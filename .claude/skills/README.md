# techaid-server skill library

Fourteen skills that carry this project's operational knowledge — how to build, change,
debug, deploy, and advance techaid-server. AI sessions load them automatically by
description; humans: start with the routing table below. Each skill ends with a
**Provenance and maintenance** section — re-verify volatile facts there before relying
on them.

## Your situation → load this skill

| Situation | Skill |
|---|---|
| Something is broken / investigating an error | `techaid-debugging-playbook` |
| Need numbers: telemetry, logs, KQL, alerts, perf | `techaid-diagnostics-and-observability` |
| Declaring a root cause / running an investigation | `techaid-investigation-methodology` |
| "Has this happened before?" — past incidents/reverts | `techaid-failure-archaeology` |
| Writing/changing code or tests; what evidence is needed | `techaid-validation-and-qa` |
| Designing a change; will it break an invariant? | `techaid-architecture-contract` |
| What a domain field/enum/scope means; business logic | `techaid-domain-reference` |
| Env vars, profiles, yml config, credentials wiring | `techaid-config-and-flags` |
| Flyway migrations, Postgres access, backups, DDL | `techaid-database-operations` |
| CI, images, UAT deploys, revisions, scaling, day-2 ops | `techaid-deploy-and-operate` |
| Promoting to production / rollback / master merge | `techaid-prod-promotion-campaign` |
| Committing, PRs, releasing, approval gates | `techaid-change-control` |
| Fresh machine setup, build failures, running locally | `techaid-build-and-env` |
| "What should we work on next?" — vetted open candidates | `techaid-roadmap-and-frontier` |

## Inventory

| Skill | One line |
|---|---|
| `techaid-change-control` | Branch model, the five non-negotiable gates and their incidents, release-please, docs of record |
| `techaid-debugging-playbook` | Symptom → first check → trap → discriminating experiment, for every known failure mode |
| `techaid-failure-archaeology` | The chronicle: every investigation, dead end, and revert, with commit evidence and status |
| `techaid-architecture-contract` | Load-bearing design decisions, the invariants a diff must not break, known weak points |
| `techaid-domain-reference` | Kits, device requests, orgs, statuses, scopes, emails — the definitive domain reference |
| `techaid-config-and-flags` | Every configuration axis: env vars, defaults, profiles, guards, how to add one |
| `techaid-build-and-env` | From-scratch dev setup, build anatomy, local run, Windows traps |
| `techaid-deploy-and-operate` | Pipeline anatomy, Azure Container Apps operations, scaling, deploy verification, legacy files |
| `techaid-database-operations` | Flyway discipline, Azure Postgres topology, safe access, backups/PITR, Envers, GDPR job |
| `techaid-diagnostics-and-observability` | Measure instead of eyeball: KQL cookbook, alert inventory, tested scripts in `scripts/` |
| `techaid-validation-and-qa` | Red/green TDD rule, test infrastructure, the certified test inventory, acceptance ladder |
| `techaid-prod-promotion-campaign` | Decision-gated, command-level runbook for promoting UAT to production and rolling back |
| `techaid-investigation-methodology` | The evidence bar: instrument first, predictions before checks, idea lifecycle |
| `techaid-roadmap-and-frontier` | Vetted open improvement candidates with first steps and falsifiable milestones |

## Suggested onboarding order (humans, new to the project)

1. `techaid-architecture-contract` — how the system is built and why.
2. `techaid-domain-reference` — what the data means.
3. `techaid-build-and-env` — get it running.
4. `techaid-change-control` + `techaid-validation-and-qa` — how changes are made here.
5. Skim `techaid-failure-archaeology` — the battles already fought.

Maintenance: this README is an index only — facts live in the skills. When adding a
skill, add its row to both tables. Authored 2026-07-06.
