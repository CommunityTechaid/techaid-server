# Onboarding — TaDa developers (techaid-server + techaid-dashboard)

Welcome to Community TechAid. TaDa is our donations pipeline: an **Angular dashboard**
(`techaid-dashboard`) talking to a **Kotlin/Spring GraphQL API** (`techaid-server`, this repo),
backed by Azure PostgreSQL, with Auth0 for login. Both repos are built to be worked on with
Claude Code. The project's operational knowledge lives in their `.claude/skills/` libraries.

> **For Claude, when guiding a newcomer through this file:** work through the steps in order, one
> at a time. Confirm each check passes before moving on, and stop to explain anything that fails.
> Facts live in the skills and the CLAUDE.md files, so load and quote them rather than restating
> from memory. If this file disagrees with a CLAUDE.md, CLAUDE.md wins; flag the drift. Don't
> change remote state for the newcomer (no pushes, PRs or deploys) unless they ask you to.

## 1. Access (ask a maintainer)

- [ ] GitHub: membership of the **CommunityTechaid** org, with write access to `techaid-server` and `techaid-dashboard`
- [ ] Auth0: a UAT dashboard login, needed for the live e2e bearer token
- [ ] Claude Code installed and signed in

You do **not** need Azure, Cloudflare or production access to develop. Deploying to production is a
maintainer-only, approval-gated workflow.

## 2. Tools

- Git (on Windows, use **Git Bash**), plus the GitHub CLI `gh` (`gh auth login`)
- **JDK 17** (Temurin) for the server; Gradle comes via `./gradlew`
- **Node 24** for the dashboard (the version CI uses)
- Optional: Docker, to run the API locally behind the dashboard's `ng serve` proxy

## 3. Clone both repos side by side

```bash
gh repo clone CommunityTechaid/techaid-server
gh repo clone CommunityTechaid/techaid-dashboard
```

Work in a branch off **`dev`**. Start Claude Code from the repo root (`cd techaid-server && claude`)
so that repo's `CLAUDE.md` and skills load.

## 4. Read the contracts

These files are always loaded into Claude's context. Read them yourself once too:

| File | What it covers |
|---|---|
| `techaid-server/CLAUDE.md` | Behavioural rules (think first, keep it simple, make surgical changes) and the **Git branch rules**: `dev` → prod → `master` |
| `techaid-server/README.md` → "START HERE" | Quick reference: tests, the UAT deploy check, cold starts, Windows tips |
| `techaid-server/SITE-IS-DOWN.md` | The first 10 minutes of an incident. It is usually a 40–90 s cold start |
| `techaid-dashboard/CLAUDE.md` | Build and run, architecture map, release workflow, testing and tokens, **issue-triage workflow** |

Neither repo has an `AGENTS.md`. For personal preferences, use a git-ignored `CLAUDE.local.md`.

## 5. Skills, in this order

Claude loads skills automatically when a task matches their description, or you can ask for one by
name. Each repo's `.claude/skills/README.md` has a "your situation → this skill" table.

1. `techaid-domain-reference` (server) — what kits, device requests, donors, orgs, statuses and Auth0 scopes mean. **Frontend devs need this too.**
2. `techaid-architecture-contract` and `dashboard-architecture-contract` — the load-bearing design decisions in each repo
3. `techaid-build-and-env` — set up and run the server locally, and the Windows traps
4. `techaid-validation-and-qa` and `dashboard-testing-and-e2e` — what evidence a change needs before it counts as done
5. `techaid-change-control` — branches, PRs, conventional commits, release-please
6. Skim `techaid-debugging-playbook`, `dashboard-debugging-playbook` and `techaid-failure-archaeology` — battles already fought

Look up the rest when you need them: `techaid-config-and-flags`, `techaid-database-operations`,
`techaid-investigation-methodology`, `*-roadmap-and-frontier` (what to work on next) and
`triage-issues`. The deploy, observability and prod-promotion skills are maintainer territory.

## 6. Prove both repos build

**Server** (embedded Postgres, no Docker needed):

```bash
cd techaid-server
./gradlew ktlintCheck test
```

A red `SchemaValidationTest` means a Flyway migration is missing.

**Dashboard:**

```bash
cd techaid-dashboard
npm install                              # NOT npm ci — the lockfile is Windows-maintained
ng build --configuration production      # the strict build is the main correctness signal
npm run e2e:fast                         # mocked Playwright subset, no token needed (what CI runs)
```

The live UAT suite (`npx playwright test`) needs a bearer token. See "Testing" in the dashboard's `CLAUDE.md`.

## 7. Rules that matter

- Branch from `dev` and open PRs against **`dev`**. Never push to `dev` or `master` directly, and never create `v*` tags; release-please and the workflows own them.
- Give PRs conventional-commit titles (`fix:`, `feat:`, `chore:`, `docs:`). release-please uses them to pick the version bump and build the changelog.
- Every bug fix comes with a test that **fails before the fix and passes after**.
- Keep each PR to the issue in hand. Flag the same problem elsewhere instead of fixing it in the same PR.
- A GraphQL resolver with no `@PreAuthorize` is **public**: server security is open by default.
- Schema changes need a Flyway migration, and audited entities need their Envers audit table kept in step.
- Dashboard forms are config-driven (ngx-formly), so look at the form config, not just the HTML.
- Never commit tokens, `.env` files or anything under `e2e/.auth/`.

## 8. Working with Claude Code

- Plan before you edit on anything non-trivial (plan mode, or ask for "plan first, don't edit yet").
- Pick up a session with `claude --continue` or `/resume`. Use `/compact` to keep long sessions sharp.
- Run `/code-review` before you open a PR.
- **Handoffs:** at the end of a multi-session task, ask Claude to write `_session_handoff_<date>_<topic>.md` covering the goal, what's done, what's left, open questions, links and the next command to run. These files are git-ignored. Anything a teammate needs goes in the PR or issue.

## 9. First task

Ask a maintainer for a starter issue, or run `techaid-roadmap-and-frontier` /
`dashboard-roadmap-and-frontier` for vetted candidates. Fix it red/green, open a PR to `dev`, and
ask for review.
