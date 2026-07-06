---
name: techaid-architecture-contract
description: Load BEFORE designing any change to techaid-server — adding or modifying a GraphQL resolver, HTTP endpoint, JPA entity, filter, scheduled task, or integration — and whenever judging whether a proposed change is safe. Explains the load-bearing design decisions (default-open security with per-method @PreAuthorize, UnknownPathFilter allow-list, schema-first GraphQL with inspection disabled, Flyway-owned schema, scale-to-zero runtime) with the WHY behind each, the invariants a diff must not break, and the known weak points. Use it to answer "will this change violate how the system is supposed to work?"
---

# TechAid Server — Architecture Contract

This skill is the contract: the design decisions this codebase depends on, why they exist, the invariants every change must preserve, and the weak points that are known and accepted (do not "fix" them casually — several look like bugs but are deliberate or parked decisions).

**The system in one paragraph:** `techaid-server` is a Kotlin 2.1 / Spring Boot 3.4 GraphQL API backing the Community TechAid dashboard. The charity receives donated devices ("kits") from donors and distributes them to clients via referring organisations that file device requests. The API serves BOTH a public surface (device-request intake, org typeahead, Typeform webhook) AND an Auth0-secured admin dashboard, from the same `/graphql` endpoint. It runs as a single small container on Azure Container Apps with scale-to-zero, against Azure PostgreSQL.

## When NOT to use this skill

- Making a commit/PR/release or asking "do I need permission for this?" → `techaid-change-control`
- Chasing a live bug or error symptom → `techaid-debugging-playbook`
- "Has this been tried before / why is it like this historically?" → `techaid-failure-archaeology`
- Business meaning of kits/requests/statuses and schema shapes → `techaid-domain-reference`
- Env vars, profiles, and their defaults → `techaid-config-and-flags`
- Writing migrations or touching the database → `techaid-database-operations`
- What tests to write and how → `techaid-validation-and-qa`

## Glossary (terms used below, defined once)

| Term | Meaning |
|------|---------|
| SDL | Schema Definition Language — the `.graphqls` files declaring the GraphQL schema |
| Resolver | A Kotlin controller method mapped to a GraphQL field (`@QueryMapping`/`@MutationMapping`/`@SchemaMapping`) |
| QueryDSL | Library generating type-safe query classes (`Q*` classes, via kapt) used for the dashboard's rich filtering |
| Envers | Hibernate module writing every entity change to `*_AUD` audit tables automatically |
| Flyway | Versioned SQL migration runner; the sole owner of the database schema |
| KEDA | Kubernetes autoscaler used by Azure Container Apps; here a cron rule scales replicas 0↔1 |
| Scale-to-zero | The app runs 0 replicas outside business hours; first request triggers a cold start |
| kapt | Kotlin annotation processing — generates QueryDSL `Q*` classes at build time |

---

## 1. Security model — the most load-bearing decision

`SecurityConfig.kt:82`:

```kotlin
http.authorizeHttpRequests { it.anyRequest().permitAll() }
```

**Every HTTP request is allowed through Spring Security's URL layer.** Authorization lives entirely in method-level annotations on individual resolvers (`@EnableMethodSecurity(securedEnabled = true)`, `SecurityConfig.kt:40`). The consequence, which you must internalize:

> **A resolver without an `@PreAuthorize` annotation is anonymous — reachable by anyone on the internet. Forgetting the annotation is not a compile error, not a test failure by default, and not visible in the schema. It is a silent security hole.**

**Why it is built this way:** the same `/graphql` endpoint serves genuinely public operations (clients submitting device requests, the public org typeahead, `buildInfo`) and admin operations. URL-level rules can't distinguish GraphQL operations, so gating moved to the method level, with public-by-default as the fallback.

**The incident that proves the failure mode (2026-07, fixed in PR #48):** three resolvers shipped without annotations and were anonymous in production — `synchronizeCollectionDataForDeviceRequest` (anonymous callers could overwrite collection data and status on any device request by id), `createReferringOrganisation`, and the `location` geocoding query (proxying a billed Google API key). All are now gated (`DeviceRequestMutations.kt:176`, `GlobalQueries.kt:26`) and locked by `PublicSurfaceAuthorizationTest`.

### The auth stack (order of evaluation)

1. **`TokenAuthenticationFilter`** (`cta/auth/TokenAuthenticationFilter.kt`) — checks the `X-Auth-Admin-Secret` header (name configured on `AuthService` via `auth.admin-header`, `AuthService.kt:10`). If present and matching `auth.admin-secret` (constant-time compare, `AuthService.kt:23`), grants a synthetic superuser with the full authority set (`read/write/delete` on `kits/donors/users/organisations`). A present-but-wrong token returns 401 immediately.
2. **Auth0 JWT resource server** — validates bearer tokens against the Auth0 issuer + audience (`AudienceValidator`). `Auth0TokenConverter` (`SecurityConfig.kt:106`) copies the JWT's `permissions` claim into Spring authorities, so `@PreAuthorize("hasAnyAuthority('write:organisations')")` checks Auth0 permissions directly.
3. **`SecretAuthenticationFilter`** — form-login path for the minimal admin pages (`/login`).

### Resolver-side helpers

`FilterService` (`cta/app/services/FilterService.kt`) is the in-resolver auth helper: `authenticated()` (has any authority), `hasAuthority("perm")`, and `userDetails()` which reads the user's name/email from custom JWT claims namespaced by the `TOKEN_ATTRIBUTE` env var (`auth0.token-attribute`) — e.g. claim `"<token-attribute>/email"`. Some resolvers gate imperatively via `filterService` instead of annotations; both count as an explicit gate, but prefer `@PreAuthorize` (visible, greppable, testable).

### The rule for every new or changed resolver

1. Add `@PreAuthorize(...)` with the narrowest sufficient authority (grep existing mutations in the same domain file for the convention — e.g. device-request admin ops use `write:organisations`), **or**
2. Deliberately public: no annotation **and** add both a rejection-of-nothing and shape-limiting test to `PublicSurfaceAuthorizationTest` (`src/test/kotlin/cta/app/graphql/mutations/PublicSurfaceAuthorizationTest.kt`) documenting that it is public on purpose. The public intake path `createDeviceRequest` (`DeviceRequestMutations.kt:91`, no annotation, capped by `DEVICE_REQUEST_LIMIT = 3` open requests per contact) is the canonical example.

Public surfaces must also constrain their input shape: `referringOrganisationsPublic` takes a dedicated `ReferringOrganisationPublicWhereInput` (name + archived only) rather than the full admin filter type, so anonymous callers can't query arbitrary fields. Follow that pattern for any new public query.

---

## 2. HTTP filter chain — the UnknownPathFilter allow-list

`RequestFilterConfig.kt` registers two servlet filters ahead of everything else:

| Order | Filter | Job |
|-------|--------|-----|
| `Int.MIN_VALUE` | `AccessLoggingFilter` | JSON access-log line for every request (including 404s), enriched with the GraphQL operation name |
| `Int.MIN_VALUE + 1` | `UnknownPathFilter` | Rejects any path not on the allow-list with 404 — before Spring MVC ever sees it |

The current allow-list (`RequestFilterConfig.kt:79`; prefix match — `/x` also admits `/x/...`):

```
"/", "/graphql", "/actuator", "/login", "/error", "/typeform/hook"
```

**Why:** the API is directly internet-facing; scanners hammer it with `/wp-admin`, `/.env`, etc. Rejecting unknown paths in a cheap filter keeps that noise out of Spring MVC, the error handler, and the logs' signal.

> **Invariant: any new HTTP endpoint (REST controller, webhook, static path) MUST be added to `allowedPrefixes` or it will 404 for every caller — with no error in the application log beyond the access line.** This has bitten before: `/` itself returned 404 until it was added to the list so `RootController` (returns 204 to stop browser visits dispatching to `/error`) could receive it. When debugging a mysterious 404, check this filter FIRST (see `techaid-debugging-playbook`).

GraphQL resolvers do **not** need allow-list changes — they all ride on `/graphql`.

---

## 3. GraphQL layer — schema-first, inspection off

- **Schema:** SDL files in `src/main/resources/graphql/*.graphqls` (one per domain: kits, deviceRequests, donors, referringOrganisations, users, notes, audit trails…). Kotlin `@Controller` classes in `cta/app/graphql/{queries,mutations}` implement them with Spring for GraphQL annotations. Nested/computed fields use `@SchemaMapping`.
- **`@QueryMapping` vs `@MutationMapping` matters:** the annotation must match where the SDL declares the field. A mutation implemented with `@QueryMapping` fails at runtime, not boot (this happened — the user-admin mutations were silent no-ops until rewired; see `techaid-failure-archaeology`).
- **Schema inspection is DISABLED** (`spring.graphql.schema.inspection.enabled: false`, `application.yml:124`), because Spring's `SchemaMappingInspector` crashes on this codebase since the Kotlin 2.x upgrade (open TODO in `src/test/resources/application.yml`). **Consequence: SDL↔controller drift is NOT caught at boot.** The compensating controls are `SchemaAssemblyTest` plus per-feature wiring tests (`*WiringTest`) — which is why `techaid-validation-and-qa` requires a wiring test for every new resolver.
- **Custom scalars** (`GraphQlConfig.kt`): `Instant` (ISO-8601), `BigDecimal`, `Long`, and `LenientString` — a String-like input scalar that also accepts numbers/booleans and stringifies them. It exists because spreadsheet bulk imports send numeric cells into String fields (e.g. numeric `lotId`); it is applied in the SDL only to numeric-prone kit input fields (`lotId`, `locationCode`, `serialNo` in `kits.graphqls`). **Invariant: never redefine a built-in scalar (String, Int…) — a schema-wide String override failed schema assembly and was reverted (PR #42→#43); the distinct-name scalar (#44) is the settled pattern.**
- **Telemetry:** `GraphQlTelemetryInterceptor` (`cta/app/config/GraphQlTelemetryInterceptor.kt`) extracts the operation name per request, puts it in MDC, stashes it on the servlet request for the access log, and renames the OpenTelemetry span so App Insights shows `POST /graphql <operation>` instead of a generic entry. It also logs GraphQL errors. Wrapped in `runCatching` so telemetry failure can never 500 a request — keep that property.

---

## 4. Persistence — Flyway owns the schema

- **Entities** live in `cta/app` in per-domain files (`KitModels.kt`, `DeviceRequestModels.kt`, `DonorModels.kt`, `OrganisationModels.kt`, `models.kt`…). Repositories in `repositories.kt` extend `JpaRepository` + `QuerydslPredicateExecutor`; QueryDSL `Q*` classes are kapt-generated at build time (`build.gradle:213-214`). Per-entity `*WhereInput` filter classes in `cta/app/graphql/filters/` translate the dashboard's filter UI into QueryDSL predicates.
- **Auditing:** major entities carry `@Audited(targetAuditMode = RelationTargetAuditMode.NOT_AUDITED)`; Envers writes `_AUD` tables using `ValidityAuditStrategy` with `revision_id`/`revision_type` columns (`application.yml:102-106`). The dashboard exposes audit trails (kit + device-request audit-trail queries), so **audit history is a user-facing feature, not an internals detail — a new audited entity or column implies a Flyway migration for its `_AUD` table too.**
- **Flyway owns the schema.** `spring.flyway.out-of-order: true` (`application.yml:117`); migrations in `src/main/resources/db/migration` named `V<yy.mm.dd.hhmm>__desc.sql`. `ddl-auto` defaults to **`validate`** (Hibernate checks the schema matches the entities and refuses to boot on mismatch) and is **`none`** in the production profile. **Why validate and not update:** the schema historically grew via Hibernate `update` with no baseline migrations, which meant fresh databases and the real databases silently diverged; a baseline migration (`V26.07.03.0900__baseline_unmanaged_schema.sql`) captured the full schema and `validate` became the default (PR #49, 2026-07-03). **Invariant: every schema change is a Flyway migration; never rely on `ddl-auto: update`, and never hand-edit a deployed database (see `techaid-database-operations` for migration discipline and the table-ownership trap).**
- `open-in-view: false` but `hibernate.enable_lazy_load_no_trans: true` — lazy associations load via temporary sessions outside transactions. This is a **known weak point** (hides N+1 query problems, extra connections), documented as parked tech debt (see §8). Don't add code that depends on it when a `JOIN FETCH` or DTO projection will do.

---

## 5. Runtime posture — built for scale-to-zero

- `spring.main.lazy-initialization: true` (`application.yml:121`) — beans initialize on first use to cut cold-start time on Azure Container Apps (introduced by commit `c3b931f` "Optimize Azure Container Apps cold start time"). **Side effect to remember:** startup no longer exercises every bean, so a misconfigured lazily-created bean (e.g. a bad JWT issuer) surfaces on first *use*, not at boot. The 2026-07 schema-baseline work also found the test-suite corollary: lazy EntityManagerFactory init meant DB objects appeared nondeterministically under the old `update` mode.
- **Scale profile (as of 2026-07-03, applied by `infra/apply-scale-rules.sh` — scale config is NOT in any IaC file and must be re-applied if the app is recreated):** `api-production` runs 1 replica Mon–Fri 08:00–20:00 Europe/London via a KEDA cron rule, 0 replicas otherwise (min 0 / max 1).
- **Consequence for `@Scheduled` tasks:** `DeclineIncompleteDeviceRequests` (`cta/app/schedulingtasks/`, cron `0 */20 * * * *` UTC) declines device requests whose Typeform second step never completed within 20 minutes. It only runs while a replica is up — overnight/weekend, stale requests wait until the next scale-up (or a cold start triggered by traffic) to be declined. This is accepted behavior, not a bug. **Invariant: never add a scheduled task that assumes continuous uptime or exact timing; design them idempotent and catch-up-safe, like the existing one.**
- Max 1 replica also means **no concurrency between instances is currently possible** — code is not written for multi-replica coordination (scheduled tasks have no distributed lock). Raising `maxReplicas` above 1 is an architectural change, not a tuning knob.

---

## 6. Integrations — each one is a trust boundary

| Integration | Entry point | Trust mechanism | Notes |
|-------------|------------|-----------------|-------|
| Auth0 | JWT on `/graphql` | Issuer + audience validation; `permissions` claim → authorities | The only identity provider for the dashboard |
| Google Apps Script calendar sync | `synchronizeCollectionDataForDeviceRequest` mutation | Auth0 client-credentials token with `write:organisations` (gated since PR #48) | Partial update: keeps `?: entity` fallbacks on purpose, unlike the full-replace update mutation |
| Typeform (device-request step 2) | `POST /typeform/hook` (`TypeformWebhookController`) | HMAC-SHA256 signature over the raw payload, constant-time compare (`TypeformService.validateHMACSignature`) | Correlates via hidden `corr_id` field → `markRequestStepsCompleted`. The two-step intake: `createDeviceRequest` (public, sets `correlationId`) → Typeform equalities form → webhook completes it, or the scheduler declines it after 20 min |
| Gmail API | Outbound only (`MailService`) | OAuth refresh token; **every send site must check `mailService.emailEnabled`** (`gmail.enabled`, default false) | Guard is at call sites (`DeviceRequestService.kt:74,114`), not inside `sendMessage` — a new send site that forgets the check will email real people from a test environment |
| Google Places geocoding | `location` query → `LocationService` | `@PreAuthorize("isAuthenticated()")` — billed key, so no anonymous access | RestTemplate has 2s connect / 5s read timeouts because a stalled call can exhaust the small container's Tomcat thread pool (`LocationService.kt:29-37`) |

**Invariant for any new outbound HTTP client: set explicit connect/read timeouts.** The container is small (0.5 vCPU class); a handful of stuck threads is an outage.

---

## 7. The invariants checklist

Check any diff against this table. "MUST" means a violation is a blocking review finding.

| # | Invariant | How to check |
|---|-----------|--------------|
| 1 | Every new/changed resolver has `@PreAuthorize` (or `filterService` gate), or is deliberately public AND covered in `PublicSurfaceAuthorizationTest` | Read the resolver; grep the test |
| 2 | Public queries constrain input to a dedicated public where-input shape | Compare SDL input types |
| 3 | Any new non-`/graphql` HTTP path is added to `UnknownPathFilter.allowedPrefixes` | `RequestFilterConfig.kt:79` |
| 4 | SDL change ↔ controller annotation match (`@MutationMapping` for Mutation fields), proven by a wiring/assembly test — boot will NOT catch drift | Test exists and fails without the change |
| 5 | Never redefine built-in GraphQL scalars; extend via distinct-name scalars like `LenientString` | `GraphQlConfig.kt` |
| 6 | Every schema change ships as a Flyway migration; `ddl-auto` stays `validate` (default) / `none` (production) | `src/main/resources/db/migration` |
| 7 | New/changed audited entities update the corresponding `_AUD` table in the same migration | Migration SQL |
| 8 | Outbound HTTP clients have explicit timeouts | Client construction code |
| 9 | Email sends check `mailService.emailEnabled` at the call site | Grep new send sites |
| 10 | Scheduled tasks are idempotent and tolerate the app being down for hours | Task logic |
| 11 | No code assumes >1 replica or continuous uptime; no state in the container filesystem | Design review |
| 12 | Telemetry/logging code can never fail a request (`runCatching` or equivalent) | Interceptor/filter changes |
| 13 | Every behavior change ships red/green tests and routes through `techaid-change-control` — this contract never overrides that | PR contents |

---

## 8. Known weak points — stated plainly (as of 2026-07-03)

Do not silently "fix" these; each is either accepted, parked with an owner decision, or needs a campaign. Route changes through `techaid-change-control`.

| Weak point | Status | Detail |
|------------|--------|--------|
| Default-open security model itself | Accepted design | `permitAll()` + per-method gates means one forgotten annotation = anonymous access. Mitigations: `PublicSurfaceAuthorizationTest`, invariant #1, reviewer discipline. A deny-by-default redesign would be a major change needing a full dashboard/public-surface audit. |
| Schema inspection disabled | Open TODO | `SchemaMappingInspector` crashes since Kotlin 2.x upgrade (`src/test/resources/application.yml` TODO). Drift risk is real; wiring tests compensate. Re-enabling = find and fix the offending mapping. |
| `enable_lazy_load_no_trans: true` | Parked tech debt (2026-07-02) | Hides N+1s; removal requires auditing every lazy access path. Don't extend reliance on it. |
| `DEVICE_REQUEST_LIMIT` bypass via contact minting | Parked, accepted risk (2026-07-02) | The 3-open-requests cap is per referring-org contact; the public surface allows creating contacts, so a determined caller can mint a contact to reset the cap. Judged low-risk. |
| `server.max-http-header-size: 50MB` (+ 50MB form post) | Rationale unverified | In `application.yml:48`. Presumably for large payloads/imports, but no in-repo justification found — treat as suspicious-but-deliberate; don't change without testing the bulk-import path. |
| Three Dockerfiles | Accepted | `Dockerfile` (prod/UAT, builds jar + App Insights agent), `Dockerfile.dev`, `Dockerfile.local`. Only `Dockerfile` is what CI ships. |
| Dokku-era leftovers | Dead config, kept | Tracked: `charts/`, `manifests/`, `Procfile` (plus some gitignored maintainer-local cutover scripts). All predate the Azure Container Apps migration and are NOT live deployment config (`.github/workflows/` + `infra/apply-scale-rules.sh` are). `Procfile` is still COPY'd into the image but unused at runtime. Mention, don't delete, per CLAUDE.md surgical-changes rule. |
| DGS codegen plugin declared but unconfigured | Vestigial (verified 2026-07-03, see techaid-build-and-env) | `build.gradle:31` applies `com.netflix.dgs.codegen` with no task configuration and no generated sources referenced; controllers are hand-written. Removal still needs its own PR + build check. |
| Scale config not in IaC | Accepted, documented | KEDA rules live only in Azure; `infra/apply-scale-rules.sh` is the source of truth to re-apply. |

---

## Provenance and maintenance

Authored 2026-07-03 against branch `dev` (HEAD `76b092f`). Historical claims (PR #42/#43/#44 scalar saga, PR #48 auth gaps, PR #49 baseline) come from this repo's git log — re-check with `git log --oneline --grep=<keyword>`.

Re-verify volatile facts:

- Security default: `grep -n "permitAll" src/main/kotlin/cta/app/config/SecurityConfig.kt`
- Allow-list contents: `sed -n '78,88p' src/main/kotlin/cta/app/config/RequestFilterConfig.kt`
- Ungated resolvers (audit): `grep -rn -B3 "@MutationMapping\|@QueryMapping" src/main/kotlin/cta/app/graphql | grep -v PreAuthorize` then inspect each hit's preceding lines
- Inspection still disabled: `grep -n -A3 "inspection" src/main/resources/application.yml`
- ddl-auto defaults: `grep -n "ddl-auto" src/main/resources/application*.yml src/main/resources/application-production.yml`
- LenientString SDL usage: `grep -rn "LenientString" src/main/resources/graphql/`
- Scheduled task cron: `grep -rn "@Scheduled" src/main/kotlin/`
- Scale windows: `sed -n '50,60p' infra/apply-scale-rules.sh` (and live: `az containerapp show -n api-production -g tada-2026 --query "properties.template.scale"`)
- Envers strategy: `grep -n -A5 "envers" src/main/resources/application.yml`
- Request limit: `grep -rn "DEVICE_REQUEST_LIMIT" src/main/kotlin/`
