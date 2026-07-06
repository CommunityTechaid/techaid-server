---
name: techaid-debugging-playbook
description: Symptom-to-triage playbook for debugging techaid-server. Load FIRST when investigating any bug, error, or misbehaviour — a 404 on a path that should exist, GraphQL "Access Denied", a mutation that succeeds but changes nothing, "Expected a String input" coercion errors, slow or unresponsive UAT/production, a deploy that did not take effect, Flyway migration failures, schema-validation startup crashes, missing Application Insights telemetry, failing local tests, or a production outage. Gives the first check, the historical trap, and a discriminating experiment for each known failure mode.
---

# TechAid Server Debugging Playbook

Triage guide for the failure modes this project has actually hit, in the order you should suspect them. Each section gives: the symptom, the **first check** (cheap, copy-pasteable), the **trap** that cost real time historically, and a **discriminating experiment** that separates competing hypotheses before you change anything.

Run shell commands in **Git Bash** (not PowerShell — BitDefender blocks PowerShell process spawning on the maintainer's machine, as of 2026-07-03). Prefix `az` commands that take `/subscriptions/...` resource IDs with `MSYS_NO_PATHCONV=1` so Git Bash doesn't mangle the path.

**Jargon used below** (defined once):

- **UAT** — the user-acceptance environment: Container App `api-testing` in resource group `tada-2026`, at `https://api-testing.communitytechaid.org.uk`, auto-deployed on every push to `dev`.
- **Production** — Container App `api-production` in `tada-2026`, deployed only by the manual `promote.yml` workflow.
- **Revision** — an immutable Azure Container Apps deployment version; traffic points at one healthy revision.
- **KEDA cron scaling** — `api-production` runs 1 replica on a business-hours cron schedule (Europe/London) and 0 outside it, via KEDA cron rules (`infra/apply-scale-rules.sh`). `api-testing` has **no cron rule** (live-verified 2026-07-04): plain 0–1 replicas, waking on any HTTP request at any hour and scaling to 0 when idle.
- **Resolver** — a Kotlin controller method that serves a GraphQL field, wired by annotation (`@QueryMapping`, `@MutationMapping`, `@SchemaMapping`) to the SDL in `src/main/resources/graphql/*.graphqls`.
- **Flyway** — the SQL migration runner; migrations live in `src/main/resources/db/migration/`.
- **zonky** — embedded Postgres binaries used by DB-backed tests (no Docker daemon needed).

## Symptom → first check

| # | Symptom | First check |
|---|---------|-------------|
| 1 | Valid-looking path returns 404 | `UnknownPathFilter` allow-list in `src/main/kotlin/cta/app/config/RequestFilterConfig.kt` |
| 2 | GraphQL returns "Access Denied" | `@PreAuthorize` on the resolver + caller's Auth0 scopes |
| 3 | Mutation "succeeds" but changes nothing | Annotation vs SDL: `@QueryMapping` where schema says `Mutation`, or missing `@SchemaMapping` |
| 4 | "Expected a String input, but it was a 'Integer'" | Is the field typed `LenientString` in the SDL? |
| 5 | UAT/prod "down" or first request very slow | Cold start — wait ~40–90s, then check revision state |
| 6 | Deploy "succeeded" but app not updated | Wedged crash-looping revision; traffic fell back to the old one |
| 7 | Flyway migration fails on deploy | Ownership/permissions error class ("must be owner of table …") |
| 8 | Startup crash: schema-validation error | `ddl-auto` is `validate` by default — a migration is missing |
| 9 | App Insights data missing | Querying classic tables instead of workspace tables; agent version |
| 10 | Local tests fail with DB/connection errors | zonky embedded-Postgres provider config |
| 11 | Prod outage, no deploy or config change | Azure platform fault is a real possibility |

---

## 1. Request to a valid-looking path returns 404

**First check** — the global allow-list filter, *before* suspecting Spring Security or MVC mappings:

```bash
grep -n -A 10 "allowedPrefixes" src/main/kotlin/cta/app/config/RequestFilterConfig.kt
```

`UnknownPathFilter` runs at order `Int.MIN_VALUE + 1` — ahead of Spring Security and MVC — and returns 404 for any path not on its allow-list. As of 2026-07-03 the list is exactly:

```
"/", "/graphql", "/actuator", "/login", "/error", "/typeform/hook"
```

A path passes only if it equals an entry or starts with `entry + "/"`. Anything else never reaches a controller.

**The trap:** the filter silently ate `"/"` itself, so `RootController`'s 204 for root probes never fired — every hypothesis about welcome pages and security config was wrong. Fixed by adding `"/"` to the list in commit `812693f`.

**Discriminating experiment:** temporarily raise logging or just compare the failing path against the list above. If the path is absent from the list, it's this filter — full stop. If the path *is* on the list and you still get 404/403, only then investigate MVC mappings (`@RequestMapping`/`@GetMapping` in `src/main/kotlin/cta/controllers/` and `cta/app/config/RootController.kt`) or auth.

**Adding a new endpoint?** You must add its prefix to `allowedPrefixes` or it will 404 forever. That change is behaviour-affecting — route it through techaid-change-control.

## 2. GraphQL query/mutation returns "Access Denied"

**Model:** `SecurityConfig.kt` sets `http.authorizeHttpRequests { it.anyRequest().permitAll() }` — HTTP-level security passes everything. All authorization is per-method `@PreAuthorize` (method security). So "Access Denied" means the resolver *has* an annotation and the caller's JWT lacks the required Auth0 permission.

**First check** — find the gate on the failing operation:

```bash
grep -rn -B 2 "fun <operationName>" src/main/kotlin/cta/app/graphql/
```

Then check the caller's token: decode the JWT (e.g. paste into jwt.io) and look at the `permissions` claim against the annotation's requirement (e.g. `hasAnyAuthority('write:organisations')`).

**The trap (inverted):** a resolver with *no* annotation is **anonymous** — silence means public, not protected. This produced the P0 auth gaps fixed in PR #48 (commit `99d5c56`). If you find an ungated mutation, that is a security finding, not a convenience.

**Discriminating experiment:** `src/test/kotlin/cta/app/graphql/mutations/PublicSurfaceAuthorizationTest.kt` is the executable record of what is *deliberately* public vs gated (e.g. `referringOrganisationsPublic` is anonymous by design; `location` requires any authenticated user; `synchronizeCollectionDataForDeviceRequest` and `createReferringOrganisation` require `write:organisations`). Run it:

```bash
./gradlew test --tests "cta.app.graphql.mutations.PublicSurfaceAuthorizationTest"
```

If the test passes, the server is behaving as specified and the problem is the *caller's* Auth0 grant (client scopes in the Auth0 tenant), not the code. Design rationale and invariants: techaid-architecture-contract.

## 3. GraphQL mutation "succeeds" but does nothing

**Failure class:** SDL/controller wiring mismatch. Two historical instances:

- User-admin mutations in `UsersGraph.kt` were annotated `@QueryMapping` while `users.graphqls` declared them under `type Mutation` — they were **silent no-ops** until switched to `@MutationMapping`.
- User/Role nested fields (`Role.permissions`, `Role.users`, `User.roles`, `User.permissions`) returned nothing until wired with `@SchemaMapping` (commit `2abedb4`, merged in `3c5cb01`).

**First check** — compare annotation to SDL for the field in question:

```bash
grep -rn "fieldName" src/main/resources/graphql/ src/main/kotlin/cta/app/graphql/
```

Rule: field under `type Mutation` in SDL ⇒ `@MutationMapping`; under `type Query` ⇒ `@QueryMapping`; nested field on another type ⇒ `@SchemaMapping(typeName = "...", field = "...")`.

**Why this is silent:** GraphQL schema inspection is deliberately **disabled** (`spring.graphql.schema.inspection.enabled: false` in `application.yml` and the test config) because `SchemaMappingInspector` crashes after the Kotlin 2.x upgrade (open TODO in `src/test/resources/application.yml`). Spring will not warn you about an unmapped field at startup.

**Discriminating experiment:** write a wiring test asserting the operation returns real data through the full schema — see `src/test/kotlin/cta/app/graphql/queries/UserMutationsWiringTest.kt` and `UserRoleNestedResolverWiringTest.kt` as templates, and `src/test/kotlin/cta/graphql/SchemaAssemblyTest.kt` for schema-level assertions. If the wiring test fails on unmodified code, you've confirmed the class; fix the annotation and keep the test (red/green rule — techaid-validation-and-qa).

## 4. "Expected a String input, but it was a 'Integer'" on kit import

**Context:** the Google Sheets / Apps Script bulk importer sends numeric JSON for text-ish cells (e.g. `"lotId": 26060301`). Strict String coercion rejects it.

**First check** — is the failing input field typed `LenientString` in the SDL?

```bash
grep -n "LenientString" src/main/resources/graphql/kits.graphqls
```

As of 2026-07-03, `lotId`, `locationCode`, and `serialNo` on the kit input types (`CreateKitInput`, `QuickCreateKitInput`, `AutoCreateKitInput`, `AutoUpdateKitInput`, `UpdateKitInput`, `BulkKitUpdateInput`) are `LenientString` — a custom scalar in `GraphQlConfig.kt` that stringifies numbers and booleans on input but still rejects objects/lists.

**The trap — fenced-off wrong path:** do **not** fix this by redefining the built-in `String` scalar schema-wide. That was tried (PR #42, commit `dde5891`), failed schema assembly, and was reverted the same day (PR #43, commit `c66e4a5`). The surviving fix (PR #44, commit `ba8b276`) is the *distinct* `LenientString` scalar applied only to numeric-prone fields. If a new field hits this error, type that field `LenientString` in the SDL — a one-line SDL change plus a `SchemaAssemblyTest` assertion.

**Discriminating experiment:** the exact offending input is logged. `GraphQlTelemetryInterceptor` logs every GraphQL error *with the input variables* (`"GraphQL error on operation '…' … — input variables: …"`). Find that log line (locally on stdout; on Azure via container logs — techaid-diagnostics-and-observability) to see precisely which field carried a number. Full saga: techaid-failure-archaeology.

## 5. UAT/production "down" or first request very slow

**First check** — it's probably a cold start, not an outage. Both apps scale to 0 when idle, and 0→1 takes tens of seconds — but their schedules differ: `api-production` is 0 outside business hours by KEDA cron design (a 21:00 prod "outage" is expected state), while `api-testing` wakes on any HTTP request at any hour (a UAT cold start at 14:00 is normal idle scale-down; a UAT that *never* wakes is a real failure):

```bash
time curl -s -o /dev/null -w "%{http_code}\n" --max-time 90 https://api-testing.communitytechaid.org.uk/actuator/health
```

Wait ~40–90 seconds (canonical figure: techaid-diagnostics-and-observability) before judging state. Note `/actuator/health` returns status only (`show-details: when-authorized`) — `{"status":"UP"}` is the healthy response for anonymous callers.

**Discriminating experiment — cold start vs crash:**

```bash
az containerapp revision list -g tada-2026 -n api-testing \
  --query "[].{name:name,active:properties.active,health:properties.healthState,replicas:properties.replicas}" -o table
```

- Active revision Healthy, replicas 0→1 while you watch, then 200: **cold start**. Normal.
- Active revision Unhealthy or replica restarting: a real failure — check `ContainerAppSystemLogs_CL` / `ContainerAppConsoleLogs_CL` in the Log Analytics workspace (queries and interpretation: techaid-diagnostics-and-observability), and see §6 and §11.

Production's cron schedule is applied by `infra/apply-scale-rules.sh` (KEDA cron, `Europe/London`, min 0 / max 1; `api-testing` intentionally has no cron rule) — scale config is **not** in any IaC file and must be re-applied if an app is recreated. Current rules per app: techaid-deploy-and-operate.

## 6. Deploy "succeeded" but the app is not updated (or revision unhealthy)

**The trap (observed 2026-07-02 during the PR #48 deploy):** a revision whose replica crash-loops gets **wedged Unhealthy with 0 replicas**. Restarting it will *not* respawn the replica, and Container Apps silently keeps routing traffic to the previous healthy revision — so the site looks "up" while running old code, and the CI job that ran `az containerapp update` reports success.

**First check** — what is actually serving traffic?

```bash
az containerapp show -g tada-2026 -n api-testing \
  --query "{image:properties.template.containers[0].image, latestReady:properties.latestReadyRevisionName, latest:properties.latestRevisionName}" -o json
```

If `latestReady` ≠ `latest`, the new revision never became healthy. Confirm which commit is live via the app itself — canonical check:

```bash
curl -s https://api-testing.communitytechaid.org.uk/actuator/info
```

(Compare `git.commit` against the SHA CI deployed — image tags are `dev-<7-char-sha>`. Alternative when only `/graphql` is reachable: POST `{ buildInfo { commit } }`.)

**Fix:** do not fight the wedged revision — deploy a **fresh revision** with a new `--revision-suffix`. The recovery commands live in techaid-deploy-and-operate §4 (the home copy). That command is **mutating — it requires explicit maintainer sign-off per techaid-change-control, rule 5**; in an active incident, request sign-off in the same message that reports the finding. Then find *why* the replica crash-looped (usually a startup failure — §7/§8) before promoting anything.

## 7. Flyway migration fails on deploy

**Failure class seen in production use:** Postgres permission/ownership. The V26.07.02.1000 index migration crashed with `must be owner of table kits` because a May-2026 restore left all tables owned by `techaid_admin` instead of the app roles. Ownership of public tables and sequences was transferred to `api_uat` and `api_prod` on 2026-07-02 (operational fact, not recorded in repo), so this specific cause should not recur — but any future restore/import can reintroduce it.

**First check** — read the actual Flyway error in the container console logs (the app crashes at startup, so see §6 for the wedged-revision consequence). Ownership check, from a psql session as admin:

```sql
SELECT tablename, tableowner FROM pg_tables WHERE schemaname = 'public' AND tableowner <> current_user;
```

**Discriminating experiment:** does the same migration apply cleanly on a fresh empty DB? Run the test suite — `src/test/kotlin/cta/db/IndexMigrationTest.kt` and `SchemaValidationTest.kt` execute migrations against embedded Postgres. If tests pass but the environment fails, the problem is environmental (ownership, permissions, pre-existing objects), not the SQL.

Deep procedure — connecting to the Azure DBs, ownership transfer, baseline strategy, `out-of-order: true` semantics: techaid-database-operations. Any manual DDL on `techaid_prod` requires explicit maintainer instruction (techaid-change-control).

## 8. Startup crash: Hibernate schema-validation error

**Model:** `spring.jpa.hibernate.ddl-auto` defaults to **`validate`** (`${DDL_AUTO:${ddl-auto:validate}}` in `application.yml`); the `production` profile sets it to **`none`** (`application-production.yml`). Hibernate never creates or alters tables — Flyway owns the schema (baseline migration `V26.07.03.0900__baseline_unmanaged_schema.sql`, PR #49 / commit `76b092f`).

**First check** — read the validation message: it names the exact missing/mismatched table or column.

**The rule:** a missing table/column means **a Flyway migration is missing** for a mapped entity. Write the migration. Do **not** flip `DDL_AUTO` to `update` — the project deliberately moved off `update` (it hid schema drift and produced nondeterministic test DBs).

**Discriminating experiment:**

```bash
./gradlew test --tests "cta.db.SchemaValidationTest"
```

This boots Hibernate `validate` against a migrated embedded DB. Fails locally too ⇒ entity/migration mismatch in the code — fix the migration. Passes locally but the environment crashes ⇒ that environment's DB has drifted (restore, manual DDL) — techaid-database-operations.

## 9. Application Insights data missing

**First check** — you're probably querying the wrong tables. `TaDa-API` is a **workspace-based** App Insights component: query `AppRequests`, `AppTraces`, `AppDependencies`, etc. in the Log Analytics workspace, **not** the classic `requests`/`traces` tables (as of 2026-07-03; queries, workspace name, and interpretation guides: techaid-diagnostics-and-observability).

**Historical traps (both fixed, both instructive):**

- Agent 3.5.4 did not instrument the Spring Boot 3.4 / Jakarta servlet stack — dependencies and traces flowed, but `AppRequests` was nearly empty. Fixed by bumping to 3.7.8 (commit `4e76b27`; `AI_AGENT_VERSION` build arg in `Dockerfile`). Lesson: "the agent is healthy" (some tables flowing) does not mean "all telemetry is flowing".
- OTel API declared `compileOnly` caused `NoClassDefFoundError` at runtime — the App Insights Java agent does **not** inject the OpenTelemetry API onto the classpath. Fixed to `implementation` scope (commit `91c45a7`). Span-enrichment code is guarded with `runCatching` (commit `d0726c3`) so telemetry bugs can't take down request handling.

**Discriminating experiment:** is the gap in *collection* or in *your query*? Check `performanceCounters`/`AppPerformanceCounters` for the same time range — the agent emits those constantly when attached. Present ⇒ agent alive, your table/query is wrong or that telemetry type isn't instrumented. Absent ⇒ agent/connection-string problem (`APPLICATIONINSIGHTS_CONNECTION_STRING` env on the Container App; `applicationinsights.json` at repo root configures the agent).

## 10. Local tests fail with database/connection errors

**Model:** DB-backed tests use **zonky embedded Postgres binaries** — no Docker daemon required. Configured in `src/test/resources/application.yml`:

```yaml
zonky.test.database.provider: zonky
```

and in `build.gradle` (`io.zonky.test:embedded-database-spring-test`, `embedded-postgres` — which pull the OS-specific Postgres binaries transitively).

**First checks:**

```bash
./gradlew clean test
```

- Errors about Docker ⇒ the provider override isn't being picked up (are you running tests with the right classpath/resources?).
- Binary download/extraction errors on Windows ⇒ antivirus interference; run from Git Bash, and note BitDefender blocks PowerShell spawning (as of 2026-07-03).
- A test needing external services: everything external is neutered in test config — Gmail disabled, Google Places pointed at closed port `http://127.0.0.1:1/geocode` so geocoding fails fast. A test hanging on an outbound call means new code bypassed those test settings.

Full environment setup: techaid-build-and-env. Test-writing conventions: techaid-validation-and-qa.

## 11. Production outage with no deploy or config change

**Take the platform-fault hypothesis seriously.** The 2026-07-01 morning outage: KEDA scaled up on schedule, but the pod landed on a broken Azure host node — container creation failed ~20× over 75 minutes with `ContainerCreateFailure` (overlay filesystem mount error), and Kubernetes kept retrying the same broken node. No app, DB, or deploy change was involved. Recovery: manual stop/start of the Container App, which rescheduled the pod onto a healthy node. (Operational fact as of 2026-07-03, not recorded in repo files; full chronicle: techaid-failure-archaeology.)

**First check** — system logs for the failure reason:

`ContainerAppSystemLogs_CL` in the Log Analytics workspace, `Reason_s` values of interest: `ContainerCreateFailure`, `ProbeFailed`, `KEDAScaleTargetActivated` (exact queries: techaid-diagnostics-and-observability).

**Recovery command** (mutating — requires explicit maintainer sign-off per techaid-change-control, rule 5, with no firefighting exception; in an active outage, request sign-off in the same message that reports the finding):

```bash
az containerapp stop -g tada-2026 -n api-production && az containerapp start -g tada-2026 -n api-production
```

**Discriminating experiment:** platform fault vs bad image — did the currently-failing image previously run healthy (check revision history and `git.commit` from `/actuator/info`)? Same image ran fine before and nothing was deployed ⇒ platform/node. New image ⇒ treat as §6/§7/§8. A scheduled alert (`api-production-availability`, Sev1, business-hours-gated) now fires on `ContainerCreateFailure` or sustained `ProbeFailed` (created 2026-07-02).

Also known noise (as of 2026-07-03): spurious off-hours KEDA activations (~2–5/night, empty trigger reason, replica runs ~5 min) — harmless, pre-dates the outage, do not chase.

---

## When NOT to use this skill

- **Writing or reviewing a change** (gates, branch rules, red/green TDD) → techaid-change-control.
- **The full story of a past incident** → techaid-failure-archaeology (this playbook only carries the trap + fix).
- **Design rationale / invariants** ("why permitAll?", "why is inspection disabled?") → techaid-architecture-contract.
- **Running KQL queries, reading dashboards, alert definitions** → techaid-diagnostics-and-observability.
- **Deploy/rollback/promotion procedures** → techaid-deploy-and-operate; production promotion specifically → techaid-prod-promotion-campaign.
- **Migration authoring, DB connections, restores** → techaid-database-operations.
- **Setting up a dev machine / build failing before tests even run** → techaid-build-and-env.
- **What a domain term means** (kit, device request, referring organisation) → techaid-domain-reference.
- **Config/env-var lookup** → techaid-config-and-flags.

## Provenance and maintenance

Authored 2026-07-03 against `dev` @ `76b092f`. Facts verified directly against the working tree and `git log`; items marked "operational fact" (table-ownership transfer, ~40–90s cold start, alert rule, KEDA noise, outage details) live in Azure, not the repo, and were current as of 2026-07-03 (api-testing's no-cron-rule scaling: live-verified 2026-07-04).

Re-verify volatile facts:

- Allow-list contents: `grep -A 10 "allowedPrefixes" src/main/kotlin/cta/app/config/RequestFilterConfig.kt`
- permitAll + method-security model: `grep -n "permitAll\|EnableMethodSecurity" src/main/kotlin/cta/app/config/SecurityConfig.kt`
- LenientString field coverage: `grep -rn "LenientString" src/main/resources/graphql/`
- Schema inspection still disabled: `grep -rn "inspection" src/main/resources/application.yml src/test/resources/application.yml`
- ddl-auto defaults: `grep -rn "ddl-auto" src/main/resources/application.yml src/main/resources/application-production.yml`
- zonky provider: `grep -n "provider" src/test/resources/application.yml`
- AI agent version: `grep -n "AI_AGENT_VERSION" Dockerfile`
- Scale rules: `cat infra/apply-scale-rules.sh`
- Revision/traffic state (live): `az containerapp revision list -g tada-2026 -n api-production -o table`
- Cited commits still exist: `git log --oneline 812693f 2abedb4 4e76b27 91c45a7 dde5891 c66e4a5 ba8b276 76b092f -n 1` (each)
