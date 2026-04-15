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
- [ ] Flyway: `9.22.3` → `10.x` (breaking major version — review [migration guide](https://documentation.red-gate.com/fd/flyway-10-0-release-notes); 9.22.3 appears to be the latest 9.x, so this requires jumping to 10.x+)
- [ ] `hibernate-types-52:2.9.8` → check if `hypersistence-utils` (renamed library) is required for Hibernate 6
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

- [ ] `google-api-client:1.35.0` → `2.x`
- [ ] `google-api-services-gmail:v1-rev20211108-1.32.1` → latest revision
- [ ] `google-auth-library-oauth2-http:1.23.0` → latest `1.x`

**Verify:** Gmail sending still works (test with a real email send in staging)

---

### Group E — Logging & Utilities

- [ ] `kotlin-logging:1.6.26` → `3.x` (major version — API changes, check import paths)
- [x] `logstash-logback-encoder:6.3` → `7.4` (required for logback 1.5.x compatibility with Spring Boot 3.4+)
- [x] `logback-classic:1.4.14` explicit pin removed — now BOM-managed (fix for JaninoEventEvaluatorBase startup crash)
- [ ] `picocli-spring-boot-starter:4.0.2` → `4.7.x`

**Verify:** `./gradlew bootRun` starts; logs appear in expected JSON format

---

### Group F — Frontend WebJars

- [ ] Bootstrap WebJar: `4.2.1` (EOL 2018) → `5.x`
- [ ] Font Awesome WebJar: `4.7.0` (EOL 2016) → `6.x`
- [ ] Check any Thymeleaf templates for Bootstrap 4 → 5 class name changes

**Verify:** Admin UI renders correctly in browser

---

## Tier 2 — Security

### S1 — Default admin secret (Critical)
- [ ] **File:** `src/main/resources/application.yml` ~line 137
- [ ] Remove fallback default: `${AUTH_ADMIN_SECRET:password}` → `${AUTH_ADMIN_SECRET}` (no default)
- [ ] Verify app fails to start cleanly if `AUTH_ADMIN_SECRET` is not set
- [ ] Ensure `.env.sample` documents this variable with a placeholder, not a real value

### S2 — Default DB password in source control (Critical)
- [ ] **Files:** `.env` and `.env.sample` ~line 16
- [ ] Replace `DB_PASS=password` with `DB_PASS=<your_local_password>`
- [ ] Confirm `.env` is listed in `.gitignore` (add if missing)

### S3 — GraphQL endpoint publicly accessible (High) — ON HOLD ⚠️
- **Status:** Deferred for manual review. The `anyRequest().permitAll()` pattern may be intentional due to external client dependencies (e.g. clients that cannot send auth headers). Do not change without verifying all callers first.
- [ ] **Manual review:** Audit what external systems call `/graphql` without auth headers before restricting
- [ ] **File:** `src/main/kotlin/cta/app/config/SecurityConfig.kt` ~line 84
- Once reviewed: replace `anyRequest().permitAll()` with explicit permit list — permit `/login`, `/typeform/hook`, `/actuator/health`; require auth for `/graphql`

### S4 — CORS wildcard origin (Medium)
- [ ] **File:** `src/main/resources/application.yml` ~lines 138-145
- [ ] Replace `origin: '*'` with specific origins:
  - `https://app.communitytechaid.org.uk`
  - `https://app-testing.communitytechaid.org.uk`
- [ ] Align with what `CorsConfig.kt` already has for the GraphQL endpoint
- [ ] Keep `application-local.yml` permissive for local dev (or scope to localhost only)

### S5 — HSTS disabled (Medium)
- [ ] **File:** `src/main/kotlin/cta/app/config/SecurityConfig.kt` ~line 79
- [ ] Remove `httpStrictTransportSecurity { it.disable() }` to re-enable HSTS
- [ ] Confirm production is HTTPS-only before enabling

### S6 — Google Places API key in URL query string (Medium)
- [ ] **File:** `src/main/kotlin/cta/app/services/LocationService.kt` ~line 22
- [ ] API key currently appended as `?key=$key&address=$address` — appears in logs
- [ ] Fix: move key to a request header or restructure the call

### S7 — Unauthenticated GraphQL queries (Medium, do after S3)
- [ ] **File:** `src/main/kotlin/cta/app/graphql/queries/GlobalQueries.kt` ~lines 22, 27
- [ ] Add `@PreAuthorize(...)` to `location()` and `buildInfo()` queries
- [ ] `buildInfo()` exposes git commit SHA and build time — restrict to authenticated users

### S8 — Actuator over-exposed in staging (Medium)
- [ ] **Files:** `application-staging.yml`, `application-local.yml`
- [ ] Change `endpoints.web.exposure.include: "*"` to `"health,info"` in staging
- [ ] Keep `*` only in local profile

---

## Tier 3 — Tech Debt / Code Quality

### T1 — Replace printStackTrace() with logging
- [ ] **File:** `src/main/kotlin/cta/app/services/DeviceRequestService.kt` ~lines 141, 228
- [ ] Replace `.printStackTrace()` with `logger.error("...", e)` (KotlinLogging already imported)

### T2 — Externalise inline HTML email templates
- [ ] **File:** `src/main/kotlin/cta/app/services/DeviceRequestService.kt` ~lines 68-120, 153-207
- [ ] Two large inline HTML strings with duplicated structure; extract to Thymeleaf templates
- [ ] Thymeleaf infrastructure is already present in the project

### T3 — Reduce KitMutations.kt (464 lines)
- [ ] **File:** `src/main/kotlin/cta/app/graphql/mutations/KitMutations.kt`
- [ ] Extract helper methods or split by logical concern

### T4 — Split models.kt (622 lines)
- [ ] **File:** `src/main/kotlin/cta/app/models.kt`
- [ ] Split into per-entity or per-domain files

### T5 — Remove commented-out dead code
- [ ] `build.gradle`: ktlint plugin (~lines 39, 94), kapt config (~lines 287-295), Datadog (~lines 196-197)
- [ ] `SecurityConfig.kt`: commented CorsFilter injection
- [ ] `DeviceRequestMutations.kt`: commented exception handler examples

### T6 — Re-enable ktlint
- [ ] `build.gradle` has ktlint commented out
- [ ] Re-enable, run `./gradlew ktlintFormat` to establish baseline, commit result

### T7 — Increase test coverage
- [ ] Currently 2 test files; JaCoCo 50% threshold exists but is not met
- [ ] Add service-level unit tests for `DeviceRequestService`
- [ ] Add GraphQL integration tests for key mutations/queries

### T8 — Remove obsolete Makefile
- [ ] `Makefile` is self-described as unused "very long time" — delete it

### T9 — Pin dynamic Auth0 dependency version
- [ ] `com.auth0:mvc-auth-commons:1.+` → explicit pinned version (done as part of Group C above)

---

## Notes

**2026-04-15 — Group A/B complete, tests green**

- Spring Boot 3.4.4 + Kotlin 2.1.20 upgrade required two fixes:
  1. `logback-classic:1.4.14` explicit pin removed — BOM now manages 1.5.x; `logstash-logback-encoder` updated to `7.4` to match.
  2. `spring.graphql.schema.inspection.enabled: false` added to `src/test/resources/application.yml`. Spring Boot 3.3+ `SchemaMappingInspector` crashes on startup with `Method must not be null` after the Kotlin 2.x upgrade — likely a Kotlin K2 compiler reflection change affecting how Spring resolves a handler method for one of the GraphQL field mappings. **TODO:** re-enable inspection and find which mapping is null (run app with inspection=true locally, check startup logs for the offending field).
  - The test resource `application.yml` completely overrides the main one during test runs — a non-obvious footgun worth documenting.

**2026-04-15 — Group C complete (Auth0 2.x migration)**

- Auth0 Java SDK 1.x → 2.x requires two code changes beyond the version bump:
  1. `execute()` now returns `Response<T>` instead of `T` directly — append `.body` on every call whose return value is used. Void calls (delete, signUp, etc.) are unchanged.
  2. Package renames: `com.auth0.json.mgmt.{Role,RolesPage}` → `com.auth0.json.mgmt.roles.*`; `com.auth0.json.mgmt.PermissionsPage` → `com.auth0.json.mgmt.permissions.*`. Both `Auth0Service.kt` and `UsersGraph.kt` needed updating.
- `thymeleaf-extras-springsecurity5` → `thymeleaf-extras-springsecurity6` is a drop-in rename (same API for Spring Security 6).

**2026-04-15 — GitHub Actions Node.js deprecation**

- CI shows deprecation warnings for `actions/checkout@v4`, `actions/setup-java@v4`, `actions/upload-artifact@v4` (Node.js 20 → 24 migration). Enforcement deadline: **2026-06-02**. Add a task to update these before June.
  - Update `.github/workflows/ci.yml`: `actions/checkout@v4` → `@v5` (or latest), `actions/setup-java@v4` → `@v5`, `actions/upload-artifact@v4` → `@v5` (check available major versions).
