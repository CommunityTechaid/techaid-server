---
name: techaid-validation-and-qa
description: Load BEFORE writing or changing any techaid-server code or test, when deciding what evidence a change needs to count as done, when a test fails and you need to know what it guards, or when adding a new test and picking a pattern to copy. Covers the red/green TDD rule, the zonky embedded-Postgres test infrastructure, the certified test inventory (what each test pins and when you must extend it), the acceptance ladder from green CI to production, and current coverage posture.
---

# TechAid Validation & QA

What counts as evidence in this repo, the test discipline every change must follow, and the certified inventory of existing tests — what each one guards and when you are required to extend it.

**When NOT to use this skill:** deploying or verifying UAT/production (→ `techaid-deploy-and-operate`, `techaid-prod-promotion-campaign`); diagnosing a live outage (→ `techaid-debugging-playbook`); writing Flyway migrations themselves (→ `techaid-database-operations`); understanding why a past fix was rejected (→ `techaid-failure-archaeology`).

---

## 1. The evidence bar: red/green TDD (non-negotiable)

Established as a hard norm 2026-07-02. **Every backend change ships with a failing-first test:**

1. Write the test that reproduces the bug or specifies the new behavior.
2. Run it. **Watch it fail** for the right reason (not a compile error).
3. Make it pass with the minimal change.
4. Keep the test in the suite permanently.

A change without a failing-first test is **not done** — do not open a PR for it. Precedent: every fix in the 2026-07 code-review batches (PRs #48 and #49) shipped this way, e.g. `PublicSurfaceAuthorizationTest` went red against the un-gated mutations before the `@PreAuthorize` annotations landed, and `SchemaValidationTest` went red before the Flyway baseline migration existed.

Why this bar exists here specifically: the security model (`permitAll()` + per-method `@PreAuthorize`) and the disabled GraphQL schema inspection mean **whole classes of defects are silent** — a missing annotation is silently anonymous, a mis-wired resolver silently returns null. Only a pinning test makes these failures loud. See `techaid-architecture-contract` for the model itself.

---

## 2. Test infrastructure: embedded Postgres, no Docker

*Jargon: "zonky" = the [io.zonky.test](https://github.com/zonkyio/embedded-database-spring-test) libraries that run a real Postgres from downloaded binaries inside the JVM test run.*

- Dependencies (verify in `build.gradle` ~lines 216–220): `io.zonky.test:embedded-database-spring-test:2.5.1` + `io.zonky.test:embedded-postgres:2.1.0`.
- `src/test/resources/application.yml` sets `zonky.test.database.provider: zonky` — the **binary** provider, not the default Docker provider. **Tests need no Docker daemon**, locally or in CI.
- Any test annotated `@AutoConfigureEmbeddedDatabase(type = ...POSTGRES)` gets a fresh real Postgres; **Flyway runs all migrations against it on context start**. Every suite run is therefore also the first-line validation of `src/main/resources/db/migration/`.
- Test-config tricks in `src/test/resources/application.yml` (read it before changing it):
  - `google.places.url: http://127.0.0.1:1/geocode` — a closed port, so geocoding calls fail fast instead of hitting Google.
  - Dummy Auth0 issuer/token-attribute `https://test.example.com` — which is why Spring-context tests must `@MockBean` the `JwtDecoder` (a real decoder would try to fetch OIDC metadata from that fake issuer).
  - `gmail.enabled: false` — no real email sends.
  - `spring.graphql.schema.inspection.enabled: false` — inspection crashes after the Kotlin 2.x upgrade (open TODO in that file). Consequence: **schema↔resolver mismatches do not surface at startup**; that is what the wiring tests in §3 exist for.

---

## 3. The certified inventory

Every test file under `src/test/kotlin`, what it pins, and when you MUST extend it. Do not delete or weaken any of these without change control (`techaid-change-control`).

| Test | What it guards | Extend when… |
|---|---|---|
| `cta/app/graphql/mutations/PublicSurfaceAuthorizationTest` | **THE auth gate.** Pins which /graphql operations reject anonymous callers ("Access Denied", null data) and which scopes are admitted (`synchronizeCollectionDataForDeviceRequest` and `createReferringOrganisation` need `write:organisations`; `location` needs any authenticated user; `referringOrganisationsPublic` stays anonymous but only with the restricted name+archived filter). Incident: the 2026-07 P0 anonymous-access gaps (PR #48). | **Any resolver is added, or any `@PreAuthorize` is added/changed/removed.** No exceptions. |
| `cta/graphql/SchemaAssemblyTest` | Assembles the real schema (all `*.graphqls` + custom scalar wiring) with no Spring context. Asserts `LenientString` exists and `CreateKitInput.lotId` uses it. Guards the exact `graphql.AssertException` assembly failure that sank PR #42 (redefining built-in `String`) and only surfaced in CI. | Adding/changing any scalar or SDL type. |
| `cta/db/SchemaValidationTest` | Boots Hibernate in `validate` mode against a Flyway-only embedded DB: proves migrations alone satisfy every JPA mapping. Added with the baseline migration (PR #49). The fix for a red run is **a new migration, never `ddl-auto=update`**. | Any entity or migration change (it must pass; it usually needs no editing). |
| `cta/db/IndexMigrationTest` | Builds a minimal production-shaped fixture for tables Flyway doesn't manage, then executes `V26.07.02.1000__add_indexes.sql` **twice**, asserting all 13 expected indexes exist — proves the column-existence guards and `IF NOT EXISTS` make it idempotent. | Adding indexes or editing that migration's guard pattern. |
| `cta/app/config/LenientStringScalarTest` | Unit-tests the `LenientString` coercing: stringifies Int/Boolean (the bulk-import numeric `lotId` failure), passes Strings, still rejects lists/objects. | Changing coercion behavior. |
| `cta/graphql/GraphQlErrorLoggingTest` | A variable-coercion error (HTTP 200 + `errors[]` body) is WARN-logged with operation name and the offending input variables — the logging that pinned the bulk-import bug. Note the content-type subtlety in its comments: `Accept: application/json` ⇒ 200; `application/graphql-response+json` ⇒ 400. | Touching `GraphQlTelemetryConfig` error logging. |
| `cta/graphql/GraphQlEndpointTest` | /graphql answers a `__typename` probe; a numeric `lotId` is coerced by `LenientString` end-to-end, not rejected (probe safely omits required `model` so nothing is created). | Endpoint-level GraphQL behavior changes. |
| `cta/app/graphql/queries/UserRoleNestedResolverWiringTest` | Reflection-asserts `User.roles/permissions`, `Role.users/permissions` resolvers carry `@SchemaMapping` at the right coordinates. Guards the silent-null bug class: with inspection disabled, an unwired field resolves to null with **no error** (broken dashboard detail tabs). | Adding any nested field resolver on Auth0-backed types. |
| `cta/app/graphql/queries/UserMutationsWiringTest` | The user-admin mutations (`assignRoles`, `removeRoles`, `deleteUser`, `removePermissions`) carry `@MutationMapping`. Guards the `@QueryMapping`-under-Mutation silent no-op (mutation "succeeds", no Auth0 change happens). | Adding/moving any mutation handler. |
| `cta/app/graphql/queries/ReferringOrganisationContactNotesResolverWiringTest` | `ReferringOrganisationContact.notes` bridges its entity/GraphQL name mismatch with `@SchemaMapping` — same silent-null class as above. | Renaming entity collections vs GraphQL fields. |
| `cta/app/graphql/mutations/DeviceRequestMutationsTest` | `UpdateDeviceRequestInput.apply()` is full-replace: explicit null **clears** `collectionDate` (operators couldn't remove bookings — techaid-dashboard #42). | Changing update-input semantics. |
| `cta/app/services/DeviceRequestServiceTest` | `formatDeviceRequests` item rendering; `markRequestStepsCompleted` status+correlationId; decline scheduler declines only >20-min-stale requests and **saves only mutated rows** (audit-churn guard from PR #48). | Touching `DeviceRequestService`. |
| `cta/app/services/LocationServiceTimeoutTest` | A stalled geocoding endpoint (socket accepts, never responds) degrades to null instead of hanging a Tomcat thread — verifies the 2s connect / 5s read timeouts (PR #48; thread-pool exhaustion risk on the small container). | Touching `LocationService` or its RestTemplate. |
| `cta/auth/AuthServiceTest` | Admin-secret matching: grants full authorities on match; rejects wrong/different-length/blank tokens and a blank configured secret (constant-time compare fix, PR #48). | Touching `AuthService`. |
| `cta/auth/TokenAuthenticationFilterTest` | Invalid admin header ⇒ 401 `INVALID_ADMIN_TOKEN`, chain halted; valid ⇒ authenticated + chain continues; absent ⇒ anonymous pass-through. | Touching `TokenAuthenticationFilter`. |
| `cta/app/KitHashCodeTest` | `Kit.hashCode()` varies by id (equals is id-based) — guards the constant-13 hashCode that made `MutableSet<Kit>` lookups O(n) (PR #48). | Touching Kit equality/hashing. |
| `cta/app/config/ActuatorHealthDetailsTest` | Anonymous `/actuator/health` returns overall status only, **no `components`** — details leak DB/infra state to unauthenticated probes (PR #48). | Touching actuator/health config. |
| `cta/controllers/CustomErrorModelTest` | `CustomErrorModel.from()` maps standard error attributes and tolerates an absent `error` key (error page must not itself throw). | Touching error rendering. |
| `ju/ma/ApplicationTest` (declares `package cta`) | Bare context-loads smoke test against embedded DB. Path is a legacy quirk — **don't copy its location** for new tests; put them under `cta/...` matching the package. | Rarely — it's the canary. |

---

## 4. Running the tests

```bash
./gradlew test                                          # full suite
./gradlew test --tests "cta.auth.AuthServiceTest"       # one class
./gradlew ktlintCheck test                              # what CI actually gates on
```

- CI (`.github/workflows/ci.yml`) runs `ktlintCheck` then `test` on every push to `dev`, `master`, and `maintenance/**`; the build/deploy jobs only run if tests pass. Test reports upload as the `test-reports` artifact from `build/reports/tests/`.
- Locally, HTML results land in `build/reports/tests/test/index.html`.
- Calibration point (full suite on a Windows dev machine, 2026-07-04): **48 tests across 19 classes, 0 failures/0 skipped, `BUILD SUCCESSFUL in 1m 57s`** (warm Gradle daemon; first-ever run also downloads the embedded Postgres binaries and takes longer). Re-run and compare if the suite feels broken or slow.
- Format violations: `./gradlew ktlintFormat` fixes most of them.

---

## 5. Adding a test — pick the right pattern

Match the cheapest pattern that can fail for the right reason. Exemplars are the cleanest current instance of each; copy their shape.

**A. Pure unit (no Spring, no DB) — milliseconds.** Exemplar: `cta/auth/AuthServiceTest` (uses `ReflectionTestUtils.setField` to inject `@Value` fields). For annotation/wiring pins, exemplar: `UserMutationsWiringTest` (pure reflection).
- [ ] No Spring annotations at all; construct the class directly, mock collaborators with Mockito.

**B. Full-context GraphQL/MockMvc (Spring + embedded DB) — seconds.** Exemplar: `PublicSurfaceAuthorizationTest`.
- [ ] `@SpringBootTest(webEnvironment = MOCK)` + `@AutoConfigureMockMvc` + `@AutoConfigureEmbeddedDatabase(type = ...POSTGRES)`.
- [ ] `@MockBean lateinit var jwtDecoder: JwtDecoder` — required or the context tries to fetch OIDC metadata from the dummy test issuer.
- [ ] Authenticated calls: `.with(jwt().authorities(SimpleGrantedAuthority("write:organisations")))`.
- [ ] GraphQL queries are embedded in a JSON string — escape inner quotes as `\"` (see `createOrgMutation` in the exemplar).
- [ ] Remember: GraphQL errors arrive as HTTP **200** with an `errors[]` array — assert on the body, not the status.

**C. SQL/migration-level (embedded DB, JdbcTemplate) — seconds.** Exemplar: `IndexMigrationTest` (builds its own fixture tables for the pre-Flyway schema, loads the migration file from the classpath, runs it twice to prove idempotence). Schema-drift pins: `SchemaValidationTest`.

Checklist for every new test regardless of pattern:
- [ ] It failed first, for the stated reason — paste the red output into the PR description.
- [ ] File lives under `src/test/kotlin/cta/...` matching its package.
- [ ] KDoc comment on the class explaining **what incident or invariant it pins** — every existing test does this; it is house style (`techaid-change-control`).
- [ ] If it touches authorization: extend `PublicSurfaceAuthorizationTest`, don't fork a parallel auth test.
- [ ] `./gradlew ktlintCheck` passes.

---

## 6. The acceptance ladder — evidence beyond unit tests

Green local tests are the floor, not the ceiling. Full ladder for a change to reach production:

1. **Green `./gradlew ktlintCheck test` locally** (red first, then green — §1).
2. **Green CI on the PR branch / `dev`** — same commands, plus the image build.
3. **UAT deploy verification** (auto-deploy on push to `dev` → `api-testing`): confirm `/actuator/health` is `UP`, confirm the deployed commit via the buildInfo endpoint matches your SHA, then send **targeted GraphQL probes for the specific change** — the 2026-07-02 pattern: anonymous calls to newly-gated mutations must return "Access Denied"; still-public surfaces (the org typeahead) must keep working anonymously. Commands and gotchas (scale-to-zero ~40–90s wake-up, wedged revisions) live in `techaid-deploy-and-operate`.
4. **Dashboard Playwright e2e** (lives in the `techaid-dashboard` repo, run locally against UAT) — required when the change touches anything the dashboard consumes. Known gotcha (as of 2026-07-03): a stale saved auth state makes org-flow specs fail with an Auth0 login redirect on an unmodified branch — re-run the suite's auth setup first, then re-judge.
5. **Production promotion** — never ad-hoc; follow `techaid-prod-promotion-campaign` end to end (change control applies: promoting requires explicit approval, see `techaid-change-control`).

---

## 7. Coverage posture (honest, as of 2026-07-04)

- JaCoCo report runs automatically after `test` (`finalizedBy jacocoTestReport` in `build.gradle`); XML/CSV disabled, HTML at `build/reports/jacoco/test/html/index.html`. Excludes legacy `com/alphasights/**` patterns.
- `jacocoTestCoverageVerification` exists with a 0.5 minimum **but is not wired into `check`** — nothing enforces it in CI. Run it manually if you want the number: `./gradlew jacocoTestCoverageVerification`. Treat coverage as a diagnostic, not a gate; the real gate is §1.

## 8. Known gaps — do not oversell

Open, untested behavior identified in the 2026-07 code review and still open as of 2026-07-03 (roadmap home: `techaid-roadmap-and-frontier`):

- `DEVICE_REQUEST_LIMIT` enforcement (including the accepted-risk bypass via public contact creation).
- The `REQUEST_COMPLETED` → kit-archival cascade.
- Decline-scheduler timing edge cases beyond the two unit tests in `DeviceRequestServiceTest`.

If your change touches one of these areas, closing the test gap is part of your change.

---

## Provenance and maintenance

Authored 2026-07-03/04 from direct reads of all 19 test files, `build.gradle`, `src/test/resources/application.yml`, and `.github/workflows/ci.yml`. Full-suite calibration run 2026-07-04 (Windows, no Docker): 48 tests / 19 classes, 0 failures, 0 skipped, 1m 57s.

Re-verify before trusting volatile facts:

```bash
find src/test -name "*.kt" | sort                 # inventory drift (19 files as of 2026-07-04)
grep -n "zonky" build.gradle src/test/resources/application.yml   # embedded-DB provider + versions
grep -n "branches" .github/workflows/ci.yml       # CI trigger branches
grep -n "jacoco" build.gradle                      # coverage wiring (report yes, verification unwired)
./gradlew test                                     # the ground truth
```
