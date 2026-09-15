---
name: techaid-prod-promotion-campaign
description: Executable, decision-gated campaign for promoting techaid-server to production. Load whenever asked to promote UAT to production, deploy to prod, merge dev into master, roll back a production deploy, pin a production image, or verify that a prod deploy succeeded. Covers pre-promote UAT verification, the promote.yml workflow, post-promote measured verification, rollback, and dev→master bookkeeping.
---

# Production Promotion Campaign

The highest-stakes operation in this project. This skill is a numbered, gated runbook:
every phase ends with an expected observation; if you see something else, follow the
branch. Never judge a step "probably fine" — every gate is measured.

**When NOT to use this skill:** routine deploys to UAT (`api-testing`) happen
automatically on push to `dev` — see `techaid-deploy-and-operate`. Diagnosing a broken
prod app that you did NOT just deploy → `techaid-debugging-playbook`. Database-only
work → `techaid-database-operations`. Approval/gating rules in general →
`techaid-change-control`. Post-promote measurement tooling →
`techaid-diagnostics-and-observability`.

## The deploy model — read this first, it is commonly confused

- **`promote.yml`** ("Promote UAT → Production", manual `workflow_dispatch`, gated by the
  GitHub `production` environment approval) is the ONLY deploy mechanism for prod. With no
  input it deploys to `api-production` the image **currently running on `api-testing`
  (UAT)** — normally a `dev-<sha>` tag. With the optional `image_tag` input it pins prod
  to a specific GHCR tag (that is also the rollback mechanism).
- A **dev→master merge is NOT a deploy.** It is bookkeeping (master ≈ "what production
  runs") and it triggers a master image build (`:latest`, `:<sha>`, `:v<version>` tags)
  which nothing auto-deploys. A `dev-<sha>` image running on `api-production` is
  **normal and by design.**
- Pushing to master to "release" is therefore both forbidden (CLAUDE.md §5) and useless —
  it would not deploy anything.

Glossary: **UAT** = user acceptance testing environment = the `api-testing` Container App
serving https://api-testing.communitytechaid.org.uk. **Prod** = the `api-production`
Container App (resource group `tada-2026`) serving https://api.communitytechaid.org.uk
(custom hostname with managed cert, verified bound 2026-07-05; raw FQDN
`api-production.gentlegrass-111fe562.uksouth.azurecontainerapps.io` also works).
**Revision** = an immutable Container Apps deployment version; traffic points at one.
**GHCR** = GitHub Container Registry (`ghcr.io/communitytechaid/techaid-server`).

---

## PHASE 0 — Authorization gate (HARD STOP)

Explicit user permission **in the current conversation** is required for any production
promote or any merge/push to master (CLAUDE.md §5). Permission in a previous session,
a memory note, or "it was approved last week" does NOT count. If you do not have it:
stop, present the Phase 0 evidence below, and ask.

Enumerate exactly what would ship:

```bash
git fetch origin
git log origin/master..origin/dev --oneline        # the delta that goes live
gh pr list --state open                            # an open release-please PR? -> PHASE 0b
```

**Expected:** a finite, explainable commit list. You must be able to say what each commit
changes in production behavior. If any commit is a mystery → read its PR before
proceeding, do not promote code you cannot explain.

## PHASE 0b — Release-please precondition (BEFORE Phase 1, not after)

**Prod promotes a release commit.** Every prod promote on record landed on a
release-please `chore(dev): release X.Y.Z` commit:

```
a7debfe -> chore(dev): release 3.2.0 (#194)
8046593 -> chore(dev): release 2.6.0 (#160)
0a984d5 -> chore(dev): release 2.5.2 (#144)
```

So if `gh pr list` shows an open `chore(dev): release X.Y.Z` PR, **dev HEAD is ahead of the
last release commit** and UAT is running unreleased code. Merge that PR first, let CI build
it and UAT deploy it, then promote — that order is the whole point of the version string.

```bash
gh pr view <release-pr> --json files -q '[.files[].path]'
# EXPECTED: only build.gradle, CHANGELOG.md, .release-please-manifest.json
```

**If it touches anything else →** it is not a plain release PR; read it before merging.

**The held-CI trap.** A release-please PR arrives with its CI/CD run held at
`action_required`, which shows up as `mergeStateStatus: UNSTABLE` and as a suspiciously
short `gh pr checks` list (CodeQL only — the test job is not merely pending, it is absent).
Merging then merges *past* the test gate. Approve the run first:

```bash
gh run list --branch release-please--branches--dev -L 3          # find the pending run
gh api repos/CommunityTechaid/techaid-server/actions/runs/<id>/approve -X POST
```

**Re-promoting after a version-only merge needs no soak.** The release PR changes no
functional code, so the image is byte-identical in behaviour to what UAT already soaked.
Say that plainly rather than performing a fake waiting period.

**If you promote ahead of the release anyway** (urgent fix, deliberate call): it is
functionally fine — prod gets exactly dev HEAD — but `/actuator/info` will report the
PREVIOUS version while holding the next version's content, and a later dev→master merge
builds a `:v<old-version>` tag over content that is not that version. Record the decision
and correct it at the next opportunity.

**Worked example — 2026-09-15.** This step did not exist; #199/#201 were promoted with the
3.3.0 release PR (#200) still open, so prod reported 3.2.0 while running 3.3.0's content.
Caught by the maintainer, not by this runbook. Nothing broke; the fix was to merge #200 and
re-promote. The cause was this skill filing release-please under Phase 7 bookkeeping, which
reads as "afterwards" — hence this phase.

## PHASE 1 — UAT soak verification (all read-only)

Prod gets the image UAT runs, so prove UAT is (a) the code you think it is and
(b) healthy under real use.

**1a. UAT runs the intended commit:**

```bash
curl -s https://api-testing.communitytechaid.org.uk/actuator/info
git rev-parse origin/dev
```

**Expected:** `build.git.commit` equals `origin/dev` HEAD. Note the app scales to zero —
the first curl may take ~40–90 s (cold start); that is normal, wait for it.
**If commit differs →** UAT is stale; check the last CI run (`gh run list
--workflow=ci.yml -L 5`) and do not proceed until dev HEAD is deployed and verified.

**1b. Health:**

```bash
curl -s https://api-testing.communitytechaid.org.uk/actuator/health
```

**Expected (verified 2026-07-05):** `{"status":"UP","groups":["liveness","readiness"]}` —
status-only to anonymous callers (details are gated `when-authorized`).
**If DOWN or an error →** stop; debug UAT first (`techaid-debugging-playbook`).

**1c. Security probes** — for any security-relevant change in the delta, prove the gate
anonymously. Reference set (these gates are in current source: `write:organisations` on
`synchronizeCollectionDataForDeviceRequest` and `createReferringOrganisation`,
`isAuthenticated()` on `location`; the public typeahead stays anonymous):

```bash
# Gated mutation, anonymous → MUST be denied
curl -s -X POST https://api-testing.communitytechaid.org.uk/graphql \
  -H "Content-Type: application/json" \
  -d '{"query":"mutation { synchronizeCollectionDataForDeviceRequest(data:{id:1}) { id } }"}'
# EXPECTED: {"errors":[{"message":"Access Denied", ...}], "data":null}   (verified 2026-07-05)

# Public typeahead, anonymous → MUST still work
curl -s -X POST https://api-testing.communitytechaid.org.uk/graphql \
  -H "Content-Type: application/json" \
  -d '{"query":"query { referringOrganisationsPublic(where:{ name:{ _contains: \"a\" } }) { id name } }"}'
# EXPECTED: {"data":{"referringOrganisationsPublic":[ ... ]}} — a list, no errors
```

**If a gated call succeeds anonymously →** SECURITY STOP. Do not promote. File the gap,
route to `techaid-change-control`.

**1d. Soak time and error telemetry.** Check how long the delta has been on UAT and
whether it erred while there:

```bash
az containerapp revision list -n api-testing -g tada-2026 \
  --query "[].{name:name,created:properties.createdTime,active:properties.active,traffic:properties.trafficWeight}" -o table
```

Then query UAT failures over the soak window (KQL, tooling and interpretation in
`techaid-diagnostics-and-observability`). **Expected:** ≥1 business day of soak
(a recommended default, not an established project norm — the maintainer has set no
soak rule) and no new failure signatures attributable to the delta. **If soak
< 1 business day or failures are unexplained →** report to the user; promoting is
their call, made on that evidence.

## PHASE 2 — Paired-deploy decision

| Delta condition | Action |
|---|---|
| Changes the GraphQL surface the dashboard consumes (types, fields, args, auth on them) | Dashboard audit required (`techaid-change-control`); coordinate a techaid-dashboard promote |
| Server-only (internal, infra, migrations, telemetry) | Proceed, server alone |
| Unsure which | STOP and ask the user |

To check: grep the delta for changes under `src/main/resources/graphql/` and for
`@PreAuthorize` edits, then confirm dashboard usage of the touched operations.

## PHASE 3 — Delta-specific risk register

Build one from the Phase 0 commit list: for each risky commit write *what could break in
prod that could not break on UAT* (prod-only data, prod-only integrations, first-boot
migrations) and *how you will observe it* in Phase 5.

**Worked example — the 3.0.0 promote, 2026-08-19.** Kept as an illustration of a good
register, not as a live one. It is deliberately a *past* promote so it cannot go stale;
write your own register for the delta in front of you.

That delta dropped the `coordinates` columns from `donors` and `kits`. The register was:

- **The GDPR scrub function reads the dropped columns.** A plpgsql body resolves column
  names at execution, so a stale `gdpr.performgdprcleanup` would NOT fail the deploy — it
  would fail the following Friday 18:00 retention run, silently, in the only job now
  performing retention. *Mitigation:* replace the function BEFORE the promote drops the
  columns (it runs correctly against either schema), gate on `mentions_coordinates = 0`
  and an md5 matching UAT, then prove it still executes as `api_prod` — `CREATE OR
  REPLACE` can quietly break that. All three gates passed.
- **The old image does not fail at boot on a missing column.** Prod runs `ddl-auto=none`,
  so a rollback to the pre-drop image fails at *runtime*, on every donor and kit read —
  presenting as a total outage of the dashboard's main screens, with nothing wrong in
  `/actuator/health`. The tell is `column k1_0.coordinates does not exist` in container
  logs. *Mitigation:* a two-step rollback (re-pin the image, then re-add the columns
  empty), rehearsed on UAT beforehand.
- **Recovery for a dropped column is PITR only**, and PITR restores a whole server.
  *Mitigation:* a logical `pg_dump -Fc -n public` taken before anything else; check the
  file size, because `api_prod` cannot read the `gdpr` schema and an unrestricted dump
  dies leaving a 0-byte file.
- **The cutover window.** Both the "stop writing the column" and "drop the column"
  deploys shipped in one image, so the old revision was briefly alive while the new
  revision's migration dropped the columns. *Observation:* grep the OLD revision's logs
  for `coordinates does not exist` / `SQLGrammarException` afterwards. It came back
  clean, but the window was real and a two-deploy split would have removed it.

**One lesson from that promote's gates.** Its runbook required `location(address:)` to
still resolve. It returned `null` — but it also returned null on prod *before* the
promote, and on UAT, because the Google key is referer-restricted (issue #186). A gate
you have never seen green is not a gate; **capture the pre-promote value of every check
you intend to gate on**, or you cannot tell a regression from a pre-existing fault.

## PHASE 4 — Execute

**4a. MANDATORY: record the rollback anchor before touching anything:**

```bash
az containerapp show -n api-production -g tada-2026 \
  --query "properties.template.containers[0].image" -o tsv   # SAVE this value
az containerapp revision list -n api-production -g tada-2026 \
  --query "[?properties.active].{name:name,image:properties.template.containers[0].image}" -o table
```

**4b. Trigger the workflow** (leave input blank to promote current UAT image):

```bash
gh workflow run promote.yml            # promote what UAT runs (normal path)
# or pin a tag:  gh workflow run promote.yml -f image_tag=dev-1a2b3c4
gh run list --workflow=promote.yml -L 1     # get the run id
gh run watch <run-id>
```

UI path if preferred: GitHub → Actions → "Promote UAT → Production" → Run workflow.

**Expected:** the run pauses at the `production` environment gate ("waiting" status). A
required reviewer approves it in the GitHub UI (run page → *Review deployments* →
approve). Then the job resolves the image and runs `az containerapp update` — completes
in a few minutes.

**4c. Watch the revision come up:**

```bash
az containerapp revision list -n api-production -g tada-2026 \
  --query "[].{name:name,created:properties.createdTime,health:properties.healthState,running:properties.runningState,traffic:properties.trafficWeight}" -o table
```

**Expected:** a new revision appears, reaches `Healthy`, and takes traffic within a few
minutes. First request after scale-from-zero takes ~40–90 s.

| If you see instead | Branch |
|---|---|
| Revision stuck `Provisioning` > 10 min | Check system logs (`ContainerAppSystemLogs_CL`, see `techaid-diagnostics-and-observability`); a `ContainerCreateFailure` storm can be a broken Azure host node (it has happened: 2026-07-01 outage) — a stop/start reschedules onto a healthy node |
| Revision `Unhealthy` / crash-looping | **DO NOT restart it** — a crash-looped revision wedges at 0 replicas and restart will not respawn it (observed 2026-07-02). Read container logs for the crash cause; then either fix-forward with a fresh revision or roll back (Phase 6). Note traffic silently stays on the previous healthy revision meanwhile — users may see the OLD version, not an outage |
| Traffic still 100% on old revision | Normal until the new one is Healthy; only investigate if it persists after health is green |
| Workflow fails before `az containerapp update` | Read `gh run view <id> --log-failed`; nothing was deployed; safe to re-run after fixing |

## PHASE 5 — Post-promote verification (measured, timeboxed)

Run immediately; keep a telemetry watch through the first business-hours window.

**5a. Identity — prod runs the promoted commit:**

```bash
curl -s https://api.communitytechaid.org.uk/actuator/info
```

**Expected:** `build.git.commit` equals the sha you promoted (UAT's commit from Phase 1a).
**If unchanged after ~5 min + one 40 s cold start →** traffic is still on the old
revision; go back to the Phase 4c branch table.

**5b. Health:** `curl -s https://api.communitytechaid.org.uk/actuator/health` →
**expected** `{"status":"UP","groups":["liveness","readiness"]}` (status-only).

**5c. Security probes:** re-run the exact Phase 1c curl pair against
`https://api.communitytechaid.org.uk/graphql`. Expected outputs identical: gated
mutation → `Access Denied`; public typeahead → data, no errors.

**5d. Telemetry watch:** over the first business-hours window, check `AppRequests`
failure counts and `ContainerAppSystemLogs_CL` restart/probe events against the previous
week's baseline (queries in `techaid-diagnostics-and-observability`).

**5e. Delta-specific watches** from your Phase 3 register. For the calendar-sync example:
query `AppRequests` for `synchronizeCollectionDataForDeviceRequest` after the first sync
runs — success = 2xx requests present, zero `Access Denied` traces for it. If Access
Denied appears → the Apps Script's Auth0 grant lacks `write:organisations`; fix the grant
(or roll back), do not weaken the mutation's gate.

**SUCCESS =** all of: 5a commit matches; 5b UP; 5c both probes correct; 5d no new failure
signature vs baseline through the watch window; 5e every register item observed-good.
Anything less is not success — report it plainly.

## PHASE 6 — Rollback

Rollback is `promote.yml` with a pinned tag — the same gated path, so it also needs
Phase 0 authorization (in an active incident, ask the user in the same breath you report):

```bash
gh workflow run promote.yml -f image_tag=<tag-part-of-Phase-4a-anchor>   # e.g. dev-3e076d8
```

Takes effect like any promote: minutes plus the environment approval. Then re-run
Phase 5a–5c against the anchor commit.

**DB caveat:** Flyway migrations are NOT reversed by rolling back the image. If the bad
deploy ran a migration, confirm the old code is compatible with the new schema before
rolling back (additive `IF NOT EXISTS` changes usually are; drops/renames are not) —
`techaid-database-operations`.

## PHASE 7 — Bookkeeping

- **`master` fast-forwards itself.** Since 2026-09-15 `promote.yml`'s last step pushes the
  deployed commit to `master`, so there is normally nothing to do here — check the step
  passed and move on. It is a plain push: it succeeds only if `master` is strictly behind,
  which is what keeps `master` from ever diverging.
  - **Step failed?** Almost always a rollback: prod went backwards, so the deployed commit
    is not a descendant of `master` and the push is correctly refused. The deploy itself
    already succeeded. Decide deliberately what the record should say — do not force-push.
  - A **manual** dev→master merge is still occasionally wanted (e.g. to record docs
    commits that never shipped). That needs explicit user permission, same CLAUDE.md §5
    gate, and a PR for it cannot be approved by its own author.
- Release-please is **not** a Phase 7 step — it is a Phase 0b precondition. If you reach
  here with an unmerged release PR, the promote happened out of order; see 0b for what
  that costs and how to correct it. (This bullet used to imply the opposite, which is
  what caused the 2026-09-15 out-of-order promote.)
- Update any doc-of-record the delta made stale, and retire satisfied risk-register
  entries in this skill (see Provenance).

## Fenced-off wrong paths

- **Never push/merge to master to deploy** — forbidden without permission, and it does
  not deploy anything anyway.
- **Never restart a wedged (crash-looping, 0-replica) revision** — deploy a fresh
  revision or roll back.
- **Never judge a scale-to-zero app in under ~90 s** — cold start (~40–90 s) looks like an outage.
- **Never flip `DDL_AUTO` (or any config) to get past a schema-validate failure on
  prod** — that failure is the safety net working; fix the schema/migration
  (`techaid-database-operations`).
- **Never skip or shortcut Phase 0**, including for rollbacks.
- **Never promote with an open release-please PR** without consciously accepting a wrong
  version string in prod — see Phase 0b.
- **Never weaken an auth gate to make an external caller work** — fix the caller's
  credentials/grant.

## Provenance and maintenance

Authored 2026-07-03/05 against repo state at commit 76b092f. Workflow mechanics from
`.github/workflows/promote.yml` and `ci.yml`; auth gates and probe shapes from
`src/main/kotlin/cta/app/graphql/**` and `src/main/resources/graphql/*.graphqls`; live
endpoints, hostname binding, and probe outputs verified 2026-07-05 with read-only
az/curl. Revised 2026-08-19 after the 3.0.0 promote: the Phase 3 block was a live register
for the long-since-completed PR #48/#49 delta and had rotted, so it is now a dated worked
example from a past promote — keep it that way rather than storing a pending register here,
which is what made it stale. Revised 2026-09-15 after the 3.3.0 promote: added PHASE 0b because release-please sat under
Phase 7 "Bookkeeping" and read as an after-the-fact step, so a promote shipped ahead of its
release commit; the prod-commit evidence in 0b was taken with
`git log -1 --format=%s <prod-commit>` over the three previous promotes.
Operational lore (wedged revision, 2026-07-01 node outage, ownership pre-clear) is from
incident history, not re-derivable from the repo — see `techaid-failure-archaeology`.

Re-verify before trusting:
- Deploy mechanics: `cat .github/workflows/promote.yml` (image resolution + target app)
- Prod hostname: `az containerapp hostname list -n api-production -g tada-2026 -o table`
- Prod identity/health: `curl -s https://api.communitytechaid.org.uk/actuator/info` and `/actuator/health`
- Auth gates still present: `grep -rn "PreAuthorize" src/main/kotlin/cta/app/graphql/`
- Image/tag scheme: `grep -n "tags=" .github/workflows/ci.yml`
