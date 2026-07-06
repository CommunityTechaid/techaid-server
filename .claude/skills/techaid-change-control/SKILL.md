---
name: techaid-change-control
description: >-
  Load BEFORE committing, opening a PR, merging, releasing, promoting to
  production, editing docs of record, or making any change a dashboard user or
  the production database could see. Covers the techaid-server branch model
  (dev → UAT → manual promote → master), the five non-negotiable gates and the
  incidents behind them, conventional commits + release-please versioning, CI
  gates (ktlint + tests), change classification, and docs-of-record house
  style. If you are unsure whether a change needs permission, load this skill.
---

# TechAid Change Control

How changes are classified, gated, reviewed, and released in `techaid-server`.
This skill is the authority on *process*; `CLAUDE.md` at the repo root is the
behavioral contract it enforces — if they ever disagree, CLAUDE.md wins and
this skill needs fixing.

**Jargon used below (defined once):**

- **UAT** — User Acceptance Testing environment: the `api-testing` Azure
  Container App, auto-deployed from the `dev` branch, serving
  `https://api-testing.communitytechaid.org.uk`.
- **Production** — the `api-production` Azure Container App backing the live
  dashboard at `https://app.communitytechaid.org.uk`.
- **release-please** — Google's release automation bot. It reads conventional
  commit messages on `dev`, maintains an always-open "release PR" that bumps
  the version and CHANGELOG, and tags a release when that PR is merged.
- **Conventional commits** — commit message prefix grammar (`feat:`, `fix:`,
  `chore:`, `ci:`, …) that release-please parses to decide version bumps.
- **ktlint** — the Kotlin linter enforced in CI (`ktlintCheck`); it has an
  auto-fixer (`ktlintFormat`).
- **GHCR** — GitHub Container Registry, where CI publishes Docker images
  (`ghcr.io/communitytechaid/techaid-server`).

## When NOT to use this skill

- Executing the actual dev→master production promotion → use
  **techaid-prod-promotion-campaign** (it routes back here for the gates).
- Deploy mechanics, Azure Container App operations, rollback commands → use
  **techaid-deploy-and-operate**.
- Writing or reviewing a Flyway migration → use
  **techaid-database-operations** (this skill only tells you a migration
  *needs* that skill's gates).
- How to write/run tests → use **techaid-validation-and-qa**.
- Understanding *why* an invariant exists architecturally → use
  **techaid-architecture-contract**.
- History of a specific past incident → use **techaid-failure-archaeology**.

## 1. The branch model

```
feature branch ──PR──▶ dev ──(push: CI auto-deploys)──▶ UAT (api-testing)
                        │
                        │  human verifies on UAT
                        ▼
        promote.yml (manual dispatch + GitHub "production" approval)
                        │
                        ▼
              Production (api-production)
                        │
                        ▼
     merge dev → master  (ONLY with explicit permission — see gate 1)
```

- All work happens on `dev` or a feature branch. **PRs target `dev`**, never
  `master`.
- Push to `dev` runs CI (`.github/workflows/ci.yml`): `ktlintCheck` → `test` →
  Docker build pushed to GHCR as `:dev`, `:dev-<sha7>`, `:v<version>` → auto
  `az containerapp update` on `api-testing`. Branches matching
  `maintenance/**` build and push images but do **not** auto-deploy.
- Production deploy is `.github/workflows/promote.yml`: manual
  `workflow_dispatch`, gated by the GitHub `production` environment (required
  reviewer approval). By default it promotes **the image currently running on
  UAT** — so a `dev-<sha>` tag on production is normal and expected. An
  optional `image_tag` input pins/rolls back to a specific GHCR tag.
- Push to `master` builds and tags `:latest`, `:<sha7>`, `:v<version>` — a
  production-ready image — but does **not** auto-deploy.
- `master`'s intended meaning (maintainer's branch-model decision from the
  2026-05 migration, embedded here as of 2026-07-03): the source of truth for
  "what is currently in production". The sequence is *promote first, then
  merge dev into master*, not the other way round.

## 2. The five non-negotiables

Every one of these exists because of a real cost. Do not route around them;
if a task seems to require it, stop and ask the user.

| # | Rule | Why / incident behind it |
|---|------|--------------------------|
| 1 | **Never push or merge to `master` without explicit user permission in the current conversation.** Approval in a past session does not carry over. | `CLAUDE.md` §5. A master merge builds a production-ready image and signals "this is what prod runs"; an unintended one desynchronizes the branch model and risks an accidental production deploy. |
| 2 | **Red/green TDD on every backend change**: write a failing test that reproduces the bug or specifies the feature, make it pass, keep the test in the suite. | Norm set by the user on 2026-07-02 during the code-review remediation (PRs #48, merged 2026-07-02, and #49, merged 2026-07-03, each shipped with failing-first tests). The 2026-06 lenient-scalar revert (#42→#43) showed that changes without an assembly-level test can pass unit tests and still break the schema — see techaid-failure-archaeology. |
| 3 | **Dashboard audit before touching the GraphQL surface.** Any change to a schema type, filter shape, resolver visibility, or auth gate that the dashboard could consume requires checking actual usage in the `CommunityTechaid/techaid-dashboard` repo FIRST. | Precedent (2026-07-02, as of that date): the auth-gap fixes in PR #48 audited dashboard usage before restricting `referringOrganisationsPublic` to the exact public-typeahead filter shape (name+archived) — the audit is what made a breaking-looking change safe with zero dashboard changes. |
| 4 | **The production database is sacred.** No direct writes or DDL on `techaid_prod` without explicit user instruction, and confirm a point-in-time-restore-safe backup exists before any risky operation. | May/July 2026 (unverified in repo, date-stamped 2026-07-03): a database restore left every table owned by the admin role, which later crashed a Flyway migration on deploy ("must be owner of table kits") and needed one-off ownership surgery. Also: Azure Burstable-tier Postgres keeps NO backups of a deleted server — data on a deleted server is gone. |
| 5 | **Azure infrastructure mutations need explicit user sign-off per change** — any `az` command that changes Container Apps, Postgres, DNS, alerts, or scale rules, even in a session the user started. | Infra changes are outside CI, invisible in the repo diff, and some (scale rules, revisions, firewall rules) have bitten before by drifting silently. Read-only `az ... show/list` commands are always fine. |

Rules 2–5 are working norms established with the user (as of 2026-07-03);
rule 1 is written in `CLAUDE.md` §5. None of them is optional.

## 3. Classify your change, then apply its gate

| Change type | Examples | Gate before merge |
|---|---|---|
| Code-only | service logic, refactor, logging | Red/green test (rule 2) + CI green + PR to `dev` |
| GraphQL-surface | `.graphqls` files, resolver auth annotations, filter input shapes, anything in `src/main/kotlin/cta/app/graphql/` | All of the above **plus** dashboard audit (rule 3) |
| DB migration | new file under `src/main/resources/db/migration/` | All of code-only **plus** the migration discipline in **techaid-database-operations** (ownership, baseline, out-of-order rules) |
| Infra | `az` mutations, workflow files that deploy, scale rules, DNS | User sign-off per change (rule 5); workflow-file changes also ride a normal PR to `dev` |
| Production data | any write/DDL on `techaid_prod` | Explicit user instruction + backup check (rule 4) + **techaid-database-operations** |
| Docs-only | README, MAINTENANCE_PLAN, this skill library | PR to `dev`; no test required; never hand-edit `CHANGELOG.md` (§5) |
| Release / promote | merging the release PR, dev→master, prod deploy | Rule 1 + **techaid-prod-promotion-campaign** |

## 4. Commit and CI mechanics

Checklist for every commit/PR:

```bash
# 1. Format, then lint + full test suite (same gates CI runs)
./gradlew ktlintFormat
./gradlew ktlintCheck test
```

- Tests run against embedded Postgres (zonky) — no Docker needed. See
  **techaid-validation-and-qa**.
- Use conventional commit messages: `fix: …`, `feat: …`, `chore: …`, `ci: …`.
  release-please parses these — `feat` bumps minor, `fix` bumps patch. A
  malformed message means the change is missing from the CHANGELOG.
- Open the PR against `dev`:

```bash
gh pr create --base dev --title "fix: <what>" --body "<why + test evidence>"
```

- CI (`.github/workflows/ci.yml`) will run `ktlintCheck`, `test` (reports
  uploaded as the `test-reports` artifact), then build/push the image, then
  auto-deploy `dev` to UAT. A red `test` job blocks the image build entirely.

## 5. Versioning and releases (release-please)

Verified layout (as of 2026-07-03):

- `.github/workflows/release-please.yml` runs on every push to `dev`
  (`target-branch: dev`).
- `release-please-config.json`: release-type `simple`, changelog to
  `CHANGELOG.md`, tags like `v2.1.0`, and `build.gradle` listed as a generic
  extra-file — the bot rewrites the line marked
  `version = '2.1.0' // x-release-please-version` (build.gradle:64). **Never
  remove or hand-edit that marker comment.**
- `.release-please-manifest.json` records the current released version
  (`2.1.0`).

How it flows: conventional commits land on `dev` → the bot keeps an open
release PR (currently PR #46 "chore(dev): release 2.2.0", open since
2026-06-04) that accumulates the pending CHANGELOG and version bump → when a
human merges that PR, the bot tags `v<version>` and the CI build stamps
`:v<version>` images. Merging the release PR is a normal `dev` merge — it does
**not** deploy anything by itself.

Rules:

- **Never hand-edit `CHANGELOG.md`** — it is generated; hand edits are
  clobbered or cause merge conflicts in the release PR.
- Don't bump `version` in `build.gradle` manually; let the bot do it.
- Merging the release PR is routine (it targets `dev`), but do it
  deliberately — typically just before a production promotion so the version
  tag matches what ships.

## 6. Docs of record — what moves when behavior moves

| Doc | Role | House style / rule |
|---|---|---|
| `CLAUDE.md` | Behavioral contract for AI/dev sessions | Short, imperative, numbered sections. Changing it changes the rules of engagement — get user agreement first. |
| `README.md` | Onboarding: local run, Gmail creds, dev env | Keep commands runnable; if you change the build or local-run flow, update it in the same PR. |
| `MAINTENANCE_PLAN.md` | Living checklist for dependency/tech-debt tiers | Checkbox idiom (`[x]`), each group ends with a **Verify:** line. Tick items as they complete; add new debt as new checklist items. |
| `CHANGELOG.md` | Release history | Generated by release-please. Never hand-edit. |
| `RECIPE.md`, `POST-MIGRATION-CLEANUP.md`, `POTENTIAL-AZURE-TODOS.md` | Maintainer-local historical notes from the 2026-05 Azure migration | **Gitignored — these exist only on the maintainer's machine and are NOT docs of record** (absent from fresh clones; the rule of motion cannot apply to them). Their durable facts are embedded in this library. **New infra procedures go in the skill library** (`techaid-deploy-and-operate` / `techaid-database-operations`), which is in-repo. |
| `.claude/skills/*` | This library | Each skill ends with "Provenance and maintenance"; re-verify volatile facts before trusting them. Update the affected skill in the same PR when you change a process it documents. |

The rule of motion: **a PR that changes behavior updates the doc that
describes that behavior, in the same PR.** A doc that lags the code is worse
than no doc.

## 7. Surgical-diff discipline (summary)

`CLAUDE.md` §3 is the source of truth; in brief: touch only what the task
requires, don't reformat or "improve" adjacent code, match existing style,
remove only the orphans *your* change created, and mention (don't delete)
pre-existing dead code. Every changed line should trace to the user's request.

## Provenance and maintenance

Authored 2026-07-03 against `dev` @ 76b092f. Facts below drift; re-verify
before relying on them:

- Branch triggers, CI gates, tag scheme: `cat .github/workflows/ci.yml`
- Promote flow + environment gate: `cat .github/workflows/promote.yml`
- release-please target branch: `cat .github/workflows/release-please.yml`
- Version marker still present: `grep -n "x-release-please-version" build.gradle`
- Current released version: `cat .release-please-manifest.json`
- Open release PR: `gh pr list --state open --search "chore(dev): release"`
- Non-negotiable rule 1 wording: `sed -n '/## 5. Git/,/^---/p' CLAUDE.md`
- Master-as-prod-truth intent: maintainer's decision, embedded in §1 (not
  repo-verifiable; confirm with the maintainer if the branch model changes)
- Rules 2–5 are user-established norms (as of 2026-07-03), not repo-verifiable;
  confirm with the user if a task pressures against them.
