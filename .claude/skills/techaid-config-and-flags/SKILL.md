---
name: techaid-config-and-flags
description: Catalog of every configuration axis in techaid-server — environment variables, Spring profiles, behavior flags, credential wiring, logging config, and test-config overrides. Load when adding or changing an env var / yml setting / profile, wiring a new credential, hunting where a config value comes from, or diagnosing "works locally, broken on UAT/production" (missing env var, wrong profile, wrong DDL_AUTO). Also covers which legacy config files (charts/, manifests/, Procfile) must NOT be trusted.
---

# TechAid Server — Configuration and Flags

Everything the app reads at startup, where each value comes from in each environment, and how to add a new axis safely. Verified against the repo on 2026-07-03.

**Jargon, once:**
- **Placeholder** — Spring's `${VAR:default}` syntax in yml: resolve env var `VAR`, else use `default`. No `:default` part = resolution failure if unset (bean creation crashes).
- **Profile** — a named Spring config overlay (`application-<profile>.yml`) activated via `SPRING_PROFILES_ACTIVE`.
- **Relaxed binding** — Spring maps env var `SPRING_JPA_HIBERNATE_DDL_AUTO` onto property `spring.jpa.hibernate.ddl-auto` automatically; any property can be overridden by its upper-snake-case env form even if no `${...}` placeholder exists.
- **Container App** — the Azure Container Apps resource (`api-testing` / `api-production` in resource group `tada-2026`) whose env vars + secrets are the real production config source.

**When NOT to use this skill:**
- Deploying, scaling, or inspecting the Azure apps themselves → `techaid-deploy-and-operate`.
- Flyway migrations, DDL_AUTO *incident history*, DB credentials rotation → `techaid-database-operations`.
- Setting up a local build/dev environment from scratch → `techaid-build-and-env`.
- Why an invariant exists (e.g. `enable_lazy_load_no_trans`, permitAll security model) → `techaid-architecture-contract`.

---

## 1. Master environment-variable table

Source of truth: `src/main/resources/application.yml`. Consumers verified by grep of `@Value` in `src/main/kotlin` (2026-07-03).

### Variables with NO default — app breaks if unset

| Var | Property | Consumer | Failure mode |
|-----|----------|----------|--------------|
| `TOKEN_ATTRIBUTE` | `auth0.token-attribute` | `FilterService.kt:11` | Placeholder resolution fails when the bean initializes. Because `spring.main.lazy-initialization: true`, this can surface at **first request** rather than boot — a deploy can look healthy and then 500. Value in real envs is the Auth0 custom-claim namespace (e.g. `https://communitytechaid.org.uk` per `.env.sample`). |
| `AUTH_ADMIN_SECRET` | `auth.admin-secret` | `AuthService.kt:13` | Same failure mode. This is the shared admin token accepted by `AuthService` (header `X-Auth-Admin-Secret`, overridable via property `auth.admin-header` — `AuthService.kt:10`). Blank string disables the token path (checked in `adminForToken`), but *unset* crashes bean init. |

Test runs don't hit this: `src/test/resources/application.yml` pins both (`token-attribute: https://test.example.com`, `admin-secret: password`).

### Datasource chain (exactly as written in application.yml:74–76)

```
url:      ${DATASOURCE_URL:${db-url:jdbc:postgresql://localhost:5432/techaid_api}}
username: ${POSTGRES_USER:${DATASOURCE_USERNAME:${db-user:}}}
password: ${POSTGRES_PASSWORD:${DATASOURCE_PASSWORD:${db-pass:}}}
```

Resolution order: env `DATASOURCE_URL` → property `db-url` → localhost default. For credentials: `POSTGRES_USER`/`POSTGRES_PASSWORD` win over `DATASOURCE_USERNAME`/`DATASOURCE_PASSWORD` win over `db-user`/`db-pass` win over empty string. **Trap:** docker-compose maps `.env`'s `DB_URL`/`DB_USER`/`DB_PASS` onto `DATASOURCE_*` (see §2) — the `DB_*` names exist only in docker-compose/.env, not in Spring.

### All other placeholder-driven vars

| Var | Default | Property | Consumer | Notes |
|-----|---------|----------|----------|-------|
| `PORT` | `8080` | `server.port` | Spring Boot | docker-compose also maps host port `${PORT:-8080}` |
| `JWT_ISSUER` | `''` | `spring.security...jwt.issuer-uri` | `SecurityConfig.kt:48` (`jwtDecoder`, `@Lazy`) | Empty issuer crashes only when the decoder is first needed (first JWT-bearing request) |
| `AUTH0_AUDIENCE` | `''` | `auth0.audience` | `SecurityConfig.kt:45` (audience validator) | |
| `AUTH0_DOMAIN` | `''` | `auth0.domain` | `Auth0Service.kt:17` | User-management API calls |
| `AUTH0_CLIENT_ID` | `''` | `auth0.client-id` | `Auth0Service.kt:20` | |
| `AUTH0_CLIENT_SECRET` | `''` | `auth0.client-secret` | `Auth0Service.kt:23` | |
| `GOOGLE_PLACES_KEY` | `''` | `google.places.key` | `LocationService.kt:23` | Geocoding; billed key. The `location` query is auth-gated (PR #48) |
| `GMAIL_CLIENT_ID` | `''` | `gmail.client-id` | `MailService.kt:23` | |
| `GMAIL_CLIENT_SECRET` | `''` | `gmail.client-secret` | `MailService.kt:26` | |
| `GMAIL_REFRESH_TOKEN` | `''` | `gmail.refresh-token` | `MailService.kt:29` | |
| `GMAIL_ADDRESS` | `communitytechaid@gmail.com` | `gmail.address` | `MailService.kt:32` | From-address |
| `GMAIL_ENABLED` | **`false`** | `gmail.enabled` | `MailService.kt:35` | **Email kill-switch** — see §3 |
| `GMAIL_BCC_ADDRESS` | `distributions@communitytechaid.org.uk` | `gmail.bcc-address` | `MailService.kt:38` | |
| `TYPEFORM_KEY` | `''` | `typeform.key` | `TypeformService.kt:23` | HMAC key for Typeform webhook signature check |
| `DDL_AUTO` | `validate` (via `${DDL_AUTO:${ddl-auto:validate}}`) | `spring.jpa.hibernate.ddl-auto` | Hibernate | Policy-guarded — see §3 |
| `SHOW_SQL` | `false` (via `${SHOW_SQL:${show-sql:false}}`) | `hibernate.show_sql` | Hibernate | |
| `HOSTNAME` | *(none — supplied by container runtime)* | metrics tag `instance` | Micrometer (application.yml:29) | Unset on bare-metal local runs → placeholder error only if metrics tag resolution occurs; docker/K8s always set it |

### Axes that exist only as `@Value` defaults (no yml entry, no env var today)

| Property | Default | Consumer | Purpose |
|----------|---------|----------|---------|
| `auth.admin-header` | `X-Auth-Admin-Secret` | `AuthService.kt:10` | Header name carrying the admin token |
| `google.places.url` | `https://maps.google.com/maps/api/geocode/json` | `LocationService.kt:26` | Overridden in test config to a closed port (§6) |
| `errors.stack_trace` | `false` | `CustomErrorController.kt:30` | Include stack traces in error payloads — leave off outside local debugging |
| `spring.application.name` fallback `${APP_NAME:}` | `techaid-api` (set in yml) | `CustomErrorController.kt:36` | |

`spring.application.environment` is the literal `unknown` in application.yml (used as the `env` metrics tag). Override per environment via relaxed binding: env var `SPRING_APPLICATION_ENVIRONMENT`.

### Vars consumed OUTSIDE Spring

| Var | Consumer | Notes |
|-----|----------|-------|
| `APPLICATIONINSIGHTS_CONNECTION_STRING` | App Insights Java agent (`-javaagent` in `Dockerfile` CMD) | Set on both Container Apps. Agent behavior configured by `applicationinsights.json` at repo root: role name `techaid-api`, sampling 100%, micrometer on, 4xx-not-error. Details → `techaid-diagnostics-and-observability` |
| `SPRING_PROFILES_ACTIVE` | Spring bootstrap | Profile selection (§2) |
| `DEV_APP` | **nobody** | Set in docker-compose but no consumer exists in `src/main/kotlin` (grep 2026-07-03). CORS origins are **hardcoded** in `CorsConfig.kt` (`app-testing.` and `app.communitytechaid.org.uk`). Dead var — don't cargo-cult it |

---

## 2. Profile map — what runs where

| Environment | Profile | How activated | Key overrides |
|---|---|---|---|
| Local docker-compose | `local` (per `.env.sample`) | `SPRING_PROFILES_ACTIVE` from `.env` → compose `web.environment` | `application-local.yml`: actuator exposure `*`; extra Flyway location `classpath:db/local` (directory **does not exist** in the repo — Flyway tolerates the missing location; don't "fix" by creating it without need) |
| CI tests | *(default + test resources)* | none set | `src/test/resources/application.yml` overlays (§6) |
| UAT `api-testing` | set on the Container App | env var on the app — inspect, don't assume | ddl-auto resolves `validate` by default since PR #49 |
| Production `api-production` | set on the Container App | same | `application-production.yml`: `ddl-auto: none` (hard override — env `DDL_AUTO` can't raise it while the production profile is active), `cta: DEBUG` logging |

`application-staging.yml` exists (actuator exposure `health,info` only) — Dokku-era; nothing in the repo activates it today. Verify before relying on it.

**Inspect the real env of a deployed app** (read-only; date-stamp any copy you make — Azure-side values drift):

```bash
az containerapp show -n api-production -g tada-2026 --query "properties.template.containers[0].env"
az containerapp show -n api-testing    -g tada-2026 --query "properties.template.containers[0].env"
```

Secret-typed values show as `secretRef`, not plaintext. Real credential values live in Container App secrets and in Bitwarden — never in this repo.

---

## 3. Behavior flags and guards

| Flag | Where | Default | Guard / policy |
|------|-------|---------|----------------|
| `GMAIL_ENABLED` | env → `gmail.enabled` | `false` | **Kill-switch for all outbound email** (device-request notifications, decline emails). Default-off means a fresh environment silently sends nothing — check this first when "emails aren't going out". Turning it ON in any shared environment is a behavior change → change control |
| `DDL_AUTO` | env → `spring.jpa.hibernate.ddl-auto` | `validate` | **Fenced:** default was flipped `update`→`validate` in PR #49 together with the Flyway baseline migration. Do NOT set it back to `update` anywhere — schema changes go through Flyway only. Production profile pins `none`. History → `techaid-failure-archaeology`; migration discipline → `techaid-database-operations` |
| `spring.graphql.schema.inspection.enabled` | application.yml | `false` | Disabled because the SchemaMappingInspector crashes after the Kotlin 2.x upgrade (open TODO, also noted in test yml). Consequence: schema/resolver mismatches are NOT caught at startup — this is why wiring tests exist (`techaid-validation-and-qa`). Don't re-enable casually; if you try, do it red/green and see `techaid-failure-archaeology` |
| `spring.main.lazy-initialization` | application.yml | `true` | Cold-start optimization for scale-to-zero Container Apps. Side effect: missing-config crashes surface at first use, not boot (§1). Don't disable without measuring startup impact |
| `spring.flyway.out-of-order` | application.yml | `true` | Allows applying a migration whose version sorts before already-applied ones. Load-bearing given the repo's date-based versioning across branches. Leave on |
| `hibernate.enable_lazy_load_no_trans` | application.yml | `true` | Known-weak point (hides N+1 / lazy-loading bugs). Parked tech debt — rationale and removal plan → `techaid-architecture-contract`. Don't flip in passing |
| `SHOW_SQL` | env | `false` | Local debugging only; noisy at scale |
| Actuator exposure | application.yml + profiles | `health,info,metrics`; `health.show-details: when-authorized` | Health endpoint is anonymously reachable for container probes but must expose **status only** to unauthenticated callers (regression-tested by `ActuatorHealthDetailsTest`). Widening exposure or details is a security change → change control |
| `errors.stack_trace` | property | `false` | Stack traces in HTTP error bodies — never enable in shared environments |

---

## 4. Credential axes (names only — values live in Container App secrets/env and Bitwarden)

| Group | Vars | Used for |
|-------|------|----------|
| Auth0 resource-server | `JWT_ISSUER`, `AUTH0_AUDIENCE`, `TOKEN_ATTRIBUTE` | Validating dashboard JWTs; custom-claim namespace for name/email extraction |
| Auth0 management | `AUTH0_DOMAIN`, `AUTH0_CLIENT_ID`, `AUTH0_CLIENT_SECRET` | User admin (list/create/update users via Auth0 API) |
| Gmail API | `GMAIL_CLIENT_ID`, `GMAIL_CLIENT_SECRET`, `GMAIL_REFRESH_TOKEN` (+ `GMAIL_ADDRESS`, `GMAIL_BCC_ADDRESS`, `GMAIL_ENABLED`) | Outbound notification email. README documents the OAuth code→refresh-token dance |
| Typeform | `TYPEFORM_KEY` | HMAC verification of intake webhooks |
| Google Places | `GOOGLE_PLACES_KEY` | Geocoding (`location` GraphQL query) — billed |
| Admin token | `AUTH_ADMIN_SECRET` | Full-access shared secret via `X-Auth-Admin-Secret` header (constant-time compared). Rotation = update Container App secret + every caller |
| Telemetry | `APPLICATIONINSIGHTS_CONNECTION_STRING` | App Insights agent |
| Database | via datasource chain (§1) | Per-env logins (`api_uat`, `api_prod`) → `techaid-database-operations` |

---

## 5. Logging configuration

- `src/main/resources/logback-spring.xml`: standard Spring console appender for app logs, plus a dedicated `ACCESS_JSON` appender — logger `cta.access` emits one JSON line per HTTP request (logstash encoder, `additivity=false` so no duplicates). Produced by `AccessLoggingFilter` (`RequestFilterConfig.kt`), includes `graphql_operation` when set. Query it in Log Analytics via `logger == "cta.access"` — interpretation → `techaid-diagnostics-and-observability`.
- `logging.level` tree in application.yml: `cta: DEBUG`, security/tomcat quieted. `application-production.yml` re-asserts `cta: DEBUG`.
- App Insights agent also captures logging at INFO+ (`applicationinsights.json` → `instrumentation.logging.level: INFO`).

---

## 6. Test configuration contrasts (`src/test/resources/application.yml`)

| Override | Value | Why |
|----------|-------|-----|
| `zonky.test.database.provider` | `zonky` | Embedded Postgres **binaries**, not the default Docker provider — DB tests run without a Docker daemon, locally and in CI |
| `google.places.url` | `http://127.0.0.1:1/geocode` | Closed port: any accidental geocode call fails fast instead of hitting Google |
| `auth0.token-attribute` / issuer | `https://test.example.com` (+ `/`) | Dummy values so beans initialize without real Auth0 |
| `auth.admin-secret` | `password` | Enables admin-token test paths |
| `gmail.*` | empty / `enabled: false` | No email from tests |
| `spring.graphql.schema.inspection.enabled` | `false` | Same Kotlin 2.x inspector crash; TODO comment in file |

---

## 7. Checklist — adding a configuration axis

1. Add to `application.yml` as `property: ${NEW_VAR:safe-default}`. Prefer a default that is safe in production (feature OFF, empty credential). Only omit the default if boot-time failure is genuinely the right behavior — and remember lazy init delays that failure (§1).
2. Consume via `@Value("\${property.name}")` (match existing style) or `@ConfigurationProperties`.
3. Behavior-bearing flag? Write the red/green test first (`techaid-validation-and-qa`).
4. Add the var to `.env.sample` (placeholder value, never real) and, if local dev needs it, to the `web.environment` block in `docker-compose.yml`.
5. Decide per-environment values. Setting it on `api-testing`/`api-production` is an Azure infra mutation — get explicit sign-off first (`techaid-change-control`), then:
   ```bash
   az containerapp update -n api-testing -g tada-2026 --set-env-vars NEW_VAR=value
   # secrets: az containerapp secret set ... then reference with secretref:
   ```
   Note: updating env vars creates a new revision (`techaid-deploy-and-operate`).
6. Add the row to this skill's tables.

---

## 8. Legacy config — do NOT trust or edit

Dokku/Kubernetes-era files, pending cleanup (roadmap item A6, `techaid-roadmap-and-frontier`). Verified 2026-07-03: **no reference to any of these in `.github/workflows/`** — the live pipeline is ci.yml → GHCR image → Container Apps.

| File(s) | Was | Status |
|---------|-----|--------|
| `charts/` (Helm values, secrets.*.yaml) | K8s-era deploy config | Dead; values stale |
| `manifests/` (config/database/ingress/web/snapshot yaml) | K8s manifests | Dead |
| `Procfile` | Dokku process file | Dead (still `COPY`'d into the image by `Dockerfile`, but the image `CMD` overrides it) |
| `app-cname-validate.sh`, `cf-proxy-on.sh`, `migrate-prod-db.sh`, `rollback-prod.sh` | One-shot migration scripts (2026-05 cutover) — **gitignored, maintainer-local; absent from fresh clones** | Historical; do not re-run |
| `Dockerfile.local` | Local image on **JDK 11** | Stale (app needs 17); use `Dockerfile.dev` + docker-compose |
| `docker/proxy.conf` | nginx for a commented-out compose service | Dead |

The real deploy config axes are: `Dockerfile` (JVM flags, `AI_AGENT_VERSION` build arg), `ci.yml`/`promote.yml`, and the Container App resources themselves → `techaid-deploy-and-operate`.

---

## Provenance and maintenance

Authored 2026-07-03 against branch `dev` (HEAD `76b092f`). Everything above verified by reading the named files and grepping consumers; Azure-side values intentionally stated as inspection commands, not facts.

Re-verify before trusting:
- Placeholder inventory: `grep -rnoE '\$\{[A-Za-z_.-]+(:[^}]*)?\}' src/main/resources/*.yml`
- Consumers: `grep -rn '@Value' src/main/kotlin --include='*.kt'`
- Deployed env (both apps): `az containerapp show -n api-production -g tada-2026 --query "properties.template.containers[0].env"` (repeat with `api-testing`)
- Test overrides: `cat src/test/resources/application.yml`
- Legacy files still unreferenced by CI: `grep -rn "charts\|manifests\|Procfile" .github/workflows/` (expect no output)
- Dead `DEV_APP` still dead: `grep -rn "DEV_APP" src/main/kotlin` (expect no output)
