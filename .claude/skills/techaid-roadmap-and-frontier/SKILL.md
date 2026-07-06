---
name: techaid-roadmap-and-frontier
description: The vetted backlog of open improvement candidates for techaid-server. Load when asked "what should we work on next", when planning improvements or roadmap work, when evaluating whether an idea is new or already known/parked/rejected, when scoping cold-start, security-lockdown, tech-debt, test-coverage, repo-cleanup, or analytics work, or before proposing any enhancement — to check it is not already listed here with a decided status.
---

# TechAid Roadmap and Frontier

The honest "advance the project" layer for techaid-server. Every item below is
**open/candidate** — analysed, sometimes designed, but NOT implemented and NOT
decided. Nothing here is a commitment. The maintainer defined "advancement" for
this project as two things only:

1. **Operational excellence** — reliability, cost (steady-state Azure spend is
   ~£70/month as of 2026-06), observability, zero-touch verified deploys. Make
   the platform boring and self-verifying.
2. **Data & impact insight** — device-flow and fulfilment analytics the charity
   can put in front of funders, and what the API/DB must expose for it.

There is no academic/paper ambition here. Do not oversell: if you pick up an
item, it stays "candidate" until it has survived the promotion protocol at the
bottom of this file.

**When NOT to use this skill:**
- Executing the production promote → `techaid-prod-promotion-campaign` (a
  pending promote is an *operation*, never a roadmap item).
- Debugging a live failure → `techaid-debugging-playbook`.
- "Has this bug/fix been tried before?" → `techaid-failure-archaeology`.
- How to run an investigation properly → `techaid-investigation-methodology`.
- What the system's design invariants are → `techaid-architecture-contract`.

**Candidate index** (details in the numbered sections below):

| ID | Title | Status |
|---|---|---|
| A1 | Cold-start UX on scale-to-zero | open, measure first |
| A2 | Re-enable the GraphQL SchemaMappingInspector | open |
| A3 | Retire `enable_lazy_load_no_trans` | open, parked tech debt |
| A4 | Close the DEVICE_REQUEST_LIMIT bypass via contact minting | open, accepted-risk (2026-07-02) |
| A5 | Test-coverage gaps from the 2026-07 review | open (1 of 3 already closed) |
| A6 | Repo hygiene: dead deploy-era artifacts and stale docs | open |
| A7 | URL-layer security lockdown | open/candidate, analysed 2026-05-19 |
| A8 | Zero-touch deploy verification | open |
| B1 | Impact analytics on device flow | open, no reporting layer exists |
| B2 | The 20-minute auto-decline rate | open, unmeasured |

**Jargon used below** (defined once):
- **KEDA cron scale-to-zero**: the production Container Apps run 1 replica
  Mon–Fri business hours (London) and 0 replicas otherwise, via KEDA cron
  rules applied by `infra/apply-scale-rules.sh` (rules are NOT in IaC; that
  script is the source of truth).
- **Cold start**: first request after the replica count goes 0→1; the JVM +
  Spring context must boot before the request is served.
- **Envers audit tables**: Hibernate Envers writes a full row-history of
  every audited entity. The default suffix is `_AUD` (configured in
  `application.yml`), but `Kit` and `DeviceRequest` override it with
  `@AuditTable` — their history lives in `kit_audit_trail` and
  `device_requests_audit_trail` (full name list in
  `techaid-database-operations`). Both entities are `@Audited`.
- **Red/green test**: project rule — every behaviour change ships with a test
  that failed before the change and passes after (see
  `techaid-validation-and-qa`).

---

## Part A — Operational excellence candidates

Each item: why it matters / current state (verified in-repo 2026-07-05) /
the project's asset or leverage / first three steps in this repo / a
falsifiable "you have a result when…" milestone.

### A1. Cold-start UX on scale-to-zero — status: open, measure first

**Why:** After off-hours scale-to-zero, the first request of the day boots the
whole JVM + Spring context. Observed cold-start latency was ~40–90 s as of
2026-07-03 (canonical figure: `techaid-diagnostics-and-observability`).
Users perceive the product as "slow in the morning".

**Current state (verified):** `Dockerfile` runs a plain
`java -jar` with `-XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0`
plus the Application Insights `-javaagent` (which itself adds startup cost).
No CDS/AOT anywhere (`grep -n "CDS\|aot" build.gradle Dockerfile` → no hits).
One mitigation already in place: `spring.main.lazy-initialization: true` in
`application.yml`.

**Asset:** every request is already telemetered (App Insights `AppRequests`),
so cold-vs-warm latency is measurable without new instrumentation.

**First three steps:**
1. Measure before touching anything: query `AppRequests` for the distribution
   of first-request-after-scale-up latency vs warm p50/p95 (query patterns in
   `techaid-diagnostics-and-observability`). Write the numbers down.
2. Rank the candidate menu against those numbers, each with its obligation:
   - **CDS/AppCDS archive** (Spring Boot 3.4 supports the extracted-launcher
     + `-XX:SharedArchiveFile` flow): obligation = Dockerfile build change +
     measured before/after boot timings.
   - **Wider min-replica windows** (extend the KEDA cron window or min=1):
     obligation = quantified monthly cost delta against the ~£70/mo baseline.
   - **JVM tuning** (e.g. tiered-compilation flags): obligation = measured
     boot-time delta, no regression in warm latency.
3. Prototype the cheapest candidate on UAT (`api-testing` scales 0→1 too, so
   cold starts are reproducible there) and re-measure.

**Result when:** p95 first-request latency after scale-up is measured below a
target you set *before* the change, with the cost delta quantified. Judged by
numbers from `AppRequests`, never by eye.

### A2. Re-enable the GraphQL SchemaMappingInspector — status: open

**Why:** The inspector is Spring-for-GraphQL's boot-time check that every
schema field has a resolver and vice versa. With it disabled, wiring bugs
(e.g. the `@QueryMapping`-on-a-Mutation silent no-op class of bug — see
`techaid-failure-archaeology`) surface only at runtime or via hand-written
wiring tests.

**Current state (verified):** `spring.graphql.schema.inspection.enabled: false`
in BOTH `src/main/resources/application.yml` (~line 124) and
`src/test/resources/application.yml`, the latter carrying the TODO: the
inspector crashes after the Kotlin 2.x upgrade ("null method reflection
issue"; disabled in commit `42c1c5a`).

**Asset:** the wiring-test suite (`*WiringTest.kt`, `SchemaAssemblyTest.kt`)
pins current behaviour, so a fix branch has an immediate regression net.

**First three steps:**
1. On a branch, set `inspection.enabled: true` in the test profile and run
   `./gradlew test` — capture the exact stack trace.
2. Isolate the offending mapping (the trace names the type/method the
   inspector chokes on); write a minimal reproduction.
3. Fix that one mapping (or report upstream if it is a framework bug with the
   Kotlin metadata), then enable inspection in main config too.

**Result when:** the app boots with `inspection.enabled: true` in both
profiles and a deliberately mis-wired resolver on a scratch branch fails at
boot — proving the wiring-bug class is now machine-detected.

### A3. Retire `enable_lazy_load_no_trans` — status: open, parked tech debt

**Why:** `hibernate.enable_lazy_load_no_trans: true` silently opens temporary
sessions for lazy associations touched outside a transaction. It hides N+1
query storms and masks the design smell instead of fixing it. Flagged in the
2026-07 code review and deliberately parked.

**Current state (verified):** still `true` at `application.yml:101` (comment
in the file itself admits DTO projection / `JOIN FETCH` is the better fix).

**Asset:** the DB-backed test suite (zonky embedded Postgres) exercises the
GraphQL surface, so turning the flag off flushes out
`LazyInitializationException` sites cheaply.

**First three steps:**
1. On a branch, set the flag to `false`; run `./gradlew test`; catalogue every
   `LazyInitializationException` with its resolver path.
2. Fix each site with a fetch join or DTO projection (not by re-enabling).
3. Compare warm p95 latency on UAT before/after (App Insights) — the fix
   should be neutral or better because hidden extra sessions disappear.

**Result when:** flag is `false`, suite green, and UAT p95 unchanged or
improved (measured, not asserted).

### A4. Close the DEVICE_REQUEST_LIMIT bypass via contact minting — status: open, accepted-risk (2026-07-02)

**Why:** the public request flow rate-limits per *contact*, not per person/IP,
so the limit does not actually bind for an adversarial caller.

**Current state (verified, precise mechanism):**
- `DeviceRequestMutations.createDeviceRequest` is intentionally public (no
  `@PreAuthorize`) and rejects when
  `referringOrganisationContact.requestCount >= DEVICE_REQUEST_LIMIT` (= 3,
  `DeviceRequestMutations.kt:100` and `:221`).
- `requestCount` is a Hibernate `@Formula` subquery counting that contact's
  requests whose status is NOT IN
  (`REQUEST_CANCELLED`,`REQUEST_COMPLETED`,`REQUEST_DECLINED`)
  (`OrganisationModels.kt` ~line 112).
- `createReferringOrganisationContact` is ALSO intentionally public
  (`ReferringOrganisationContactMutations.kt:29`), deduping only on
  (name, email, org). So an anonymous caller mints a fresh contact (vary the
  email) and gets 3 more requests. The limit is per-contact theatre.

**Asset:** `PublicSurfaceAuthorizationTest` already models the anonymous
public surface — the natural home for a red test proving the bypass.

**First three steps:**
1. Write the red test: anonymously create contact A, exhaust the limit,
   mint contact B under the same org, show a 4th request succeeds.
2. Decide the binding dimension with the maintainer (per-organisation
   count, per-IP throttle, or both) — this is a product decision, gate it
   per `techaid-change-control`.
3. Implement the chosen guard; the red test flips green.

**Result when:** a red/green test proves the limit binds across freshly
minted contacts, and the public request flow still works for legitimate orgs
(existing public-surface tests stay green).

### A5. Test-coverage gaps from the 2026-07 review — status: open (one of three already closed)

**Current state (verified):** the decline-scheduler timing gap is CLOSED —
`DeviceRequestServiceTest` now covers the 20-minute cutoff and the
nothing-to-do case. Still uncovered:
- **DEVICE_REQUEST_LIMIT enforcement** — no test asserts the 4th request for
  one contact is rejected (grep `DEVICE_REQUEST_LIMIT` in `src/test/` → no
  hits). (A4's red test subsumes part of this.)
- **REQUEST_COMPLETED kit-archival cascade** — when `updateDeviceRequest`
  moves status to `REQUEST_COMPLETED`, all non-completed kits on the request
  are marked `DISTRIBUTION_DELIVERED` and archived
  (`DeviceRequestMutations.kt:158-159`). No test pins this destructive-ish
  cascade.

**First three steps:** (1) red/green the limit check at exactly 3; (2)
red/green the completion cascade including the kits-already-completed
branch; (3) run the full suite per `techaid-validation-and-qa`.

**Result when:** both behaviours have failing-first tests merged into the
suite.

### A6. Repo hygiene: dead deploy-era artifacts and stale docs — status: open

**Why:** the repo still carries Dokku/Kubernetes-era files from before the
Azure Container Apps migration (completed May 2026). They mislead newcomers
about how the system deploys.

**Current state (verified via `git ls-files`, 2026-07-05):** tracked
leftovers: `Procfile` (Dokku), `charts/` (Helm), `manifests/` (K8s),
`Dockerfile.local`, `start.sh`. **Trap:** `Dockerfile` still does
`COPY ./Procfile /app` — deleting `Procfile` without editing `Dockerfile`
breaks the image build. `cutover-prod.sh` is already gone. The `README.md`
dev-environment section predates the migration (references a `setup_branch`
and container-shell workflow) and says nothing about the actual CI → UAT →
promote model. Also deferred: kotlin-logging is pinned at 3.0.5
(`build.gradle:176`); the 5.x migration (new group id + package across ~11
files) was deliberately deferred during the 2026-04 dependency wave.

**First three steps:** (1) remove `Procfile` + its `Dockerfile` COPY line,
`charts/`, `manifests/`, `Dockerfile.local`, `start.sh` in one PR and confirm
the Docker image still builds; (2) rewrite the README dev-setup section and
walk it verbatim on a clean machine (see `techaid-build-and-env`); (3)
optionally take the kotlin-logging 5.x migration as its own mechanical PR.

**Result when:** cleanup PR merged and a from-scratch README walkthrough
works verbatim. (Note `docker-compose.yml` is NOT a leftover — it is the
live local-dev path.)

### A7. URL-layer security lockdown — status: open/candidate, analysed 2026-05-19, never implemented

**Why:** `SecurityConfig.kt` ends its chain with
`anyRequest().permitAll()` — the URL layer enforces nothing; every gate in
the system is a per-resolver `@PreAuthorize`, and a *missing* annotation
means silently anonymous (this is the invariant documented in
`techaid-architecture-contract`, and the root of the 2026-07 P0 auth gaps).
A default-deny URL layer adds a second line of defence for everything that
is not GraphQL.

**Current state (verified 2026-07-05):** still `permitAll()`. The 2026-07
review's resolver-level gaps are FIXED (`synchronizeCollectionDataForDeviceRequest`,
`createReferringOrganisation` now require `write:organisations`; `location`
requires `isAuthenticated()` — all verified in source). The URL-layer change
itself was analysed in May 2026 but never implemented. Essentials of that
analysis (re-validated against current source):
- `/graphql` **must stay `permitAll`** — it is one URL fanning out to many
  operations, several intentionally public (the org request flow:
  `createDeviceRequest`, `createReferringOrganisationContact`, the
  `*Public` picker queries, `adminConfig`, `buildInfo`). Per-operation auth
  happens inside resolvers and must keep working for anonymous callers.
- The auth filters are pass-through when their header is absent
  (`TokenAuthenticationFilter` for `X-Auth-Admin-Secret`, Spring's bearer
  filter for JWT), so anonymous requests legitimately reach resolvers.
- The change is: allow-list `/graphql`, `/typeform/hook` (HMAC-checked),
  `/login`, `/error`, `/auth/user`, `/actuator/health`, `/actuator/info`,
  and static assets; then `anyRequest().authenticated()`.
- Concrete win: `/actuator/metrics` is anonymously readable on production
  today. `application.yml` exposes health,info,metrics; the staging profile
  narrows to health,info; the production profile file does NOT narrow it —
  and prod runs `SPRING_PROFILES_ACTIVE=production` (live-verified
  2026-07-06; re-check: `az containerapp show -n api-production -g tada-2026
  --query "properties.template.containers[0].env"`).
- Known unknown: the Angular deep-link forward in `AuthController`
  (`"/*/*/{path:[^\.]*}"`) may already be dead code because
  `UnknownPathFilter` 404s unknown prefixes before Spring Security runs —
  test this before choosing the static-asset matchers.

**First three steps:** (1) red test: anonymous GET `/actuator/metrics`
expecting 401 (fails today); (2) apply the allow-list +
`authenticated()` default in `SecurityConfig.kt`; (3) run
`PublicSurfaceAuthorizationTest` and the full suite — every anonymous public
flow must still pass — then verify on UAT (anonymous public form flow,
Typeform webhook, health probe).

**Result when:** the URL layer denies a non-allowlisted path (metrics test
green) while ALL existing anonymous public-surface tests still pass. This
changes runtime security behaviour: gate it per `techaid-change-control`
(dashboard audit + UAT soak before any promote).

### A8. Zero-touch deploy verification — status: open

**Why:** post-deploy verification today is a human running curl/KQL (the
checklists in `techaid-prod-promotion-campaign` and
`techaid-deploy-and-operate`). A bad image that boots-then-fails, or a
deploy that silently kept serving the old revision (a real failure mode —
crash-looping revisions wedge and traffic falls back), is only caught if a
person looks.

**Current state (verified):** `ci.yml`'s `deploy-testing` job runs
`az containerapp update` and ends — no smoke step, no buildInfo assertion.
`promote.yml` likewise. External monitoring exists but is coarse: as of
2026-07-03 an external business-hours watcher checks prod
`/actuator/health` (Mon–Fri, London hours) — it catches "down", not "wrong
version" or "degraded".

**Asset:** the API already exposes `buildInfo` (git commit) via GraphQL and
`/actuator/health` anonymously — everything a smoke job needs is queryable.

**First three steps:** (1) add a post-deploy step to `deploy-testing` that
polls UAT until healthy (allow ~40–90 s for cold start — a premature check
is a false alarm) and asserts `buildInfo.commit == $GITHUB_SHA`; (2) fail the
job loudly if the assertion times out; (3) once proven on UAT, propose the
same for `promote.yml` through change control (it touches the production
pipeline).

**Result when:** a deliberately bad UAT canary (e.g. deploy a wrong-tag
image on UAT, with the maintainer's agreement) is flagged by the machinery,
not by a human.

---

## Part B — Data & impact insight candidates

### B1. Impact analytics on device flow — status: open, no curated reporting layer exists

**Why:** the charity reports to funders on device throughput and request
fulfilment. Today there is no curated metric layer — numbers are assembled
by hand when asked.

**Current state / assets (verified):**
- Envers `_AUD` tables hold the full status history of every `Kit` and
  `DeviceRequest` (both `@Audited`; `ValidityAuditStrategy` with
  `revision_id`/`revision_type` columns per `application.yml`) — fulfilment
  timelines are reconstructable after the fact.
- `kits.status_updated_at` exists (migration `V25.12.04.1201`).
- Superset is already deployed for dashboards (its config/DBs are outside
  this repo — treat Superset-side specifics as out of scope here; this
  repo's job is to expose queryable, documented data).

**First three steps (in this repo):**
1. Document the audit-table query patterns as SQL in the repo (candidate
   home: a `docs/analytics.md` or the skill library): fulfilment lead time
   (request created → kits assigned → `REQUEST_COMPLETED`, from
   `device_requests_audit_trail` transitions) and device throughput by
   month/type (from `kit_audit_trail` / `status_updated_at`). (Both entities
   override the default `_AUD` suffix with `@AuditTable` — the full
   audit-table name list is in `techaid-database-operations`.)
2. Decide the exposure route with the maintainer: read-only SQL views the
   Superset connection can query, vs GraphQL aggregate queries. Views are
   cheaper and keep the API surface unchanged; a view needs a Flyway
   migration → `techaid-database-operations`.
3. Prototype ONE KPI end-to-end (recommend: median days request→fulfilment)
   and validate the number against a handful of manually-traced requests.

**Result when:** one funder-grade metric is reproducible from a documented,
committed query and visible in Superset, and a spot-check of ≥5 individual
requests matches the metric's story.

### B2. Request-pipeline health: the 20-minute auto-decline rate — status: open, unmeasured

**Why:** the public request flow is two-stage: `createDeviceRequest` stores
the request with a random `correlationId`, and completing the follow-up
Typeform (equalities data) clears it via `markRequestStepsCompleted`. A
scheduler (`DeclineIncompleteDeviceRequests`, every 20 min) auto-declines
any request whose `correlationId` is still set after 20 minutes
(`DeviceRequestService.kt:37`). Nobody knows how many *legitimate*
applicants get auto-declined by that window — if the rate is material, the
charity is silently turning away the people it exists to serve.

**Asset:** every decline is a status transition to `REQUEST_DECLINED`
recorded in the `DeviceRequest` audit history — the rate is measurable from
existing data, no instrumentation needed.

**First three steps:** (1) write the audit query: of all requests created in
the last N months, what fraction went `NEW → REQUEST_DECLINED` via the
scheduler path vs completed the Typeform; (2) inspect a sample of declined
rows — are they spam/abandoned or plausibly real (org, details filled)?;
(3) bring the number to the maintainer for a threshold decision: keep the
20-minute window, lengthen it, or add a reminder email before declining.

**Result when:** the decline rate is a known number and the window decision
(keep/change) is made on that number, recorded with its rationale. Any
window change is behaviour-changing → red/green test on the new cutoff +
`techaid-change-control`.

---

## Promoting a candidate to actual work

No item on this list self-authorises. The route is always:

1. **Check it is still open** — run the re-verification commands below; retire
   solved items from this file.
2. **Measure first** where the item says so — numbers per
   `techaid-diagnostics-and-observability`, hypothesis discipline per
   `techaid-investigation-methodology`.
3. **Gate it** — classification, approval, and the non-negotiables live in
   `techaid-change-control` (PRs target `dev`; never promote to master
   without explicit maintainer instruction; behaviour-visible changes need a
   dashboard audit).
4. **Red/green it** — every behaviour change ships failing-first tests per
   `techaid-validation-and-qa`.
5. **Verify on UAT with numbers**, then leave the promote decision to the
   maintainer (`techaid-prod-promotion-campaign`).

When an item is finished or rejected, update THIS file (status → done/retired
with a one-line pointer to the PR) and add the story to
`techaid-failure-archaeology` if it produced a lesson.

## Provenance and maintenance

Authored 2026-07-05 from direct repo verification (paths/line numbers checked
against `dev` at that date) plus date-stamped operational facts (cold-start
~40–90 s and the external health watcher: as of 2026-07-03; £70/mo cost
baseline: as of 2026-06; URL-lockdown analysis: 2026-05-19, re-validated
2026-07-05; prod profile: live-verified 2026-07-06). Line numbers drift —
re-verify before acting:

- A1 cold start still untuned: `grep -n "CDS\|aot" build.gradle Dockerfile` (no hits = still open); `grep -n "lazy-initialization" src/main/resources/application.yml`
- A2 inspector still disabled: `grep -rn "inspection" src/main/resources/application.yml src/test/resources/application.yml`
- A3 lazy-load flag still on: `grep -n "enable_lazy_load_no_trans" src/main/resources/application.yml`
- A4 bypass still open: `grep -rn "DEVICE_REQUEST_LIMIT" src/test/kotlin/` (no hits = untested); confirm `createReferringOrganisationContact` still has no `@PreAuthorize`: `grep -n -B3 "fun createReferringOrganisationContact" src/main/kotlin/cta/app/graphql/mutations/ReferringOrganisationContactMutations.kt`
- A5 cascade still untested: `grep -rln "REQUEST_COMPLETED" src/test/kotlin/` (no hits = still open)
- A6 leftovers still present: `git ls-files Procfile charts manifests Dockerfile.local start.sh`; Procfile still COPY'd: `grep -n Procfile Dockerfile`; logging still 3.x: `grep -n "kotlin-logging" build.gradle`
- A7 URL layer still permitAll: `grep -n "permitAll\|authenticated()" src/main/kotlin/cta/app/config/SecurityConfig.kt`
- A8 still no smoke step: `grep -n "buildInfo\|health" .github/workflows/ci.yml .github/workflows/promote.yml` (no hits = still open)
- B1 status_updated_at + audit config: `ls src/main/resources/db/migration | grep status_updated`; `grep -n "audit_table_suffix" src/main/resources/application.yml`
- B2 window still 20 min: `grep -n "minus(20" src/main/kotlin/cta/app/services/DeviceRequestService.kt`
