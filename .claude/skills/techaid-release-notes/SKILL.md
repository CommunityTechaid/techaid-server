---
name: techaid-release-notes
description: Produce team-facing release notes for everything pending in UAT that has not yet gone to production, across techaid-server AND techaid-dashboard. Load when asked for "release notes", "what's going out", "what to tell the team", "what's pending in UAT", or before/after a promote. Outputs a super-concise user-visible list plus 1-2 lines of backend/tech-debt. Read-only.
---

# TechAid release notes (UAT → production delta)

Audience: CTA staff/volunteers, not engineers. Lead with what they will *see or be able to do*.
Read-only: never merge, push or deploy from this skill.

## 1. Establish the delta (measure, don't assume)

Prod/UAT state comes from what is *running*, never from `main` ([[feedback_verify_running_image_not_commit_list]]).

```bash
# Server: running images. Tag is dev-<sha7>.
az containerapp list -g tada-2026 --query "[].{n:name,img:properties.template.containers[0].image}" -o tsv
# Dashboard: UAT = last successful deploy-dev.yml head; prod = last successful deploy-prod.yml head
cd /d/Code/techaid-dashboard
gh run list --workflow deploy-dev.yml  -L 1 --json headSha,conclusion
gh run list --workflow deploy-prod.yml -L 3 --json headSha,headBranch,conclusion
# Delta = prod sha .. UAT sha, in each repo (git fetch first)
git log --no-merges --format='%h %s' <prod-sha>..<uat-sha>
```

Traps:
- If UAT's sha is behind `origin/dev`, only count what UAT is actually running; say the rest isn't in UAT yet.
- A dashboard **hotfix** deployed from `hotfix/*` (deploy-prod `headBranch`) is in prod but not in `main`;
  dev usually carries an equivalent commit. Exclude it from the delta by subject.
- Prod server sha may differ from `main` — use the image tag.
- Subjects are truncated by `--format=%s` in some terminals; `git show -s <sha>` for any you must interpret.

## 2. Classify every commit

| Bucket | Commit types / signals | Goes in |
|---|---|---|
| User-visible | `feat:`, user-facing `fix:` (form, page, search, error message, permission) | Headline list |
| Visible but subtle | perf (faster loads), changed error text, stricter validation, new filter | Headline list, one short line |
| Backend / tech debt | `refactor`, `chore`, `ci`, `test`, `docs`, `deps`, Docker/CodeQL/token hardening, removed legacy code | 1-2 summary lines, no per-commit list |
| Security / data protection | GDPR, auth, public-surface changes | Headline list if behaviour changes for users; else the tech line. Don't describe exploit detail |

Merge duplicates (server + dashboard halves of one feature = one bullet). Drop pure
test/CI noise from the headline entirely.

## 3. Check behaviour that needs a human decision

Flag (don't hide) anything that changes behaviour for staff or an external caller:
stricter validation (new required fields), removed API fields, public request rules,
permission changes. Add a "heads-up" line. For removals, confirm no live callers first
([[feedback_check_callers_before_api_removal]]).

## 4. Output format

```
**Going to production — <date>**  (server <ver/sha>, dashboard <ver/sha>)

User-visible
- <plain-English change> (#PR)
...

Heads-up
- <behaviour change staff might notice>

Behind the scenes
- <one line: backend/security/hardening>
- <one line: front-end tech debt/perf/test>
```

Rules: <= ~12 bullets total, each one line, no jargon (say "request form", not "createDeviceRequest").
Cite PR numbers only when they help; omit commit SHAs. End by stating what UAT sha each repo
was measured at, and whether a promote is still required (server: `promote.yml`; dashboard: `deploy-prod.yml`,
both gated, see [[techaid-prod-promotion-campaign]] and dashboard-release-and-deploy).
