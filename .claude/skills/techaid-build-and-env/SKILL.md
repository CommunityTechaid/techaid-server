---
name: techaid-build-and-env
description: >
  Set up the techaid-server development environment from scratch and build/test it reliably —
  getting started / onboarding. Load this when: cloning the repo for the first time, a Gradle build or test run fails to
  start, ktlint/kapt/QueryDSL Q-class errors appear, you need to run the app locally
  (docker-compose or bootRun), you are editing a Dockerfile, or you hit Windows-specific
  tooling problems (PowerShell blocked, az path mangling, line endings). NOT for deploys
  (techaid-deploy-and-operate), config semantics (techaid-config-and-flags), or writing
  tests (techaid-validation-and-qa).
---

# techaid-server: Build and Environment

How to go from a fresh clone to green tests and a locally running API, plus every
environment trap we know about. All facts verified against the repo on **2026-07-03**
unless marked otherwise.

## When NOT to use this skill

| You want to… | Use instead |
|---|---|
| Deploy to UAT/production, operate Azure Container Apps | `techaid-deploy-and-operate` |
| Understand what a config property/env var *means* | `techaid-config-and-flags` |
| Write or run tests the project way (red/green, zonky patterns) | `techaid-validation-and-qa` |
| Author or fix Flyway migrations, touch any real database | `techaid-database-operations` |
| Debug a runtime failure (404s, auth, GraphQL errors) | `techaid-debugging-playbook` |

## Jargon (defined once)

- **Gradle wrapper** (`./gradlew` / `gradlew.bat`): script that downloads and runs the pinned Gradle version. Never install Gradle yourself.
- **ktlint**: Kotlin linter/formatter. CI fails on violations. Rules live in `.editorconfig`.
- **kapt**: Kotlin annotation processing — here it generates **QueryDSL Q-classes** (typed query metamodel, e.g. `QKit`) from JPA entities.
- **zonky**: `io.zonky.test` embedded PostgreSQL — real Postgres binaries downloaded as a Maven artifact, so DB-backed tests run **without Docker**.
- **Adminer**: single-file web DB browser bundled in docker-compose.

## 1. Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | **17** (Temurin recommended) | `sourceCompatibility = '17'`, `jvmTarget = '17'` in `build.gradle`. Verified working locally with Temurin 17.0.18. |
| Git | any recent | |
| Gradle | **none — do not install** | Wrapper pins 8.12.1 (`gradle/wrapper/gradle-wrapper.properties`). |
| Docker | **optional** | Needed only for the docker-compose way of *running* the app. Tests do NOT need Docker: `build.gradle` declares `io.zonky.test:embedded-database-spring-test:2.5.1` + `io.zonky.test:embedded-postgres:2.1.0`, and `src/test/resources/application.yml` sets `zonky.test.database.provider: zonky` (embedded binaries, not the Docker provider) precisely because Docker Desktop's npipe was unreliable. |

Check: `java -version` → must say 17.x.

## 2. Clone → green tests (the fast path)

```bash
git clone https://github.com/CommunityTechaid/techaid-server.git
cd techaid-server
./gradlew ktlintCheck test        # Git Bash / macOS / Linux
# or from cmd.exe:  gradlew.bat ktlintCheck test
```

What to expect:

- **First run**: Gradle 8.12.1 distribution + all dependencies + embedded Postgres binaries download. Budget extra time on first run.
- **Warm run, measured 2026-07-03 on a Windows dev laptop: BUILD SUCCESSFUL in 3m 34s** (`ktlintCheck test`, 19 actionable tasks). Tests boot several Spring contexts and start/stop embedded Postgres instances — the wall of Postgres log lines (`received fast shutdown request`, `DROP DATABASE IF EXISTS …`) at the end is normal, not an error.
- Test events print as `PASSED`/`FAILED`/`SKIPPED` (configured in `build.gradle` `test {}` block). HTML report: `build/reports/tests/test/index.html`.
- `test` is finalized by `jacocoTestReport` (coverage; xml/csv outputs disabled).
- A Gradle deprecation warning about Gradle 9.0 incompatibility appears — known, harmless today.

This exact pair (`ktlintCheck` then `test`) is what CI runs (`.github/workflows/ci.yml`), so green here ≈ green CI.

## 3. Build anatomy (`build.gradle`)

- **Kotlin 2.1.20, Spring Boot 3.4.4**, dependency-management 1.1.7. Versions are annotated with update history comments in the `buildscript.ext` block.
- **Plugins that matter**: `kotlin-spring` + `allOpen` (opens classes annotated `@Entity`/`@MappedSuperclass`/`@Embeddable` — JPA proxies need non-final classes), `kotlin-jpa` (no-arg constructors), `kotlin-kapt`, `org.jlleitschuh.gradle.ktlint` 12.1.1, `jacoco`.
- **QueryDSL**: `com.querydsl:querydsl-jpa:5.0.0:jakarta` + `kapt "com.querydsl:querydsl-apt:5.0.0:jakarta"`. Q-classes are generated into **`build/generated/source/kapt/main`** (the `idea {}` block registers this as a source dir). If IntelliJ shows red `QKit`/`QDeviceRequest` symbols: run `./gradlew kaptKotlin` (or any build) and re-sync.
- **ktlint engine pinned to 1.5.0** via the `ktlint { version = "1.5.0" }` block. Do not downgrade: ktlint 1.4.x was compiled against Kotlin 1.9 and throws `NoSuchFieldError: KtTokens.HEADER_KEYWORD` under Kotlin 2.x (comment in `build.gradle` records this). Format with `./gradlew ktlintFormat`, check with `ktlintCheck`.
- **Build info**: `springBoot.buildInfo` embeds `git.commit` from `-PgitCommit=<sha>` (defaults to `unknown`). The production `Dockerfile` passes it as `GIT_COMMIT` build-arg; it surfaces at runtime via `/actuator/info` and the GraphQL buildInfo — used to verify what's deployed.
- **Coverage**: `jacocoTestCoverageVerification` declares a 0.5 minimum but is **not wired into `check`** — it only runs if you invoke it explicitly. Note: its exclude patterns (`com/alphasights/**`) reference a package that no longer exists (code lives under `cta/`); vestigial, harmless.
- **Vestigial, do not build on these** (verified 2026-07-03): the `com.netflix.dgs.codegen` plugin 6.0.3 is applied but has **no configuration block and no generated-code usage** — the app uses Spring for GraphQL (`spring-boot-starter-graphql`) with hand-written SDL in `src/main/resources/graphql/*.graphqls`. Likewise `graphQLVersion = '5.7.3'` feeds only commented-out graphql-kickstart dependencies. Treat both as dead weight, not as a signal that DGS codegen is in use.
- Utility tasks `updateAppName` and `getDeps` exist; `getDeps` is used by `Dockerfile.dev` to pre-fetch dependencies.

## 4. Running the app locally

Two supported ways. Both need a `.env`-style secret set — see `techaid-config-and-flags` for what each variable means.

### 4a. docker-compose (the README-documented way)

```bash
cp .env.sample .env      # then fill in the <value> placeholders you need
docker compose up -d
docker exec -it techaid-server-web-1 bash
./gradlew bootRun -PskipDownload=true    # inside the container
```

Services (from `docker-compose.yml`):

| Service | Image | Host port | Notes |
|---|---|---|---|
| `web` | built from `Dockerfile.dev`, container name **`techaid-server-web-1`** (fixed — the dashboard's local setup depends on this name and on the network name `techaid-server_default`) | `${PORT:-8080}` → 8080 | Idles (`tail -f /dev/null`) until you exec in and run `bootRun`. Mounts `./src` so edits are visible; Ctrl-C and rerun `bootRun` to reload. Runs with `DDL_AUTO: update` and `SHOW_SQL: true`. |
| `postgres` | `postgres:16` | **5423** → 5432 | ⚠ Non-standard host port 5423. DB `techaid_api`, user `postgres`, password `password` (compose-internal only). Data persists in the `pgdata` volume. |
| `adminer` | `adminer` | 8900 | DB browser at http://localhost:8900 — server `postgres`, credentials as above. |

**Stale-README flags (verified 2026-07-03):**
- `-PskipDownload=true` (README and `start.sh`) is **not defined anywhere in `build.gradle`** — Gradle silently ignores unknown `-P` properties, so it is a harmless no-op. Don't waste time looking for what it does.
- README mentions a `setup_branch` "containing the docker files" — the docker files are on the main branches; ignore that paragraph.
- The compose Postgres is 16 while UAT/production run Postgres 17 (see `techaid-deploy-and-operate`). Fine for dev; don't infer version-specific behavior from it.

To seed data, `pg_restore` a dump into the postgres container exactly as the README shows (commands there are correct).

### 4b. Bare-metal `./gradlew bootRun`

Requires a reachable Postgres. Defaults in `application.yml` point at `jdbc:postgresql://localhost:5432/techaid_api` (note: port 5432 — if you use the compose Postgres from the host, override to 5423).

Minimum env (most properties default to empty and degrade gracefully, but these two have **no default** and abort startup if unset):

```bash
export TOKEN_ATTRIBUTE=https://communitytechaid.org.uk
export AUTH_ADMIN_SECRET=<any-local-secret>
export SPRING_PROFILES_ACTIVE=local
export JWT_ISSUER=https://techaid-auth.eu.auth0.com/   # needed for real logins; tests stub it
./gradlew bootRun
```

The `local` profile (`application-local.yml`) exposes all actuator endpoints and adds a second Flyway location `classpath:db/local` — **that directory does not exist in the repo**; Flyway tolerates missing locations by default, so this is inert. Full variable catalog: `techaid-config-and-flags`.

## 5. Dockerfile matrix

| File | Purpose | State |
|---|---|---|
| `Dockerfile` | **Production/UAT image** (built by CI). Multi-stage: `gradle:8.12.1-jdk17` builder (`clean build -x test -PgitCommit=…`) → `eclipse-temurin:17-jre-alpine` + tini init + Application Insights Java agent (downloaded at build, `AI_AGENT_VERSION=3.7.8`) + `applicationinsights.json`. JVM: `-XX:MaxRAMPercentage=75.0`. | Current. **Trap:** the builder copies `src`, `build.gradle`, `settings.gradle`, **and `.editorconfig`** — ktlint config lives in `.editorconfig`; a past image build broke when it wasn't copied. If you add root-level files the build needs, add them to the `COPY` line. |
| `Dockerfile.dev` | The docker-compose `web` service: pre-fetches deps via `getDeps`, then idles for interactive `bootRun`. | Works, but internally pins Gradle **8.6** (repo wrapper is 8.12.1) — a version skew nobody has fixed; expect the container to use 8.6. |
| `Dockerfile.local` | Wrap a locally built jar (`build/libs/*.jar`) in a minimal image. | **Stale/broken**: base is `adoptopenjdk/openjdk11` JRE but the app requires Java 17. Do not use without fixing the base image. |

## 6. Windows / tooling traps (as of 2026-07-03)

- **BitDefender blocks PowerShell process spawning** on the primary dev machine. Run `az`, `gh`, and other CLIs from **Git Bash**, not PowerShell.
- **Git Bash mangles `/subscriptions/...` arguments** into Windows paths. Prefix az commands that take resource IDs: `MSYS_NO_PATHCONV=1 az resource show --ids /subscriptions/...`.
- **Line endings**: `.editorconfig` mandates `end_of_line = lf` and there is **no `.gitattributes`**. Use an editor that honors EditorConfig (IntelliJ and VS Code do) so you don't introduce CRLF churn that ktlint/diffs then flag.
- `.editorconfig` also carries deliberate ktlint suppressions (lowercase multi-declaration filenames like `models.kt`, wildcard imports in two legacy files, a max-line-length exemption for `CustomErrorController.kt`). Don't "fix" code those suppressions exist for — see CLAUDE.md's surgical-changes rule.

## 7. IDE notes

- **IntelliJ IDEA recommended** (README's advice; the `idea {}` block wires kapt-generated sources automatically).
- README claims VS Code devcontainer attach is broken (their claim, unverified since); plain VS Code + Git Bash terminal works fine for build/test.

## 8. "Environment is good" checklist

- [ ] `java -version` → 17.x
- [ ] `./gradlew ktlintCheck test` → `BUILD SUCCESSFUL` (~3–4 min warm; longer first run)
- [ ] (Optional, full stack) `docker compose up -d` → `curl http://localhost:8080/actuator/health` returns `{"status":"UP"}` after you've run `bootRun` inside `techaid-server-web-1`
- [ ] IntelliJ resolves `QKit` and friends after a build/sync

## Provenance and maintenance

Authored 2026-07-03 against branch `dev` (HEAD `76b092f`). Facts verified by reading
`build.gradle`, `gradle/wrapper/gradle-wrapper.properties`, `docker-compose.yml`,
`Dockerfile*`, `.env.sample`, `.editorconfig`, `src/test/resources/application.yml`,
`src/main/resources/application*.yml`, `.github/workflows/ci.yml`, and by actually running
`./gradlew ktlintCheck test` (BUILD SUCCESSFUL, 3m34s) and `./gradlew --version` on 2026-07-03.

Re-verify before trusting, one-liners:

- Gradle/Kotlin/Boot versions: `grep -E "kotlinVersion|springBootVersion|distributionUrl" build.gradle gradle/wrapper/gradle-wrapper.properties`
- ktlint engine pin still needed: `grep -A2 "^ktlint {" build.gradle`
- Test suite still green + timing: `./gradlew ktlintCheck test`
- zonky still Docker-free: `grep -rn "provider: zonky" src/test/resources/application.yml`
- DGS codegen still vestigial: `grep -rn "dgs" build.gradle && grep -rln "netflix.dgs" src/ || echo "no source usage"`
- compose ports/services: `grep -nE "ports|image:" docker-compose.yml`
- AI agent version: `grep AI_AGENT_VERSION Dockerfile`
- skipDownload still a no-op: `grep -rn skipDownload build.gradle || echo "still undefined"`
