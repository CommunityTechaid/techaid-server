# Carry-on: issue #209 dependency currency → Spring Boot 4.1.x

Written 2026-09-21 to resume cold. **Delete this file when the work lands.**
Companion: `MAINTENANCE_PLAN.md` → "Tier 4 — Dependency currency, 2026-09" (the checklist).

## Where things are

| Thing | State |
|---|---|
| Branch | `chore/dependency-currency-209`, pushed, 6 commits, tree clean |
| Forked from | `dev` @ `1726099` (unmoved) |
| PR | **none opened yet** — a *draft* PR against `dev` is the end state |
| `master` / production / UAT | untouched. Prod is server 3.3.0 / `c605bf0` |
| Feature flags | untouched |
| Dependabot PRs | #212–#221 all still open; none merged or closed |
| release-please PR #205 | untouched |
| Suite | `314 passed / 0 failed / 2 skipped`, green at every commit |

**Hard constraint from Tony: nothing merges to `dev` yet.** A `dev` merge auto-deploys UAT and he
wants to soak this later in the week.

## The target

**Spring Boot 4.1.x. The 3.5 line is deliberately skipped** — a 3.5.16 stop would move
spring-graphql 1.3.4 to 1.4.6 and graphql-java 22.3 to 24.3, and 4.1 moves both again, so it pays
for this repo's riskiest migration twice. Dependabot **#216 is to be closed, not merged.**
CVE-2026-40973 does not force a move: its precondition is inactive
(`server.servlet.session.persistent` unset everywhere, defaults false).

**Java stays at 17** — Boot 4.1 supports 17–26. No JDK move anywhere in the plan.

## Done (all pushed)

| SHA | What |
|---|---|
| `3a37ffa` | `.github/dependabot.yml`: `semver-major` ignores added to the **docker** and **github-actions** blocks |
| `052f570` | `MAINTENANCE_PLAN.md` Tier 4 plan |
| `bf8c454` | App Insights agent `3.7.8` → `3.7.10` (Dockerfile ARG) |
| `9aa73bc` | ktlint gradle plugin `12.1.1` → `14.2.0` (engine pin stays 1.5.0) |
| `1a1ae6f` | plan retargeted to 4.1.x |
| `6cad5cf` | Postgres driver `42.7.13` — buildscript pin **and** `ext['postgresql.version']` |

## Next steps

### Phase A — front-loadable onto Boot 3.4.4, one green commit each

1. **zonky** `embedded-postgres` 2.1.0 → **2.2.2**, `embedded-database-spring-test` 2.5.1 →
   **2.8.0** (**never 2.7.0** — shaded-Guava `ClassNotFoundException`, upstream #310),
   h2 2.2.224 → 2.5.250. Folds Dependabot **#218**.
2. **`@MockBean` → `@MockitoBean`** (`org.springframework.test.context.bean.override.mockito`).
   **45 files, 48 sites, 45 imports. Zero `@SpyBean` in the repo** — no `@MockitoSpyBean` work.
   Mandatory: the annotation is **removed** from `spring-boot-test` 4.1.1, not deprecated.
3. **Gradle wrapper** 8.12.1 → **>= 8.14** (the 4.1 floor for the 8.x line).
   **This makes Dependabot #217 required, not optional**: `Dockerfile:7` builds the shipped jar
   with **bare `gradle`** from `gradle:8.12.1-jdk17`, so the image Gradle must move too.
4. **Kotlin** 2.1.20 → **>= 2.2** (the 4.1 floor; BOM picks 2.3.21). **Independent of QueryDSL.**
   Verify `./gradlew kaptKotlin` still generates Q-classes.
5. **Rewrite `DumpSchema.kt`** off `org.hibernate.tool.hbm2ddl.SchemaExport` (used at lines
   10/43/68; the class is gone in `hibernate-ant` 7.4.5) and drop the `hibernate-tools-orm` /
   `hibernate-tools-utils` pins — **no 7.4.x release exists** (404; publishing stops at
   7.3.13.Final). Check first whether the command is used at all; deleting may be cheaper.
6. **Delete `com.github.alexliesenfeld:querydsl-jpa-postgres-json:0.0.7`** — proven dead code,
   see issue #222 below. Two imports plus two dead method bodies.
7. `io.github.microutils:kotlin-logging-jvm:3.0.5` may not survive Kotlin 2.2 (Kotlin 1.x
   metadata; the coordinate has moved to `io.github.oshai`). Contingent on step 4.

### Phase B — the Boot bump

`springBootVersion` + plugin to 4.1.x; replace the `flyway-core` / `flyway-database-postgresql`
pins with **`spring-boot-starter-flyway`**; `hypersistence-utils-hibernate-63` → **`-73`**
(3.16.0; there is no `-74`); QueryDSL 5.0.0 → **5.1.0** or drop the version and let the BOM
manage it; **keep `ext['postgresql.version'] = '42.7.13'`** (the 4.x BOM is also inside the
CVE-2026-54291 range); then Jakarta EE 11 / Servlet 6.1 / Spring Framework 7 / Spring Security 7
fallout, and the spring-graphql jump.

### Phase C — after it runs

Envers `NOT_AUDITED` re-verification (below); the #222 decision; DGS codegen plugin 6.0.3
against Gradle 9 / Kotlin 2.3 — the plugin is **vestigial** here, deleting may beat upgrading;
`bootRun` + `/actuator/health` locally.

## What #209 gets wrong — do not re-plan from the issue alone

Verified against the Boot 4.1.1 BOM (`kotlin.version 2.3.21`, `querydsl.version 5.1.0`,
`hibernate.version 7.4.5.Final`, `flyway.version 12.4.0`, `jakarta-persistence.version 3.2.0`,
`spring-framework.version 7.0.9`):

- **"QueryDSL is the real blocker / 5.0.0 is its last release / must move to the
  `io.github.openfeign` fork with kapt to KSP."** False. **`com.querydsl` 5.1.0 exists**, the
  Boot 4.1.1 BOM manages it, and Spring Data JPA 4.1.1 depends on
  `com.querydsl:querydsl-jpa:5.1.0:jakarta` — the same coordinate shape already in use, and the
  jakarta-classifier jar is on Central. It is a **version bump, not a re-platform**. The fork is
  optional and deferrable. Biggest simplification to the programme.
- **"kapt is in maintenance mode and falls back to Kotlin 1.9, so do QueryDSL first."** False.
  K2 kapt has been default since **2.1.20** (already the pin here), and JetBrains removed the
  maintenance-mode notice in March 2026. **kapt does not gate Kotlin; QueryDSL does not gate
  Kotlin.**
- **"Boot 4 *may* remove `@MockBean`."** It **has**.
- **Never mentioned, and hard:** the Gradle 8.14 floor; the hibernate-tools 7.4.x gap;
  the `SchemaExport` removal; the `spring-boot-starter-flyway` requirement; the Envers change.

## Two live risks

- **Flyway: step 6 of #209 (to 11.20.3) is probably wasted work.** The Boot 4.1 BOM manages
  **12.4.0** via the starter, so pinning 11 pays for a Flyway major twice on the component that
  owns the schema. Options: skip it and let the Boot bump do 10 → 12; or go to 12.x now on Boot
  3.4.4 to isolate the Flyway risk from the Boot risk (**preferred**); or keep 11 as a waypoint.
  **Decide by checking whether the Boot 3.4.4 `FlywayAutoConfiguration` tolerates Flyway 12.**
  Also note: in Boot 4, missing the starter means **migrations silently stop auto-running**.
- **Envers `NOT_AUDITED` changed semantics at Hibernate 7.3** — previously ignored, now
  respected, so those associations read **current** state instead of historic audit state.
  **9 sites**: `KitModels.kt` x4 (37, 71, 75, 163), `DonorModels.kt` x2 (28, 91),
  `OrganisationModels.kt` x2 (28, 91), `DeviceRequestModels.kt` x1 (99). Behavioural — it will
  not fail a compile. Re-baseline `KitAuditNoOpRevisionTest`, `EnversSchemaContractTest`,
  `KitAuditTrailStatusConstraintTest` and the audit-trail queries against real data.

## Research gap — close before Phase B

**Nothing has been researched about Spring Framework 7 / Spring Security 7 breaking changes**
against the `SecurityFilterChain` DSL here, the per-method `@PreAuthorize` model and
`TokenAuthenticationFilter`, **or about spring-graphql 1.3 → 1.5 / graphql-java 22 → 25** against
the hand-written SDL, the `LenientString` scalar and the depth limit in `GraphQlConfig.kt`. Those
are the two likeliest sources of surprise.

## Issue #222 — a live regression found on the way

**Kit free-text search has silently not matched `attributes.notes` since 2024-11-12** (~22
months). `KitFilters.kt:332-333` has `attributes?.let { null }` where every sibling reads
`x?.let { builder.and(...) }`, so the input is accepted and dropped. Introduced by `6c2ced4`
(Akhil) to bypass a Hibernate 6 breakage rather than fix it. The **dashboard still sends the
filter** (`kit-index.component.ts:42`, the main kit-list search box), in an `OR` with `model` /
`serialNo` / `id` — so results come back HTTP 200, just missing notes matches. No test covers the
path, which is why it survived. Restoring it needs a `FunctionContributor` or a native jsonb
predicate — a product decision, not an upgrade blocker.

## Dependabot verdicts

- **Fold in:** #218 (Phase A1), #219 font-awesome, #220 auth0 1.12.1, #221 jakarta.mail 2.0.2.
- **Close, do not merge:** #216 (3.4.4 → 3.5.16).
- **Now required:** #217 (gradle build image) — pairs with the wrapper bump, see Phase A3.
- **Hold:** #215 (temurin 17 → 25) — elective; 4.1 supports Java 17–26, and the AI agent has an
  open Java 25 native-access issue (ApplicationInsights-Java#4851).
- **Undecided:** #212/#213/#214 action majors. The inputs this repo passes are unaffected.
  Recommendation: a separate small PR, not folded here. Only `upload-artifact` is exercised by
  PR CI — the `build` job is guarded to push events, so the other two first run on a UAT deploy.

## Working rules that bit this session

- **Never pipe `gradlew`** — it wedges under the rtk hook. Redirect to a log file, then read the
  log; background it for the full suite.
- **Run the FULL suite before each commit.** `./gradlew ktlintFormat` first; CI gates on
  `ktlintCheck`.
- The thrifty hook caps inline searches per prompt; bypass one command with
  `THRIFTY_ALLOW_INLINE=1 <cmd>`.
- **Do not chain two heredocs in one Bash call, and avoid apostrophes in heredoc bodies** — the
  wrapper re-quotes and the parse fails. Write the file with the Write tool instead. This bit me
  twice writing this very file.
- **`grep -i h2` matches `oauth2`.** Cost a wrong first read of where h2 is used.
- Test config is **`src/test/resources/application-test.yml`**, not `application.yml` (the #105
  shadowing fix). `techaid-validation-and-qa` and `techaid-build-and-env` are stale on this, and
  on the test-file count (they say 19; it is 68).
- **Verify the resolved artifact, not the pin.** The whole point of `6cad5cf`.

## Dead weight noticed, deliberately not removed (CLAUDE.md section 3)

`com.h2database:h2` — nothing in `src/main` or `src/test` references it.
`org.webjars:font-awesome` — unreferenced. The `com.netflix.dgs.codegen` plugin and
`graphQLVersion` — vestigial. The `MAINTENANCE_PLAN.md` header still says "PR to `master`",
contradicting CLAUDE.md section 5.
