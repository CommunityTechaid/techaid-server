---
name: techaid-diagnostics-and-observability
description: How to MEASURE the techaid-server system instead of eyeballing it. Load this when investigating performance, slowness, outages, cold starts, errors in production or UAT, when you need telemetry/logs/metrics (Application Insights, Log Analytics, KQL, container logs, access logs), when checking or adding alerts, when verifying a deploy landed (health/build commit), or before making any before/after performance claim. Ships tested scripts in scripts/.
---

# TechAid Diagnostics and Observability

This skill maps every diagnostic surface of the TechAid API (`techaid-server`, running as Azure Container Apps `api-testing` and `api-production`) and gives you copy-paste queries with interpretation guides, plus four tested scripts. The rule of this project: **never claim "it's slow", "it's fixed", or "it's down" without a number or a log line from one of these surfaces.**

**When NOT to use this skill:**
- Deploying, promoting, rolling back, or restarting apps → `techaid-deploy-and-operate` (and `techaid-prod-promotion-campaign` for dev→master).
- A symptom you want triaged to a cause → `techaid-debugging-playbook` (it will send you back here for the measurement steps).
- Database-level investigation (slow queries inside Postgres, migrations, ownership) → `techaid-database-operations`.
- Understanding why telemetry code in the repo looks the way it does (past incidents) → `techaid-failure-archaeology`.
- Designing an experiment / proving a hypothesis with these numbers → `techaid-investigation-methodology`.

## Jargon (defined once)

| Term | Meaning here |
|---|---|
| Log Analytics workspace | Azure's queryable log store. Ours: `workspace-tada2026ubat` in resource group `tada-2026`. Everything below queries this one workspace. |
| KQL | Kusto Query Language — SQL-like language used to query Log Analytics. |
| Workspace-based App Insights | Application Insights that stores data in a Log Analytics workspace, with table names like `AppRequests`. The **classic** table names (`requests`, `dependencies`, `traces`) DO NOT WORK here — querying them returns nothing and has wasted real investigation time. Our component: `TaDa-API` in `tada-2026`. |
| App Insights Java agent | `applicationinsights-agent-<ver>.jar` attached via `-javaagent` in the `Dockerfile` (`ARG AI_AGENT_VERSION=3.7.8` as of 2026-07-04). Auto-instruments HTTP, JDBC, outbound calls. Configured by `applicationinsights.json` at repo root: role name `techaid-api`, sampling 100%, Micrometer on, `captureHttpServer4xxAsError: false`. |
| KEDA | Kubernetes Event-Driven Autoscaler — scales Container Apps replicas. Prod apps use a cron rule (business hours) between 0 and 1 replicas; scale-to-zero is why the first request after idle is slow (cold start ~40–90s). |
| Cold start | Replica count 0 → 1: container pull + JVM + Spring boot. Shows up as one very slow first request and a burst of `ProbeFailed` (StartUp) events — this burst is NORMAL. |

## 1. Telemetry map — where each signal lives

All roads lead to Log Analytics workspace `workspace-tada2026ubat`. Get its GUID (needed by `az monitor log-analytics query`):

```bash
az monitor log-analytics workspace show -g tada-2026 -n workspace-tada2026ubat --query customerId -o tsv
# → 432f14c1-3681-4134-a56d-9f0affa75504   (verified 2026-07-04)
```

| Signal | Table | Notes |
|---|---|---|
| HTTP/GraphQL server requests | `AppRequests` | `Name` includes the GraphQL operation, e.g. `POST /graphql findAllKits`. `UserAuthenticatedId` = user email (set by `UserTelemetryFilter`). |
| Outbound calls (Postgres, Auth0, Gmail/Google) | `AppDependencies` | `Target` shows host + DB, e.g. `techaid-pg-svr.privatelink... \| techaid_uat`. |
| App log lines (INFO+) | `AppTraces` | Includes the GraphQL-error WARN lines with input variables (see §3). |
| Exceptions | `AppExceptions` | |
| JVM/system counters | `AppPerformanceCounters` | |
| Replica lifecycle (scale, probes, container create/terminate) | `ContainerAppSystemLogs_CL` | Platform-side; key column `Reason_s`. Observed values: `ContainerCreateFailure`, `ContainerTerminated`, `ProbeFailed`, `KEDAScaleTargetActivated`, `KEDAScaleTargetDeactivated`. |
| Raw container stdout (incl. JSON access log) | `ContainerAppConsoleLogs_CL` | `Log_s` column; filter by `ContainerAppName_s`. |

**Trap 1 — classic tables:** `requests`, `dependencies`, `traces`, `exceptions` return empty here. Always use the `App*` tables.

**Trap 2 — one component, two environments:** BOTH `api-testing` and `api-production` report into the same `TaDa-API` component with the same role name `techaid-api`. To separate environments in `App*` tables, filter on `AppRoleInstance` (it is the replica name, verified 2026-07-04):

```kusto
| where AppRoleInstance startswith "api-production"   // or "api-testing"
```

## 2. Tested scripts (in this skill's `scripts/` dir)

All are read-only, need `az login` done (subscription "CTA Nonprofit Azure Grant"), and were run successfully against live Azure on 2026-07-04 (**TESTED**). They set `MSYS_NO_PATHCONV=1` themselves (Git Bash on Windows mangles `/`-prefixed args otherwise). On this project's Windows machines, run them via the Bash tool / Git Bash, not PowerShell.

| Script | What it does | When |
|---|---|---|
| `check-health.sh <testing\|production>` | Cold-start-aware `/actuator/health` check with retry loop; prints verdict + running build version and full git commit from `/actuator/info`. Needs no az login. | First command in ANY "is it up / did my deploy land" question. |
| `query-requests.sh [--hours N]` | `AppRequests` summary: count, failures, avg + p95 duration per operation. | Performance questions; before/after evidence for any perf change. |
| `replica-events.sh [app] [--hours N]` | Chronological `ContainerAppSystemLogs_CL` events for `api-production` (default) or `api-testing`. | Outage/restart/cold-start investigations. |
| `verify-scale-rules.sh` | Shows current min/max replicas + scale rules for api-testing, api-production, superset-production and prints the expected steady state. | Suspected scale-rule drift; after any app recreate. |

Example: `bash .claude/skills/techaid-diagnostics-and-observability/scripts/check-health.sh testing`

## 3. App-side signals (what the code itself emits)

### Access log (one JSON line per HTTP request)

Emitted by `AccessLoggingFilter` (`src/main/kotlin/cta/app/config/RequestFilterConfig.kt`) on logger `cta.access`, JSON-encoded by Logstash encoder (`logback-spring.xml`), to stdout → lands in `ContainerAppConsoleLogs_CL.Log_s`. It runs at highest filter precedence, so it captures even requests the `UnknownPathFilter` 404s. Fields:

| Field | Meaning |
|---|---|
| `type` | Always `"access"` — grep anchor. |
| `method`, `path`, `query`, `full_path`, `protocol` | Request line parts (`full_path` = path?query). |
| `status` | Response status. |
| `request_content_length`, `response_content_length` | Byte sizes. |
| `duration_ms` | Server-side wall time. |
| `remote_ip` | Real client IP (Tomcat resolves `X-Forwarded-For` because `forward-headers-strategy: NATIVE`). |
| `user_agent`, `referer` | Headers. |
| `graphql_operation` | Only present on `/graphql` requests; set via request attribute by the GraphQL interceptor. |

### GraphQL telemetry (`GraphQlTelemetryConfig` in `src/main/kotlin/cta/app/config/GraphQlTelemetryInterceptor.kt`)

- Resolves the operation name (explicit `operationName`, else first named operation in the document, else first top-level field of shorthand queries) and renames the OpenTelemetry span → that is why `AppRequests.Name` reads `POST /graphql <operation>` instead of a useless flat `POST /graphql`.
- **Critical interpretation rule: GraphQL errors return HTTP 200.** Validation/coercion errors (e.g. "Expected a String input, but it was a 'Integer'") never appear as failed requests in `AppRequests` or the access log. They ARE logged as WARN lines *with the full input variables JSON* — search `AppTraces` for them (query below). If you're hunting a "failing" GraphQL call and see only 200s, this is why.
- `UserTelemetryFilter` tags spans with `enduser.id` (Auth0 email claim) → `AppRequests.UserAuthenticatedId`, so you can attribute traffic to a person.

### Actuator endpoints (exposed: health, info, metrics — `application.yml`)

- `GET /actuator/health` — anonymous callers get status only (`show-details: when-authorized`): `{"status":"UP","groups":["liveness","readiness"]}`.
- `GET /actuator/info` — build identity: `{"build":{"version":"2.1.0",...,"git":{"commit":"<full-sha>"}}}`. **This is the authoritative "what is actually running" check** after any deploy.
- `GET /actuator/metrics/<name>` — Micrometer metrics (also shipped to App Insights as customMetrics via the agent).

## 4. KQL cookbook (run via wrapper; every query here was executed successfully 2026-07-04)

Wrapper (Git Bash; substitute the workspace GUID from §1):

```bash
az monitor log-analytics query -w <workspace-guid> --analytics-query "<KQL>" -o table
```

### 4.1 Request overview (health of the API surface)

```kusto
AppRequests
| where TimeGenerated > ago(24h)
| summarize count() by Name, ResultCode
| order by count_ desc
```
**Healthy:** dominated by `POST /graphql <operation>` names with ResultCode 200; `GET /actuator/health` 200s (probes + ctawatch). **Worrying:** ResultCode 5xx rows; a flat `POST /graphql` name (operation-name enrichment broken — see `techaid-failure-archaeology` for the agent-version history); ResultCode 401 spikes on mutations (Auth0/token trouble).

### 4.2 Failures last 24h

```kusto
AppRequests
| where TimeGenerated > ago(24h) and Success == false
| project TimeGenerated, Name, ResultCode, DurationMs, AppRoleInstance, UserAuthenticatedId
| order by TimeGenerated desc
```
**Healthy:** empty or a handful of 404s from internet scanners hitting unknown paths. **Worrying:** 5xx on `/graphql`; anything with a `UserAuthenticatedId` (a real user hit it). Note `captureHttpServer4xxAsError: false` — 4xx are *not* marked failed, so also glance at 4.1's ResultCode column.

### 4.3 Slow operations (cold start vs genuinely slow)

```kusto
AppRequests
| where TimeGenerated > ago(24h)
| summarize requests=count(), avg_ms=round(avg(DurationMs),1), p95_ms=round(percentile(DurationMs,95),1) by Name
| order by p95_ms desc
```
**Interpretation:** a high **max** with normal p95 on the first request after idle = cold start (correlate with a `KEDAScaleTargetActivated` event, §4.5) — not an app regression. A high **p95** on one operation with warm traffic = real; take it to `techaid-investigation-methodology` with these numbers as the baseline. Reference points measured 2026-07-04: warm `POST /graphql buildInfo` avg ≈ 100ms; `findAllKits`-class list queries a few hundred ms.

### 4.4 Dependency failures (Postgres, Auth0, Google)

```kusto
AppDependencies
| where TimeGenerated > ago(24h)
| summarize calls=count(), failures=countif(Success==false), avg_ms=round(avg(DurationMs),1) by Target, DependencyType
| order by calls desc
```
**Healthy (observed 2026-07-04):** `postgresql` to `techaid-pg-svr.privatelink.postgres.database.azure.com | techaid_uat` / `| techaid_prod` with avg ~10ms, 0 failures; `HTTP techaid-auth.eu.auth0.com` ~130ms; occasional `gmail.googleapis.com` / `oauth2.googleapis.com`. **Worrying:** any `failures > 0` on postgresql (DB reachability — go to `techaid-database-operations`); Auth0 failures (logins/JWKS broken); Postgres avg_ms jumping 10× (missing index or DB pressure).

### 4.5 Replica lifecycle / outage forensics (the 2026-07-01 method)

```kusto
ContainerAppSystemLogs_CL
| where TimeGenerated > ago(48h)
| where ContainerAppName_s == 'api-production'
| project TimeGenerated, Reason_s, RevisionName_s, Log_s
| order by TimeGenerated asc
```
(or just `scripts/replica-events.sh api-production --hours 48`)

**Normal morning pattern (observed live 2026-07-04):** `KEDAScaleTargetActivated` at 08:00 London → ~40–90s of `ProbeFailed` ("Probe of StartUp failed") while Spring boots → silence (healthy) → `KEDAScaleTargetDeactivated` + `ContainerTerminated` (reason `ManuallyStopped`) at 20:00. **Do not page anyone over a startup ProbeFailed burst.**
**Incident pattern (2026-07-01 outage):** repeated `ContainerCreateFailure` ("special device overlay does not exist", mount exit 32) recurring for tens of minutes on the same revision = broken Azure host node, an Azure platform fault — the app never got to boot, no app-side fix exists. Remedy and the wedged-revision trap (a crash-looping revision can stick at 0 replicas and needs a fresh revision, not a restart) are in `techaid-deploy-and-operate` / `techaid-debugging-playbook`.

### 4.6 GraphQL input errors (the HTTP-200 errors from §3)

```kusto
AppTraces
| where TimeGenerated > ago(7d)
| where Message contains "GraphQL error on operation"
| project TimeGenerated, Message, AppRoleInstance
| order by TimeGenerated desc
```
The message includes the operation, error type, path, and the JSON-serialised input variables — this is exactly how the numeric-`lotId` bulk-import bug was pinned down (see `techaid-failure-archaeology`, lenient-scalar saga).

### 4.7 Console log fishing (startup errors, stack traces)

```kusto
ContainerAppConsoleLogs_CL
| where TimeGenerated > ago(24h)
| where ContainerAppName_s == 'api-testing'
| where Log_s contains "ERROR" or Log_s contains "Exception"
| project TimeGenerated, Log_s
| order by TimeGenerated desc
```
Use for boot failures (Flyway errors, missing env vars) that die before the App Insights agent reports anything. Flyway/schema failures at boot → `techaid-database-operations`.

### 4.8 KEDA activation history (cold-start frequency)

```kusto
ContainerAppSystemLogs_CL
| where TimeGenerated > ago(7d)
| where Reason_s startswith "KEDA"
| summarize count() by Reason_s, ContainerAppName_s
```
**Reference (7d window, 2026-07-04):** api-production ~39 activations/wk, api-testing ~7. Roughly 2×/weekday on prod (cron start + occasional mid-day reactivation) is normal. A large rise = something is repeatedly waking or killing replicas; get the per-event view via §4.5 before theorising. Spurious low-volume off-hours activations (~2–5/night, replica lives ~5 min) are a known, harmless background behaviour — do not chase them (documented in `techaid-failure-archaeology`).

## 5. Alert inventory (verified live 2026-07-04 unless noted)

| Alert | Where | Fires on | Severity |
|---|---|---|---|
| `api-production-availability` | scheduled-query rule, rg `tada-2026`, eval 5m / window 15m | `ContainerCreateFailure` count > 0 OR `ProbeFailed` count > 60, gated Mon–Fri 08:00–20:00 London (so startup bursts and off-hours noise don't page). *Firing criteria per the 2026-07-02 creation record — rule existence/severity/cadence az-verified, criteria body not re-fetched; re-verify: `az monitor scheduled-query show -g tada-2026 -n api-production-availability --query criteria`* | Sev1 |
| `techaid-pg-svr-down` | metric alert on `techaid-pg-svr` (rg `tada-2026`) | server unavailable | Sev0 |
| `techaid-pg-svr-storage-high` | " | storage > 80% | Sev1 |
| `techaid-pg-svr-cpu-high` | " | CPU > 90% | Sev2 |
| `techaid-pg-svr-connections-failed` | " | failed connections | Sev2 |

All route to action group `ctawatch-alerts` in rg `cta-monitor-rg` (emails Tony, Steve, security-reports).

**External watchdogs (rg `cta-monitor-rg`, resources verified 2026-07-04):** Azure Function `ctawatch-func-rkblk4nee3o4k` runs (a) a site-defacement monitor every 15 min over the public CTA sites and (b) as of 2026-07-03 an `api_health_monitor` timer checking production `/actuator/health` Mon–Fri 08:10–20:00 London; alert rule `ctawatch-site-alert` on `ctawatch-insights-rkblk4nee3o4k` (that one is classic-mode App Insights — the one place classic `traces` queries ARE correct). Source: github.com/CommunityTechaid/ctawatch (private repo).

**Portal dashboard:** an operations dashboard **TaDa-Operations** exists in rg `tada-2026`. Its Python generator is deliberately NOT in this repo — `.gitignore` line 49 ignores `.azure/` (commit d44b490). Don't hunt the repo for it; regenerate/edit via the Azure portal or ask for the generator.

## 6. Measurement discipline

- Before any perf/reliability change: capture the relevant §4 query output as the baseline. After: same query, same window shape. No numbers, no claim — the full protocol (hypothesis → predicted numbers → run) is in `techaid-investigation-methodology`.
- After any deploy: `scripts/check-health.sh` and confirm `git.commit` matches what you shipped (the promote flow in `techaid-prod-promotion-campaign` builds this in).
- Changing alert rules or adding telemetry code is a behaviour change: goes through `techaid-change-control` like any other change. Read-only queries need no sign-off; anything `az ... update/create/delete` on monitoring resources needs Tony's explicit go-ahead.

## Provenance and maintenance

Authored 2026-07-04 from repo source (`RequestFilterConfig.kt`, `GraphQlTelemetryInterceptor.kt`, `UserTelemetryFilter.kt`, `logback-spring.xml`, `application.yml`, `Dockerfile`, `applicationinsights.json`, `infra/apply-scale-rules.sh`, `.gitignore`) and live read-only `az` verification of the workspace, every §4 query, the alert rules, ctawatch resources, and all four scripts (all TESTED against live Azure).

Volatile facts — re-verify before relying on them:

```bash
# Workspace GUID (if queries 403/404):
az monitor log-analytics workspace show -g tada-2026 -n workspace-tada2026ubat --query customerId -o tsv
# Agent version + config still as documented:
grep AI_AGENT_VERSION Dockerfile && grep -A2 sampling applicationinsights.json
# Alert rules still present/enabled:
az monitor scheduled-query list -g tada-2026 -o table && az monitor metrics alert list -g tada-2026 -o table
# Scale rules drifted?
bash .claude/skills/techaid-diagnostics-and-observability/scripts/verify-scale-rules.sh
# Access-log field list changed?
grep -n '"type" to "access"' -A 16 src/main/kotlin/cta/app/config/RequestFilterConfig.kt
# ctawatch watchers still deployed:
az resource list -g cta-monitor-rg -o table
```
