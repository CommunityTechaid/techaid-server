---
name: techaid-deploy-and-operate
description: >
  How techaid-server is built, shipped, and operated: the CI/CD pipeline (GitHub Actions →
  GHCR image → Azure Container Apps), image tag conventions, UAT auto-deploy, what runs where
  (api-testing vs api-production), KEDA cron scaling and cold starts, day-2 az commands
  (inspect image/revision, logs, restart, the wedged-revision trap), how to verify a deploy
  landed, external monitoring, and which repo files are Dokku/Kubernetes-era legacy. Load this
  for anything about CI, Docker images, deploys, UAT, revisions, replicas, scaling, container
  logs, actuator endpoints, or Azure Container Apps operations for this project.
---

# TechAid Server — Deploy and Operate

Reference for how code gets from a `dev` commit to production and how to operate the running
system. Written 2026-07-03; live-verified facts are marked. Commands are Bash (`az` CLI).

**When NOT to use this skill:**
- Executing a production promotion (dev → master → prod) end-to-end → use
  **techaid-prod-promotion-campaign** (the gated runbook; this skill only explains the machinery).
- Approval rules for what you may change and when → **techaid-change-control**. Every command
  below marked **[sign-off]** mutates shared infrastructure and requires explicit maintainer
  go-ahead first.
- Querying telemetry / App Insights / Log Analytics → **techaid-diagnostics-and-observability**.
- Postgres server operations, Flyway, backups → **techaid-database-operations**.
- Building or running the app locally → **techaid-build-and-env**.

## Glossary (terms used below)

| Term | Meaning here |
|------|--------------|
| GHCR | GitHub Container Registry — `ghcr.io/communitytechaid/techaid-server`; packages are public, no pull credentials needed |
| Container App | Azure Container Apps — managed serverless containers; the unit that runs the API |
| Revision | An immutable snapshot of a Container App's template (image + env + scale). Every `az containerapp update` creates a new one; traffic points at one revision |
| KEDA cron scale rule | Scheduler that sets desired replicas by time window; here it keeps 1 replica warm in business hours, 0 otherwise |
| workflow_dispatch | A GitHub Actions workflow you trigger manually from the Actions tab |
| UAT | User acceptance testing environment = the `api-testing` Container App |
| Cold start | First request after scale-to-zero; expect **~40–90 s** before a response (JVM boot; canonical figure: **techaid-diagnostics-and-observability**). Do not judge an app "down" until you have waited that long (as of 2026-07-03) |

## 1. Pipeline anatomy

Three workflows in `.github/workflows/` (verified 2026-07-03):

```
push to dev / master / maintenance/**
        │
        ▼
  ci.yml: test job        ./gradlew ktlintCheck  +  ./gradlew test   (zonky embedded Postgres, no Docker)
        │ pass
        ▼
  ci.yml: build job       docker build → push to GHCR with tag matrix below
        │ (dev branch only)
        ▼
  ci.yml: deploy-testing  az containerapp update -n api-testing -g tada-2026
                          --image ghcr.io/communitytechaid/techaid-server:dev-<sha7>
        │ human verifies UAT
        ▼
  promote.yml (MANUAL)    workflow_dispatch + GitHub `production` environment approval
                          → deploys the image currently running on api-testing to api-production
```

**Tag matrix** (from `ci.yml`; `<sha7>` = first 7 chars of commit SHA, `<version>` = the
`version = '…'` line in `build.gradle`, maintained by release-please):

| Branch pushed | Tags published |
|---------------|----------------|
| `dev`, `maintenance/**` | `:dev-<sha7>`, `:dev`, `:v<version>` |
| `master` | `:<sha7>`, `:latest`, `:v<version>` |

Key facts:

- **UAT deploys are automatic** on every push to `dev` (merged PRs included). The SHA-pinned
  tag (`dev-<sha7>`) is used precisely so each push forces a new revision.
- **Production deploys are manual only**: `promote.yml` (Actions → "Promote UAT → Production"
  → Run workflow). With no input it reads the image currently on `api-testing` and deploys
  exactly that to `api-production`. The optional `image_tag` input pins any published tag
  instead — **this is the rollback mechanism** (e.g. `image_tag: dev-1a2b3c4` or `v2.1.0`).
- Consequence, **by design**: production normally runs a `dev-<sha7>` tag. Live-verified
  2026-07-03: `api-production` ran `ghcr.io/communitytechaid/techaid-server:dev-3e076d8`.
  Do NOT "fix" this by pushing `:latest` to prod; `:latest` (master) images exist but the
  promote flow deliberately ships the exact bits verified on UAT.
- The `production` GitHub environment requires a named reviewer's approval before the promote
  job runs. That approval gate is part of change control — never route around it.
- `release-please.yml` runs on pushes to `dev` and maintains a release PR that bumps
  `version` in `build.gradle` (marker comment `// x-release-please-version`) and
  `CHANGELOG.md` from Conventional Commit messages. Merging that PR creates the `v<x.y.z>`
  tag. See **techaid-change-control** for commit-message conventions.
- The image embeds the git SHA: `ci.yml` passes `GIT_COMMIT` as a build arg → `Dockerfile`
  runs Gradle with `-PgitCommit=…` → `build.gradle` `springBoot.buildInfo` publishes it →
  readable at `/actuator/info` (see §5).

## 2. What runs where (topology)

Live-verified 2026-07-03 unless noted. Azure subscription `b7981f4a-b5b8-482a-ab3d-fa3b14b8006a`
("CTA Nonprofit Azure Grant"), region UK South.

| | `api-testing` (UAT) | `api-production` |
|---|---|---|
| Resource group | `tada-2026` | `tada-2026` |
| Custom domain | `https://api-testing.communitytechaid.org.uk` | `https://api.communitytechaid.org.uk` |
| Azure FQDN | `api-testing.gentlegrass-111fe562.uksouth.azurecontainerapps.io` | `api-production.gentlegrass-111fe562.uksouth.azurecontainerapps.io` |
| Ingress target port | 8080 | 8080 |
| Deployed by | `ci.yml` auto, on push to `dev` | `promote.yml`, manual + approval |
| Spring profile | `testing` | `production` |
| Scale (replicas) | 0–1, no cron rule (default HTTP scaler) | 0–1, KEDA cron rule `business-hours` |
| Database | `techaid_uat` on `techaid-pg-svr` | `techaid_prod` on `techaid-pg-svr` (private link) |

- Both apps sit behind **Cloudflare** (CNAMEs on `communitytechaid.org.uk`); the dashboard at
  `app.communitytechaid.org.uk` calls the API cross-origin.
- The production Container Apps environment (`production-env`) is **VNet-integrated** so it can
  reach Postgres over Azure Private Link (`techaid-pg-svr.privatelink.postgres.database.azure.com`)
  without public firewall exposure. Rationale embedded here (as of 2026-07-03): the private
  endpoint keeps Postgres off the public internet for production traffic; details in
  **techaid-database-operations**.
- ⚠️ A `RECIPE.md` exists on the maintainer's machine only (**gitignored — not in the repo,
  absent from fresh clones**) documenting the May-2026 migration as planned. It is partially
  stale (old resource group, port 5000, pre-`promote.yml` deploy flow, "pending DNS cutover"
  — the cutover completed; custom domain live-verified bound). Its durable facts are embedded
  in this skill and **techaid-database-operations**; trust `ci.yml`/`promote.yml` and live `az`
  output. If recreating resources from scratch, ask the maintainer for that file.

## 3. Scaling and cold starts

`api-production` scales on a KEDA cron rule named `business-hours`: **1 replica Mon–Fri
08:00–20:00 Europe/London, 0 replicas otherwise** (timezone field handles GMT/BST). Outside
that window the first request pays the ~40–90 s cold start.

- `infra/apply-scale-rules.sh` re-applies the rules via `az rest` PATCH. **Scale config is not
  in any IaC file** — if a production app is destroyed and recreated, run this script
  **[sign-off]** or the app will have no cron rule. It covers both `api-production`
  (08:00–20:00) and `superset-production` (09:00–17:00). Note its `MSYS_NO_PATHCONV=1` prefix:
  required in Git Bash on Windows so `/subscriptions/...` URLs aren't mangled into file paths.
- `api-testing` has **no cron rule** (live-verified): plain 0–1 replicas, waking on HTTP
  traffic. UAT requests after idle always pay the cold start — wait before declaring it broken.
  Since 2026-07-22 it idles for **15 minutes, not 5**, before scaling to zero
  (`cooldownPeriod: 900`, against Azure's 300 s default) so staff testing UAT stop hitting the
  cold start mid-session. Two things to know: like the cron rules this is **not in any IaC
  file**, so it silently reverts to 300 s if the app is recreated; and
  `infra/apply-scale-rules.sh` cannot set it, because its api-version `2024-03-01` rejects the
  field with `Unknown properties cooldownPeriod in ContainerAppScale are not supported` —
  use `2025-01-01` or later:

  ```bash
  MSYS_NO_PATHCONV=1 az rest --method PATCH \
    --url "https://management.azure.com/subscriptions/<sub-id>/resourceGroups/tada-2026/providers/Microsoft.App/containerApps/api-testing?api-version=2025-01-01" \
    --body '{"properties":{"template":{"scale":{"minReplicas":0,"maxReplicas":1,"cooldownPeriod":900,"rules":null}}}}'
  ```

  `scripts/verify-scale-rules.sh` in **techaid-diagnostics-and-observability** reports
  `cooldownPeriod` for all three apps, so this drift surfaces without anyone remembering to
  look for it.
- Known noise: occasional off-hours KEDA activations on production (a replica runs ~5 min then
  deactivates, a few times per night, empty trigger reason). Observed for weeks pre-2026-07;
  harmless. Don't burn time investigating unless behaviour changes materially. See
  **techaid-failure-archaeology**.

Check current scale config:

```bash
az containerapp show -n api-production -g tada-2026 \
  --query "properties.template.scale" -o json
```

## 4. Day-2 operations cookbook

Read-only commands: run freely. **[sign-off]** = mutates shared infra; requires explicit
maintainer approval per **techaid-change-control** before running.

**What image/commit is deployed right now?**

```bash
az containerapp show -n api-production -g tada-2026 \
  --query "properties.template.containers[0].image" -o tsv
az containerapp show -n api-testing -g tada-2026 \
  --query "properties.template.containers[0].image" -o tsv
```

**List revisions and their health** (active revision, replica count, running state):

```bash
az containerapp revision list -n api-production -g tada-2026 \
  --query "[].{name:name, active:properties.active, replicas:properties.replicas, state:properties.runningState, created:properties.createdTime}" -o table
```

**Console logs** (application stdout — the Spring Boot / access-log JSON):

```bash
# Recent lines
az containerapp logs show -n api-production -g tada-2026 --type console --tail 100
# Follow live
az containerapp logs show -n api-production -g tada-2026 --type console --follow
# Platform/system events (scheduling, probes, scaling) instead of app output
az containerapp logs show -n api-production -g tada-2026 --type system --tail 50
```

For historical log queries (Log Analytics tables `ContainerAppConsoleLogs_CL` /
`ContainerAppSystemLogs_CL`) → **techaid-diagnostics-and-observability**.

**Restart** — there is no "restart" verb. Options, in order of preference:

```bash
# Restart the current revision's replica(s)  [sign-off]
az containerapp revision restart -n api-production -g tada-2026 --revision <revision-name>

# Force a NEW revision with the same image (the reliable fix — see trap below)  [sign-off]
az containerapp update -n api-production -g tada-2026 --revision-suffix manual-$(date +%s)
```

**⚠️ The wedged-revision trap** (bit us during the 2026-07-02 UAT deploy — see
**techaid-failure-archaeology**): if a revision's container crash-loops, the revision ends up
`Unhealthy` with 0 replicas, and `revision restart` will NOT respawn it. Meanwhile ingress
**silently falls back to the previous healthy revision** — so the app "works" but serves old
code, and the deployed-image query above disagrees with what's actually responding. Recovery:
fix the crash cause, then deploy a fresh revision (`az containerapp update` with the image
and/or a `--revision-suffix`). Never assume a deploy landed because the update command
succeeded — verify (§5).

**⚠️ The broken-node trap** (2026-07-01 production outage): repeated `ContainerCreateFailure`
in system logs with an overlay-mount error means the pod is stuck on a broken Azure host node;
Kubernetes retries on the same node indefinitely. A stop/start reschedules onto a healthy
node **[sign-off]**: `az containerapp stop …` then `az containerapp start …`. Full story:
**techaid-failure-archaeology**; triage flow: **techaid-debugging-playbook**.

## 5. Verifying a deploy landed

Run all three; a green update command alone proves nothing (wedged-revision trap).

```bash
# 1. Health — anonymous callers get overall status ONLY (details hidden by design;
#    an empty-looking body is not a bug). Remember the ~40–90 s cold-start wait.
curl -s https://api-testing.communitytechaid.org.uk/actuator/health
# expect: {"status":"UP"}

# 2. Deployed commit — buildInfo git.commit must equal the SHA you expect
curl -s https://api-testing.communitytechaid.org.uk/actuator/info
# expect: {"build":{ ... "git.commit":"<full-sha-of-the-commit-CI-built>" ...}}

# 3. Azure agrees — image tag matches, latest revision is Healthy/Running
az containerapp show -n api-testing -g tada-2026 \
  --query "properties.template.containers[0].image" -o tsv
az containerapp revision list -n api-testing -g tada-2026 \
  --query "[?properties.active].{name:name, state:properties.runningState, replicas:properties.replicas}" -o table
```

For production substitute `api-production` / `https://api.communitytechaid.org.uk`. A
production deploy additionally requires the functional gates in
**techaid-prod-promotion-campaign** — actuator checks alone are not acceptance evidence.

## 6. External watchers (not in this repo)

As of 2026-07-03; both live outside this codebase, so re-verify with `az` rather than trusting
this text:

| Watcher | What it does | Verify with |
|---|---|---|
| Alert rule `api-production-availability` (RG `tada-2026`) | Scheduled Log Analytics query; fires Sev1 email if `ContainerCreateFailure` count > 0 or `ProbeFailed` count > 60, Mon–Fri 08:00–20:00 London | `az monitor scheduled-query list -g tada-2026 -o table` (live-verified present + enabled 2026-07-03) |
| ctawatch Azure Function (`cta-monitor-rg`) | Timer-based check of prod `/actuator/health` Mon–Fri 08:10–20:00 London; source at `github.com/CommunityTechaid/ctawatch` (private repo) | `az functionapp list -g cta-monitor-rg -o table` |

If you change health-endpoint behaviour, actuator exposure, or business-hours scaling, these
watchers are downstream consumers — check them before and after.

## 7. Cost posture

Steady state is roughly **£70/month** for the whole Azure estate (as of 2026-06; not derivable
from the repo — re-baseline in Azure Cost Analysis before relying on it). This is a
donation-funded charity: cost is a real design constraint. Scale-to-zero outside business
hours and the Burstable (B1ms) Postgres tier are deliberate. Any fix that raises the floor
(e.g. `--min-replicas 1` around the clock, a bigger Postgres SKU) is a cost decision, not just
a technical one → route through **techaid-change-control**.

## 8. Legacy inventory (do not build on these)

The project migrated GKE/Helm → Dokku VM → Azure Container Apps (May 2026). Residue remains.
Verified 2026-07-03 that no GitHub workflow references any file below; the only live consumers
are noted. Their removal is roadmap item A6 (**techaid-roadmap-and-frontier**) — mention,
don't delete (per CLAUDE.md surgical-changes rule).

| Path | Era | Still consumed? |
|---|---|---|
| `charts/` | GKE/Helm (pre-Dokku; `eu.gcr.io` images) | No |
| `manifests/` | Kubernetes (header says unused) | No |
| `Procfile` | Dokku process declaration | Copied into the image by `Dockerfile` but never executed (CMD supersedes it). Cosmetic only |
| `start.sh` | Dokku/dev hot-reload helper | No |
| `scripts/deploy-api.sh`, `scripts/poll-deploy-api.sh` | Dokku VM deploy/poll scripts (`dokku git:from-image`) | No — do NOT use; deploys go through `ci.yml`/`promote.yml` |
| `rollback-prod.sh`, `migrate-prod-db.sh`, `cf-proxy-on.sh`, `app-cname-validate.sh` | One-shot May-2026 cutover tooling (**gitignored, maintainer-local — absent from fresh clones**; rollback flips CNAMEs back to the now-retired Dokku VM) | No — historical record only; rollback today = `promote.yml` with a pinned `image_tag` |
| `docker/proxy.conf` | nginx proxy for local `docker-compose` | Only by local dev compose → **techaid-build-and-env** |
| `Dockerfile.dev`, `Dockerfile.local`, `docker-compose.yml` | Local development (current) | Yes, locally → **techaid-build-and-env** |

## Provenance and maintenance

Authored 2026-07-03 against commit `76b092f` (branch `dev`). Sources: `.github/workflows/`
(read in full), `infra/apply-scale-rules.sh`, `scripts/*.sh`, root shell scripts, `Dockerfile`,
`build.gradle`, the maintainer's gitignored local migration notes (facts embedded above), plus
live read-only `az` checks on 2026-07-03 (both Container Apps' image/ports/domains/scale,
scheduled-query alert). Cost figure and ctawatch schedule are external facts, dated above.

Re-verify before trusting volatile facts:

- Pipeline/tags: `cat .github/workflows/ci.yml .github/workflows/promote.yml`
- Deployed images: `az containerapp show -n api-production -g tada-2026 --query "properties.template.containers[0].image" -o tsv` (and `api-testing`)
- Scale rules: `az containerapp show -n api-production -g tada-2026 --query "properties.template.scale" -o json`
- Custom domains/ports: `az containerapp show -n api-production -g tada-2026 --query "properties.configuration.ingress.{fqdn:fqdn,port:targetPort,domains:customDomains[].name}" -o json`
- Alert rule: `az monitor scheduled-query list -g tada-2026 -o table`
- Version/changelog automation: `grep -n "x-release-please-version" build.gradle`
- Legacy files still unreferenced by CI: `grep -rl "deploy-api\|cutover-prod\|Procfile\|charts/" .github/ || echo "none"`
