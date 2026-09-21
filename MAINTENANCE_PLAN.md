# Techaid-Server Maintenance Plan

Living checklist for working through dependency updates, security hardening, and tech debt. Work through tiers in order. Mark items `[x]` as completed. Run `./gradlew clean test` after each group before moving on.

Branch strategy: work on `dev`, PR to `master` when each tier or group is done.

**Current branch:** `maintenance/dependency-updates-2026` — tests green as of 2026-04-15.

---

## Tier 1 — Dependency / Framework Updates

### Group A — Core Runtime

- [x] Spring Boot plugin: `3.2.3` → `3.4.4`
- [x] Spring Boot version: `3.2.2` → `3.4.4`
- [x] Kotlin: `1.9.22` → `2.1.20`
- [x] Gradle wrapper: `8.6` → `8.12.1`
- [x] `io.spring.dependency-management` plugin: `1.1.4` → `1.1.7`

**Verify:** `./gradlew clean test` passes; `./gradlew bootRun` starts cleanly

---

### Group B — Database (Critical)

- [x] **CRITICAL** PostgreSQL JDBC driver: `42.2.10` → `42.7.10` (buildscript classpath in build.gradle)
- [x] Flyway: `9.22.3` → `10.22.0`; added `flyway-database-postgresql:10.22.0` (required separate module in Flyway 10+)
- [x] `hibernate-types-52:2.9.8` → `io.hypersistence:hypersistence-utils-hibernate-63:3.15.2`; updated imports in `models.kt` (`com.vladmihalcea.hibernate.type.json.*` → `io.hypersistence.utils.hibernate.type.json.*`)
- [x] H2: `1.4.200` → `2.2.224` for tests

**Verify:** `./gradlew clean test`; Flyway migrations run cleanly on a fresh DB

---

### Group C — Auth0

- [x] `com.auth0:auth0:1.15.0` → `2.27.0`
- [x] `com.auth0:java-jwt:3.10.3` → `4.5.1`
- [x] `com.auth0:mvc-auth-commons:1.+` → `1.11.1` (pinned explicit version)
- [x] `thymeleaf-extras-springsecurity5:3.0.4.RELEASE` → `thymeleaf-extras-springsecurity6:3.1.3.RELEASE`
  - Spring Boot 3.x uses Spring Security 6; the `-springsecurity5` artifact is wrong

**Verify:** Login flow works end-to-end; JWT validation still passes

---

### Group D — Google APIs

- [x] `google-api-client:1.35.0` → `2.9.0` (also aligned `google-api-client-gson` 2.3.0 → 2.9.0)
- [x] `google-api-services-gmail:v1-rev20211108-1.32.1` → `v1-rev20260112-2.0.0`
- [x] `google-auth-library-oauth2-http:1.23.0` → `1.43.0`

**Verify:** Gmail sending still works (test with a real email send in staging)

---

### Group E — Logging & Utilities

- [x] `kotlin-logging:1.6.26` → `3.0.5` (`io.github.microutils:kotlin-logging-jvm`; 3.x keeps `mu` package — no import changes needed; 5.x+ would require `io.github.oshai` group + `io.github.oshai.kotlinlogging` package across 11 files — deferred)
- [x] `logstash-logback-encoder:6.3` → `7.4` (required for logback 1.5.x compatibility with Spring Boot 3.4+)
- [x] `logback-classic:1.4.14` explicit pin removed — now BOM-managed (fix for JaninoEventEvaluatorBase startup crash)
- [x] `picocli-spring-boot-starter:4.0.2` → `4.7.7`

**Verify:** `./gradlew bootRun` starts; logs appear in expected JSON format

---

### Group F — Frontend WebJars

- [x] Bootstrap WebJar: `4.2.1` → `5.3.8`
- [x] Font Awesome WebJar: `4.7.0` → `6.4.2`
- [x] No Thymeleaf templates exist in the codebase — WebJars are declared but unused; no class-name changes to verify

**Verify:** Admin UI renders correctly in browser

---

## Tier 2 — Security

### S1 — Default admin secret (Critical)
- [x] **File:** `src/main/resources/application.yml` ~line 137
- [x] Remove fallback default: `${AUTH_ADMIN_SECRET:password}` → `${AUTH_ADMIN_SECRET}` (no default)
- [ ] Verify app fails to start cleanly if `AUTH_ADMIN_SECRET` is not set (manual check at next deploy)
- [x] `.env.sample` updated — `AUTH_ADMIN_SECRET=<your_admin_secret>` added as placeholder

### S2 — Default DB password in source control (Critical)
- [x] **Files:** `.env.sample` line 16 — replaced `DB_PASS=password` with `DB_PASS=<your_local_db_password>`
- [x] `.env` is in `.gitignore` (confirmed)

### S3 — GraphQL endpoint publicly accessible (High) — ON HOLD ⚠️
- **Status:** Deferred for manual review. The `anyRequest().permitAll()` pattern may be intentional due to external client dependencies (e.g. clients that cannot send auth headers). Do not change without verifying all callers first.
- [ ] **Manual review:** Audit what external systems call `/graphql` without auth headers before restricting
- [ ] **File:** `src/main/kotlin/cta/app/config/SecurityConfig.kt` ~line 84
- Once reviewed: replace `anyRequest().permitAll()` with explicit permit list — permit `/login`, `/typeform/hook`, `/actuator/health`; require auth for `/graphql`

### S4 — CORS wildcard origin (Medium)
- [x] **Files:** `application.yml` and `application-production.yml` — replaced `origin: '*'` with explicit origins:
  - `https://app.communitytechaid.org.uk`
  - `https://app-testing.communitytechaid.org.uk`
- [x] `application-local.yml` left permissive (`*`) for local dev

### S5 — HSTS disabled (Medium)
- [x] **File:** `src/main/kotlin/cta/app/config/SecurityConfig.kt`
- [x] Removed `httpStrictTransportSecurity { it.disable() }` — HSTS now enabled by Spring Security defaults
- Note: Dokku/nginx terminates SSL; `forward-headers-strategy: NATIVE` confirms proxy setup is HTTPS-safe

### S6 — Google Places API key in URL query string (Medium)
- [x] **File:** `src/main/kotlin/cta/app/services/LocationService.kt`
- [x] Restructured to use `UriComponentsBuilder` — key no longer appears in error log messages
- Note: Google Maps Geocoding API requires key as query param (no header alternative); key still in HTTP request URL but no longer interpolated into logged strings

### S7 — Unauthenticated GraphQL queries (Medium, do after S3)
- [ ] **File:** `src/main/kotlin/cta/app/graphql/queries/GlobalQueries.kt` ~lines 22, 27
- [ ] Add `@PreAuthorize(...)` to `location()` and `buildInfo()` queries
- [ ] `buildInfo()` exposes git commit SHA and build time — restrict to authenticated users

### S8 — Actuator over-exposed in staging (Medium)
- [x] **File:** `application-staging.yml` — changed to `"health,info"`
- [x] `application-local.yml` keeps `"*"` for local dev

---

## Tier 3 — Tech Debt / Code Quality

### T1 — Replace printStackTrace() with logging
- [x] **File:** `src/main/kotlin/cta/app/services/DeviceRequestService.kt`
- [x] Both `e.printStackTrace()` calls replaced with `logger.error("Failed to send email", e)`; added `KotlinLogging` import and logger instance

### T2 — Externalise inline HTML email templates
- [x] **Files:** `src/main/resources/templates/email/fragments.html`, `device-request-acknowledged.html`, `device-request-declined.html`
- [x] Shared header/footer extracted to `fragments.html`; unique body in each template using `th:text`/`th:utext`
- [x] `DeviceRequestService` now injects `TemplateEngine` and calls `templateEngine.process(...)` instead of building inline strings

### T3 — Reduce KitMutations.kt (464 lines)
- [x] **New file:** `src/main/kotlin/cta/app/graphql/mutations/KitInputs.kt`
- [x] All `*Input` data classes and `KitSubStatusInput` moved out; `KitMutations.kt` reduced to controller class only (~140 lines)

### T4 — Split models.kt (622 lines)
- [x] **New files:** `KitModels.kt`, `DonorModels.kt`, `DeviceRequestModels.kt`, `OrganisationModels.kt`
- [x] `models.kt` now contains only `BaseEntity` and `CustomRevisionInfo` (~35 lines)
- [x] All files remain in `package cta.app` — no import changes required in other files

### T5 — Remove commented-out dead code
- [x] `build.gradle`: removed ktlint plugin refs, Datadog deps, kapt config block
- [x] `SecurityConfig.kt`: removed commented CorsFilter import, injection, and usage; removed unused `SessionManagementFilter` import
- [x] `DeviceRequestMutations.kt`: removed commented example exception handler block

### T6 — Re-enable ktlint
- [x] `org.jlleitschuh.gradle.ktlint:12.1.1` plugin added to `build.gradle`
- [x] `ktlint { version = "1.5.0" }` — uses ktlint 1.5.0 compiled against Kotlin 2.x (avoids `HEADER_KEYWORD` NoSuchFieldError with 1.4.x)
- [x] `.editorconfig` created; baseline suppressions added for intentional project conventions (lowercase filenames, consecutive-comment style in cors.kt)
- [x] `./gradlew ktlintFormat` passes cleanly; remaining suppressions documented in `.editorconfig`

### T7 — Increase test coverage
- [x] **New file:** `src/test/kotlin/cta/app/services/DeviceRequestServiceTest.kt`
- [x] 6 unit tests covering `formatDeviceRequests` (pure function, no mocks) and `markRequestStepsCompleted` (with Mockito mocks)
- [x] All 6 tests pass; uses plain JUnit 5 + Mockito (no Spring context, no Docker required)
- [ ] Add GraphQL integration tests for key mutations/queries (deferred — requires Docker for embedded Postgres)

### T8 — Remove obsolete Makefile
- [x] `Makefile` deleted (contained only a self-describing "not used in a very long time" comment)

### T9 — Pin dynamic Auth0 dependency version
- [x] `com.auth0:mvc-auth-commons:1.+` → `1.11.1` (done in Group C)

---

## Tier 4 — Dependency currency, 2026-09 (issue #209)

Branch `chore/dependency-currency-209`. One commit per step; the full suite
(`./gradlew ktlintCheck test`) must be green before the next step starts.
**Baseline before any change: 314 passed / 0 failed / 2 skipped, BUILD SUCCESSFUL in 2m 38s.**

**Target: Spring Boot 4.1.x.** Everything in this tier is a prerequisite for that move, and
the 3.5 line is deliberately skipped.

Verified facts this tier is planned around (Maven Central metadata, 2026-09-21): Spring Boot 3.4
stops at **3.4.13** and 3.5 stops at **3.5.16** on Maven Central; both lines are past OSS
end-of-life (2025-12-31 and 2026-06-30), so 3.5 is not a destination, only a resting place.
The `3.4.16` named as the fix for CVE-2026-40973 is a **commercial-only** build and is not
publicly obtainable, so that advisory cannot be closed on the 3.4 line at all - but its
precondition is inactive here (`server.servlet.session.persistent` is unset everywhere and
defaults to false), so it does not force a move on its own.

Why not step through 3.5.16 first: it would move spring-graphql 1.3.4 -> 1.4.6 and graphql-java
22.3 -> 24.3, and Boot 4.1 moves both again. That is the highest-risk migration in this repo -
hand-written SDL, the `LenientString` scalar, the depth limit in `GraphQlConfig.kt`, schema
inspection disabled - and stepping through 3.5 means paying for it twice and discarding the
first payment. Dependabot #216 (3.4.4 -> 3.5.16) is closed for this reason, not merged.

### Group A — dependabot configuration

- [x] Add `version-update:semver-major` ignores to the **docker** and **github-actions** blocks
  of `.github/dependabot.yml`; the gradle block already had them, which is why the first run
  opened six unmergeable major PRs

**Verify:** `.github/dependabot.yml` parses and all three ecosystems carry an `ignore` list

### Group B — low-risk version bumps

- [ ] Application Insights agent `3.7.8` → `3.7.10` (`Dockerfile` ARG). The 3.7.10 JFR event
  rename `MachineStats` → `MachineInfo` is inert here: the repo ships no `.jfc` file
- [ ] ktlint gradle plugin `12.1.1` → `14.2.0`. The plugin's default engine is already 1.5.0,
  identical to the `ktlint { version }` pin, so no formatting rules change
- [ ] **CRITICAL** PostgreSQL JDBC. Two separate pins, and the issue only named one:
  - [ ] buildscript classpath `42.7.10` → `42.7.13`
  - [ ] **the runtime driver.** `runtimeOnly 'org.postgresql:postgresql'` is unpinned and rides
    the Boot BOM, which resolves it to **42.7.5** — inside CVE-2026-54291's affected range
    (42.7.4–42.7.11). Bumping the buildscript pin alone does not change what ships. Boot 3.5.16's
    BOM resolves 42.7.11, still in range, so this pin is needed whichever Boot version we land on
- [ ] `org.webjars:font-awesome` `6.4.2` → `7.3.0` (Dependabot #219)
- [ ] `com.auth0:mvc-auth-commons` `1.11.1` → `1.12.1` (Dependabot #220)
- [ ] `com.sun.mail:jakarta.mail` `2.0.1` → `2.0.2` (Dependabot #221)

**Verify:** `./gradlew ktlintCheck test` green; `./gradlew dependencyInsight --configuration
runtimeClasspath --dependency org.postgresql:postgresql` reports **42.7.13** (it reports 42.7.5
today — this is the check that distinguishes a real fix from a cosmetic one)

### Group C — test infrastructure (prerequisite for Group D)

- [ ] `io.zonky.test:embedded-postgres` `2.1.0` → `2.2.2`
- [ ] `io.zonky.test:embedded-database-spring-test` `2.5.1` → `2.8.0`.
  **Do not land on 2.7.0** — it ships a runtime `ClassNotFoundException` for shaded Guava
  (zonkyio/embedded-database-spring-test#310), fixed in 2.7.1
- [ ] `com.h2database:h2` `2.2.224` → `2.5.250` (Dependabot #218)

Why this gates Group D: Flyway 11 support arrived in `embedded-database-spring-test` **2.6.0**,
so the Flyway bump cannot be verified by the test suite until this group lands.

**Verify:** `./gradlew ktlintCheck test` green — every DB-backed test spins a fresh embedded
Postgres and runs all migrations, so the whole suite is the test for this group

### Group D — framework

- [ ] `@MockBean` → `@MockitoBean` (`org.springframework.test.context.bean.override.mockito`).
  Measured footprint: **45 files, 48 annotation sites, 45 import lines**. There is **no
  `@SpyBean` anywhere in the repo**, so no `@MockitoSpyBean` work. Deprecated since Boot 3.4,
  removed in Boot 4 — this is the gate for any 4.x move, not optional polish
- [ ] Flyway `10.22.0` → `11.20.3`, both `flyway-core` and `flyway-database-postgresql`
  (the separate Postgres module is already declared, so this is a version bump, not a new
  dependency). Java 17 remains the floor

**Verify:** `./gradlew ktlintCheck test` green after each commit, with particular attention to
`SchemaValidationTest`, `SchemaDriftConvergenceTest`, `IndexMigrationTest`,
`EnversSchemaContractTest`, the `Gdpr*` suites, and `PublicSurfaceAuthorizationTest`.
**Known gap:** the embedded-DB tests run migrations from an empty database, so they do **not**
exercise Flyway 11 re-validating an existing `flyway_schema_history`. Only the UAT deploy proves
that; watch Flyway's own log lines on first start.

### Not in this tier — tracked separately

- [ ] QueryDSL `com.querydsl:5.0.0` → the `io.github.openfeign.querydsl` fork (groupId change
  plus kapt → KSP). Its own project; blocks the Kotlin upgrade
- [ ] Kotlin `2.1.20` → 2.4.x, gated behind the QueryDSL move (kapt does not support Kotlin
  language version 2.0+ without falling back to 1.9)
- [ ] **Spring Boot `3.4.4` → 4.1.x — the goal this whole tier serves.** Land on **≥ 4.0.6**:
  CVE-2026-40976 (Critical, 9.1) affects 4.0.0–4.0.5, where the default filter chain grants all
  endpoints when actuator is present without the health dependency. Java 17 is supported through
  4.1, so no JDK move is required to get there
- [ ] `eclipse-temurin` 17 → 25 (Dependabot #215). Held: Java-17 bytecode on a JRE 25 is safe in
  itself, but the Application Insights agent has an open Java 25 native-access issue
  (microsoft/ApplicationInsights-Java#4851). Elective: Boot 4.1 supports Java 17–26, so this is
  **not** on the path to the target
- [ ] Gradle build image `8.12.1-jdk17` → `9.7.1-jdk17` (Dependabot #217). Held: a real Gradle 9
  migration, **and it exposed a latent divergence** — `Dockerfile:7` builds the shipped jar with
  bare `gradle` (the image's own version), while CI's test job uses `./gradlew` (8.12.1). They
  agree today by coincidence. Worth a separate change making the Dockerfile use the wrapper so
  the pin is the single source of truth

### Observations, not acted on (CLAUDE.md §3)

- `com.h2database:h2` is declared `testImplementation` but **nothing in `src/main` or `src/test`
  references it** (`org.h2`, `jdbc:h2`, `H2Dialect` all absent — apparent matches for "h2" are
  substrings of `oauth2`). Dead weight since the zonky switch; a candidate for removal
- `org.webjars:font-awesome` is likewise unreferenced
- This file's header still says "PR to `master` when each tier or group is done", which
  contradicts CLAUDE.md §5 — PRs target `dev`, and `master` only ever records what shipped


---

## Notes

**2026-04-15 — Group A/B complete, tests green**

- Spring Boot 3.4.4 + Kotlin 2.1.20 upgrade required two fixes:
  1. `logback-classic:1.4.14` explicit pin removed — BOM now manages 1.5.x; `logstash-logback-encoder` updated to `7.4` to match.
  2. `spring.graphql.schema.inspection.enabled: false` added to `src/test/resources/application.yml`. Spring Boot 3.3+ `SchemaMappingInspector` crashes on startup with `Method must not be null` after the Kotlin 2.x upgrade — likely a Kotlin K2 compiler reflection change affecting how Spring resolves a handler method for one of the GraphQL field mappings. **TODO:** re-enable inspection and find which mapping is null (run app with inspection=true locally, check startup logs for the offending field).
  - The test resource `application.yml` completely overrides the main one during test runs — a non-obvious footgun worth documenting.
  - **2026-04-16 follow-up:** `spring.graphql.schema.inspection.enabled: false` was only set in `src/test/resources/application.yml`; staging/production crashed with `Method must not be null` from `SchemaMappingInspector`. Added the same flag to `src/main/resources/application.yml`.

**2026-04-15 — Group C complete (Auth0 2.x migration)**

- Auth0 Java SDK 1.x → 2.x requires two code changes beyond the version bump:
  1. `execute()` now returns `Response<T>` instead of `T` directly — append `.body` on every call whose return value is used. Void calls (delete, signUp, etc.) are unchanged.
  2. Package renames: `com.auth0.json.mgmt.{Role,RolesPage}` → `com.auth0.json.mgmt.roles.*`; `com.auth0.json.mgmt.PermissionsPage` → `com.auth0.json.mgmt.permissions.*`. Both `Auth0Service.kt` and `UsersGraph.kt` needed updating.
- `thymeleaf-extras-springsecurity5` → `thymeleaf-extras-springsecurity6` is a drop-in rename (same API for Spring Security 6).

**2026-04-15 — Tier 3 T* refactor complete**

- T2: Thymeleaf email templates. The two email methods in `DeviceRequestService` had identical 60-line CSS headers and footers hardcoded as Kotlin strings. Extracted to `templates/email/fragments.html` (shared header/footer) with two template files for each email type. `TemplateEngine` is now injected.
- T3: `KitMutations.kt` (464 lines) split — all 8 `*Input` data classes moved to `KitInputs.kt`; controller is now ~140 lines.
- T4: `models.kt` (622 lines) split into 4 domain files (`KitModels.kt`, `DonorModels.kt`, `DeviceRequestModels.kt`, `OrganisationModels.kt`). `models.kt` now only contains `BaseEntity` and `CustomRevisionInfo`. No import changes needed in other files (same package).
- T6: ktlint 1.5.0 enabled via plugin 12.1.1. Version pin is critical — 1.4.x causes `NoSuchFieldError: HEADER_KEYWORD` because Kotlin 2.x removed that token from `KtTokens`. Baseline suppressions in `.editorconfig` for 3 intentional project conventions.
- T7: 6 unit tests added for `DeviceRequestService`. All pass without Docker/Spring context.

**2026-04-15 — GitHub Actions Node.js deprecation (resolved)**

- Updated `actions/checkout@v4` → `@v5`, `actions/setup-java@v4` → `@v5`, `actions/upload-artifact@v4` → `@v5` to address Node.js 20 → 24 migration before 2026-06-02 enforcement deadline.
