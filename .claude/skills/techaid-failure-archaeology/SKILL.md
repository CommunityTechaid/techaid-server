---
name: techaid-failure-archaeology
description: The historical record of every major techaid-server investigation, dead end, rejected fix, and revert — symptom, root cause, evidence, status. Load BEFORE starting any investigation, proposing a fix that touches GraphQL scalars/schema, Spring Security, Flyway/schema management, Envers auditing, or App Insights telemetry, or whenever a symptom feels like it may have happened before. Prevents re-fighting settled battles and re-proposing already-rejected fixes.
---

# TechAid Failure Archaeology

The chronicle of what went wrong in techaid-server, why, and how each battle ended. Every entry is Symptom → Root cause → Evidence → Status → Lesson. **Check this file before diagnosing anything** — several of this project's failure modes recur, and several "obvious fixes" were tried and rejected with reasons recorded here.

**Status vocabulary:** *adopted* = fix merged and in force; *settled* = question answered, no code change pending; *open* = known problem, not yet fixed; *parked* = consciously deferred with accepted risk — do not "rediscover" parked items as new findings.

## When NOT to use this skill

- **Active triage of a live failure** → use `techaid-debugging-playbook` (symptom→triage tables). Come here when the playbook doesn't match, or to check whether your hypothesis was already tried.
- **Understanding why the design is the way it is** → `techaid-architecture-contract`.
- **How to run an investigation properly** → `techaid-investigation-methodology`.
- This file records history; it is not a runbook. Nothing here authorizes bypassing `techaid-change-control`.

## Quick-lookup index

| If you're looking at… | Entry |
|---|---|
| "Expected a String input, but it was a 'Integer'" on kit imports | A1 |
| GraphQL errors invisible in logs / App Insights (HTTP 200) | A1 |
| Schema startup failure after adding/changing a scalar | A1 |
| `SchemaMappingInspector` / "Method must not be null" crash at boot | A2 |
| GraphQL field or mutation silently does nothing | A3 |
| Hibernate `validate` failing / missing tables on fresh DB | B1 |
| Flyway "must be owner of table …" | B2 |
| Duplicate or missing Envers audit records (`*_AUD` tables) | B3 |
| `value too long for type character varying(255)` on startup | B4 |
| `is_sales` filter returning wrong rows | B5 |
| No HTTP requests appearing in App Insights | C1 |
| `NoClassDefFoundError` for OpenTelemetry classes | C1 |
| Anonymous access to a mutation/query that should be gated | D1 |
| 404 on a path that "should" exist | E1 |
| Production down but no deploy happened | E2 |
| Container Apps revision stuck Unhealthy at 0 replicas | E3 |
| Logback/Janino crash at startup after dependency change | F1 |

---

## A. GraphQL & schema

### A1. The lenient-String-scalar saga (2026-06-04) — three PRs in one day

**Symptom:** Bulk kit imports from a Google Sheet + Apps Script failed with `Variable 'data' has an invalid value: Expected a String input, but it was a 'Integer'`. Worse, the failures were *invisible*: GraphQL validation/coercion errors return HTTP 200 with errors in the body, so they never hit `CustomErrorHandlerConfig` (data-fetcher exceptions only), the access log, or App Insights failed requests.

**Root cause:** Numeric spreadsheet cells (e.g. `"lotId": 26060301`) land in GraphQL `String` input fields; graphql-java's built-in String scalar rejects non-string JSON values.

**The three-step battle (all commits 2026-06-04):**
1. **Instrument first** — PR #40 (`49a0a3c`) extended `GraphQlTelemetryInterceptor` to log every GraphQL error together with the JSON-serialised input variables (fires only on error responses; `GraphQlErrorLoggingTest`). This is what pinned the culprit to `CreateKitInput.lotId`.
2. **Rejected fix** — PR #42 (`dde5891`) overrode the *built-in* `String` scalar schema-wide with lenient coercion. **It failed schema assembly and was reverted the same day** — PR #43 (`c66e4a5`).
3. **Adopted fix** — PR #44 (`ba8b276`) introduced a **distinct** `LenientString` scalar (new name, not a redefinition) applied in the SDL only to numeric-prone kit input fields — `lotId`, `locationCode`, `serialNo` — across `CreateKitInput`, `QuickCreateKitInput`, `AutoCreateKitInput`, `AutoUpdateKitInput`, `UpdateKitInput`, `BulkKitUpdateInput`. Output types and where-filters stay `String`. Guarded by `SchemaAssemblyTest` (builds the real schema from SDL + wiring and asserts `CreateKitInput.lotId` resolves to `LenientString` — the exact assembly step that failed in #42), plus `LenientStringScalarTest` and a numeric-lotId case in `GraphQlEndpointTest`.

**Evidence:** `git show 49a0a3c dde5891 c66e4a5 ba8b276`; scalar at `src/main/kotlin/cta/app/config/GraphQlConfig.kt` (`.name("LenientString")`, ~line 95); SDL usages in `src/main/resources/graphql/kits.graphqls`; tests in `src/test/kotlin/cta/`.

**Status:** Adopted.

**Lessons:** (1) Instrument before fixing — the error logging found the field in one shot. (2) **Never redefine graphql-java built-in scalars**; add a distinct named scalar and apply it surgically in the SDL. (3) When a fix touches schema assembly, add a test that performs the assembly step itself.

### A2. SchemaMappingInspector disabled after Kotlin 2.x upgrade (2026-04) — OPEN

**Symptom:** Application startup crash: Spring Boot 3.3+ `SchemaMappingInspector` fails with "Method must not be null" under the Kotlin 2.x K2 compiler.

**Root cause:** Inspector/K2 reflection incompatibility (specific mapping never isolated).

**Workaround (in force):** `spring.graphql.schema.inspection.enabled: false` in **both** `src/main/resources/application.yml` (~line 124) and `src/test/resources/application.yml` (which carries the TODO comment). First disabled in test config during the maintenance upgrade (`aee75b7`, `110f985`), then in main config (`42c1c5a`, 2026-04-16) because staging/production also crashed.

**Status:** **OPEN.** The inspector is a safety net that would have caught A3 below. Re-enabling (find the offending mapping, fix it, delete both flags) is on the roadmap — see `techaid-roadmap-and-frontier`.

**Lesson:** While inspection is off, nothing at boot verifies SDL ↔ resolver wiring. `SchemaAssemblyTest` and the wiring tests are the partial substitute; add a wiring test whenever you add a resolver.

### A3. Silently unwired resolvers — nested fields and @QueryMapping-on-mutations

**Symptom:** GraphQL fields return null / operations appear to succeed but do nothing. No error anywhere.

**Two instances:**
1. **User/Role nested resolvers** (2026-06-01): `User.role`-style nested fields were never resolved until explicit `@SchemaMapping` wiring was added — `2abedb4`, merged via PRs #36/#37 (`ef60b73`, `3c5cb01`).
2. **User admin mutations were silent no-ops**: `assignRoles`, `removeRoles`, `deleteUser`, `removePermissions` are declared under `Mutation` in `users.graphqls` but were annotated `@QueryMapping`, so the registration never matched. Fixed to `@MutationMapping` in the 2026-07 code review batch 2+3 (`e28bca2`, part of PR #48 squash `99d5c56`), covered by `UserMutationsWiringTest` / `UserRoleNestedResolverWiringTest`.

**Root cause:** With schema inspection disabled (A2), a mismatch between SDL and annotations fails silently.

**Status:** Settled (both fixed). The class of bug remains live risk while A2 is open.

**Lesson:** For every new resolver: annotation kind must match the SDL root type (`@QueryMapping` ↔ `Query`, `@MutationMapping` ↔ `Mutation`), and ship a wiring test (see `techaid-validation-and-qa`).

---

## B. Database, Flyway & Envers

### B1. Flyway baseline for the unmanaged schema (2026-07-02/03)

**Symptom:** Wanting `ddl-auto: validate` (code-review item 16), but most tables — `device_requests`, `referring_*`, `note`, audit `*_AUD` tables, `admin_config`, `donor_parents`, … — had **no CREATE TABLE migration at all**. Historically the schema was created on the fly by Hibernate `ddl-auto: update`; fresh databases contained only the Flyway-managed subset, and any environment without `SPRING_PROFILES_ACTIVE` let Hibernate silently mutate the schema.

**Fix (PR #49, squash `76b092f`, 2026-07-03):**
- `V26.07.03.0900__baseline_unmanaged_schema.sql`: sequences + tables verbatim from Hibernate 6's schema export with `IF NOT EXISTS`; update-era added columns on donors/kits; guarded FK constraints; guarded `kits.archived` varchar(1)→char(1) alter; re-run of the V26.07.02.1000 index block for fresh DBs. Entirely a no-op on databases that already have the objects.
- Default `ddl-auto` flipped `update` → `validate` (schema drift now fails boot loudly). **Production profile stays `none`.**
- Permanent `SchemaValidationTest` (red: Flyway-only embedded Postgres missing `admin_config`; green with baseline).

Side-find: under the old `update` mode the test DB randomly lacked `referring_organisation_sequence` (lazy EntityManagerFactory init), which had masked a weak assertion in `PublicSurfaceAuthorizationTest` — strengthened in the same PR.

**Status:** Adopted; verified on UAT 2026-07-03 (Flyway applied to 26.07.03.0900, Hibernate validate passed). Note: on production (always `ddl-auto: none`) the baseline may genuinely create tables Hibernate never created there — expected, guarded by `IF NOT EXISTS`.

**Lesson:** Never assume a table has a migration; check `src/main/resources/db/migration/`. New DDL must be guarded (`IF NOT EXISTS`, column-existence checks) because environments have divergent histories. See `techaid-database-operations`.

### B2. Table-ownership incident: "must be owner of table kits" (2026-07-02)

**Symptom:** UAT deploy of index migration `V26.07.02.1000__add_indexes.sql` crashed Flyway with `must be owner of table kits`.

**Root cause:** The May-2026 database restore (Dokku→Azure migration) left all tables owned by the admin login `techaid_admin`, not the app roles. DDL that requires ownership (e.g. `ALTER TABLE`) fails when run as the app user.

**Resolution:** One-off ownership-transfer scripts run as `techaid_admin`, moving all public tables + sequences to `api_uat`, and pre-emptively to `api_prod` ahead of the production promote. *(As of 2026-07-03; not repo-verifiable — operational history. The scripts were deliberately not committed.)*

**Status:** Settled for both databases. If a future restore recreates the situation, the symptom will be identical.

**Lesson:** After any restore/clone, verify table ownership matches the app role **before** the next migration deploy (`techaid-database-operations` has the check).

### B3. The duplicate-audit-record campaign (Jul–Aug 2025) and its long tail

**Symptom:** Duplicate Hibernate Envers audit rows (`*_AUD` tables) for kits; separately, a mass/recursive update misbehaviour on kit mutations.

**The battle trail (all verifiable in git):** `85da717` temp-disabled kit/device auditing (2025-07-21) → `8c48102` reverted a sequence definition to isolate the bug (2025-07-23) → `d4227e8` temp reintroduced it → `bfb784c` tried default audit revision sequence config → `2dfdb02` changed audit mode for donor/device-request relations on Kit → **`c0133fe` turned off auditing of Kit.donor because it duplicated logs** (2025-07-30) → `2a444ab`/`a169f70` extended `@NotAudited` usage → `4a7bb43` "potential fix for the mass update of kits" → `9c0c0a1` "corrections to recursive update bug" (2025-08-07, `KitMutations.kt`).

**Where it landed (current code):** entities use `@Audited(targetAuditMode = RelationTargetAuditMode.NOT_AUDITED)` at class level with `@NotAudited` on relation fields throughout `DeviceRequestModels.kt`, `DonorModels.kt`, `KitModels.kt`, etc. A later regression — device-request id missing from audit entries — was fixed 2026-03-11 (`5032eab`).

**Status:** Settled, but **fragile by construction**: audit correctness depends on per-field annotations, and the July-2025 history shows how easily relation auditing produces duplicates.

**Lesson:** Do not add or remove `@Audited`/`@NotAudited` casually; auditing relations (not just scalars) is what caused duplicates. Any Envers change needs a test asserting audit-row counts.

### B4. Long-text columns vs Hibernate 6 ALTER (2026-04-16)

**Symptom:** Startup failure on staging/production during the 2026 upgrade: `value too long for type character varying(255)` — Hibernate 6 (Spring Boot 3.4) defaults unannotated String fields to `varchar(255)` and, under `ddl-auto: update`, tried to ALTER columns that already held TEXT data.

**Fix:** `@Column(columnDefinition = "TEXT")` on `Post.content` and `DeviceRequest.details` (`b443807`). Other notes/content fields already had `@Column(length = 4096)`.

**Status:** Adopted; the whole class of drive-by ALTERs is now also blocked by B1's `validate` default.

### B5. is_sales NOT NULL fix (2026-04-29)

**Symptom:** Filtering on `device_requests.is_sales` returned wrong results — nullable boolean semantics.

**Fix:** `53f9aff` + migration `V26.04.29.1000__fix_device_request_is_sales_not_null.sql` (touched up in `01b80dc`) backfilling and enforcing NOT NULL.

**Status:** Adopted. **Lesson:** nullable booleans in filters are a trap; prefer NOT NULL with a default.

---

## C. Telemetry & observability

### C1. App Insights capturing no HTTP requests (2026-05)

**Symptom:** For weeks the App Insights `requests` table held almost nothing (only scheduled-task entries) while dependencies, traces, and metrics flowed fine — so the agent was "healthy" but blind to HTTP.

**Root cause:** Application Insights Java agent 3.5.4 did not auto-instrument the Spring Boot 3.4 / Spring 6.2 / Jakarta Servlet stack.

**Fix chain (all 2026-05-24, on master then merged to dev via `66f9294`):**
- `4e76b27` — agent bump 3.5.4 → **3.7.8** (`ARG AI_AGENT_VERSION=3.7.8` in `Dockerfile`). This restored request capture.
- `91fff28` — surface GraphQL operation name in AppRequests (span enrichment), after `770d383` did the same for the access log.
- `91c45a7` — **trap:** the OpenTelemetry API dependency was `compileOnly`, causing `NoClassDefFoundError` at runtime — the AI Java agent does *not* inject the OTel API into the app classpath. Must be `implementation` (`io.opentelemetry:opentelemetry-api` in `build.gradle`, ~line 146).
- `d0726c3` — span enrichment wrapped in `runCatching` so telemetry can never take down request handling.
- `ee162f1` — coverage for root mapping, shorthand GraphQL ops, user id; `d5390ea` — use the Auth0 email claim for enduser id instead of raw `sub`.

**Status:** Settled. **Lessons:** after any Spring Boot major/minor bump, verify the AI agent version supports it *by looking at the `requests` table*, not the agent log; OTel API must be `implementation` scope; telemetry code is always `runCatching`-guarded.

---

## D. Security & authorization

### D1. The 2026-07 authorization-gap campaign (PRs #48/#49)

**Context (the load-bearing fact):** `SecurityConfig` ends with `anyRequest().permitAll()` — the entire authorization model is per-method `@PreAuthorize`. **A resolver without an annotation is anonymous.** See `techaid-architecture-contract` for why.

**Symptom/finding:** A full code review (2026-07-02) found unauthenticated access to:
1. `synchronizeCollectionDataForDeviceRequest` — anonymous IDOR: anyone could overwrite collection data *and status* on any device request by id (the Google-Calendar sync endpoint);
2. `createReferringOrganisation`;
3. the `location` geocoding query (proxied the billed Google Places key anonymously);
4. `referringOrganisationsPublic` accepted the full admin filter input.

**Fix (batch 1, `e83aa97`, in PR #48 squash `99d5c56`):** items 1–2 gated with `write:organisations`; `location` requires authentication; `referringOrganisationsPublic` restricted to `ReferringOrganisationPublicWhereInput` (name + archived — exactly the public typeahead's shape; no dashboard change needed). All red-first in `PublicSurfaceAuthorizationTest`. Batches 2+3 (`e28bca2`): perf + hygiene (indexes migration `V26.07.02.1000`, geocoding timeouts 2s/5s, id-based `Kit.hashCode`, shared ObjectMapper, constant-time admin-secret compare, health details `when-authorized`, dead-code removal, A3's `@MutationMapping` fix).

**Consciously PARKED (do not re-report as new discoveries):**
- **DEVICE_REQUEST_LIMIT bypass via contact minting** — a public caller can create a fresh contact to evade the per-contact request limit. Accepted risk (low), 2026-07-02.
- **`enable_lazy_load_no_trans: true`** in `application.yml` — hides N+1s and lazy-loading bugs. Known tech debt.

**Live caution (as of 2026-07-03, not repo-verifiable — operational history):** the promote of these changes to production is pending. The Google Apps Script calendar sync authenticates via an Auth0 client grant that is *believed* to include `write:organisations`; the first calendar sync after the production promote must be watched for Access Denied. See `techaid-prod-promotion-campaign`.

**Status:** Fixed items adopted and verified on UAT (2026-07-02); parked items OPEN; promote-watch pending.

**Lesson:** Any new query/mutation resolver MUST get an explicit `@PreAuthorize` (or a documented decision that it is public) plus a `PublicSurfaceAuthorizationTest` case. Silence = anonymous.

---

## E. Platform & operations

### E1. UnknownPathFilter eats requests before MVC (2026-05-24/25)

**Symptom:** A path 404s even though a controller mapping exists. Classic instance: `RootController` returning 204 on `/` never got the request.

**Root cause:** `UnknownPathFilter` (`src/main/kotlin/cta/app/config/RequestFilterConfig.kt`) is a global allow-list filter registered at nearly highest precedence; anything not matching `allowedPrefixes` (`/`, `/graphql`, `/actuator`, `/login`, `/error`, `/typeform/hook`) is 404'd **before Spring MVC or Security see it**.

**Fix:** `2986ad1` freed the `/` mapping; `812693f` allowed `/` through the filter.

**Status:** Settled. **Lesson:** when adding any new endpoint path, add its prefix to `allowedPrefixes` or it will 404 mysteriously. On any unexplained 404, check this filter *first* (see `techaid-debugging-playbook`).

### E2. Production outage 2026-07-01: broken Azure host node

*(Operational history, as of 2026-07-03 — evidence lives in Azure logs, not this repo.)*

**Symptom:** `api-production` down for ~75 minutes on a weekday morning. No deploy, DB, or config change had occurred.

**Root cause:** KEDA cron scaled up on time, but the pod was scheduled onto a broken Azure host node; container creation failed 20× with `ContainerCreateFailure` — overlay filesystem mount error ("special device overlay does not exist"). Kubernetes kept retrying the same broken node. A manual stop/start rescheduled onto a healthy node and the app booted cleanly. Pure Azure platform fault.

**Outcome:** Scheduled-query alert `api-production-availability` (Sev1; fires on ContainerCreateFailure>0 or ProbeFailed>60, business hours Mon–Fri London) created 2026-07-02 and backtested over 14 days (fires only during this incident).

**Status:** Settled (platform fault); alert adopted. **Lesson:** "production down with zero changes" can be pure platform failure — check `ContainerAppSystemLogs_CL` before suspecting the app (see `techaid-diagnostics-and-observability`).

### E3. Wedged crash-looping revision (2026-07-02)

*(Operational history, as of 2026-07-03.)* A Container Apps revision whose replica crash-loops (e.g. the B2 Flyway crash) gets stuck **Unhealthy at 0 replicas**; `az containerapp revision restart` will not respawn it. Deploy a fresh `--revision-suffix` instead. Meanwhile traffic silently falls back to the previous healthy revision — the app "works" while your new code isn't running. Also: the app scales to zero — curl it and wait ~40–90s before judging its state.

**Status:** Settled operational knowledge; runbook in `techaid-deploy-and-operate`.

---

## F. Upgrade traps (maintenance-2026, Apr 2026)

The Spring Boot 3.2→3.4 / Kotlin 1.9→2.1 campaign (`033d3c9` … PRs #23/#26/#27/#28; plan in `MAINTENANCE_PLAN.md`) hit these, all settled:

| Trap | Detail | Evidence |
|---|---|---|
| F1. Logback/Janino startup crash | Explicit `logback-classic:1.4.14` pin split the classpath against Boot 3.4's BOM-managed 1.5.x (`LogbackClassicDefaultNestedComponentRules` loaded, Janino missing). Fix: drop the pin, let the BOM manage it; `logstash-logback-encoder` 6.3→7.4. | `279abcc` |
| F2. Hibernate 6 varchar(255) ALTER failure | See B4. | `b443807` |
| F3. SchemaMappingInspector crash | See A2. | `110f985`, `aee75b7`, `42c1c5a` |
| F4. Flyway 10 needs a separate PostgreSQL module | `flyway-database-postgresql` artifact required alongside core (Flyway 9→10). | `f350277`, MAINTENANCE_PLAN.md Group B |
| F5. hibernate-types → hypersistence-utils | `com.vladmihalcea.hibernate.type.json.*` imports become `io.hypersistence.utils.hibernate.type.json.*`. | `f350277` |
| F6. Docker build broke on ktlint | Gradle 8.12.1 image needed `.editorconfig` copied into the build context. | `15e8619` |

**Lesson:** version bumps here fail at *startup*, not compile time — after any dependency change, `./gradlew bootRun` (or the docker-compose flow) before calling it done.

---

## G. Minor chronicle (one-liners, all verified)

- **Snyk workflow experiments** added then reverted (2024-04): `f84992e`/`d672d2a` reverted by `5684812`/`c451fa2`. Status: settled — no Snyk CI today.
- **`isleadcontact` custom boolean type reverted** to standard boolean (2024-11-05): `ee18c73`. Precursor to the general lesson in B5.
- **Custom-error handling attempts reverted** (2025-05-22): `1fe1e96`, `ac90a99` rolled back two experimental changes in `DeviceRequestMutations.kt`; the eventual error-model work landed later as `CustomErrorModelTest`-covered code.
- **collectionDate explicit-null fix** (PR #45, `9f5c5f4`, 2026-06-04): `UpdateDeviceRequestInput.apply()` had a preserve-on-null fallback, so the dashboard couldn't clear a booking date; dropped, with `DeviceRequestMutationsTest`. Pattern to remember: "preserve on null" input-mapping defeats explicit clearing.
- **release-please adopted** (PR #39, `adb07f4`, 2026-06-01): conventional-commit titles on `dev` drive CHANGELOG + version; `bootstrap-sha` pinned so old history didn't generate a noisy bootstrap release.
- **Cold-start optimisation** for Container Apps (`c3b931f`, PR #31, 2026-04): startup-time work predating the KEDA scale-to-zero regime; cold starts remain a live UX concern (see `techaid-roadmap-and-frontier`).

---

## Provenance and maintenance

Authored 2026-07-03 from direct verification of git history and working-tree state. Entries marked "not repo-verifiable — operational history" (B2 resolution, D1 promote-watch, E2, E3) came from the maintainer's incident records as of 2026-07-03 and cannot be re-derived from this repo; treat their *lessons* as durable and their *specifics* as dated.

Re-verification one-liners:

```bash
# Any cited commit: message + files
git show --stat <hash>
# A1 saga: git show 49a0a3c dde5891 c66e4a5 ba8b276
# A2 flag still present (expect enabled: false in both):
grep -rn "inspection" src/main/resources/application.yml src/test/resources/application.yml
# A3 wiring current state:
grep -n "MutationMapping\|QueryMapping" src/main/kotlin/cta/app/graphql/queries/UsersGraph.kt
# B1 baseline + validate default:
ls src/main/resources/db/migration/ | grep V26.07 && grep -n "ddl-auto" src/main/resources/application.yml src/main/resources/application-production.yml
# B3 audit annotations:
grep -rn "NotAudited" src/main/kotlin/cta/app/ | wc -l
# C1 agent version + OTel scope:
grep -n "AI_AGENT_VERSION" Dockerfile && grep -n "opentelemetry-api" build.gradle
# D1 gates in force:
grep -n "PreAuthorize\|isAuthenticated" src/main/kotlin/cta/app/graphql/mutations/DeviceRequestMutations.kt src/main/kotlin/cta/app/graphql/mutations/ReferringOrganisationMutations.kt src/main/kotlin/cta/app/graphql/queries/GlobalQueries.kt
# E1 allow-list:
grep -n -A 8 "allowedPrefixes" src/main/kotlin/cta/app/config/RequestFilterConfig.kt
# Parked items still parked? search for new gates/tests before assuming:
grep -rn "DEVICE_REQUEST_LIMIT" src/main/kotlin/ && grep -n "enable_lazy_load_no_trans" src/main/resources/application.yml
```

When a new investigation concludes, append an entry here in the same Symptom/Root cause/Evidence/Status/Lesson format, and update the quick-lookup index.
