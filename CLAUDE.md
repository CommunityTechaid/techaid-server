# CLAUDE.md

Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

## Project operational docs

This file is generic behavioural guidance. **Project-specific operational knowledge lives in
[`.claude/skills/`](.claude/skills/README.md)** (14 skills — build, deploy, database, debugging,
config, change control). Start there. For incident response, see
[`SITE-IS-DOWN.md`](SITE-IS-DOWN.md); for onboarding, [`README.md`](README.md) → "START HERE".

## 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

## 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

## 5. Git — Branch Rules

**Never push or merge to `master` without explicit permission in the current conversation.**

- All work goes to `dev` (or a feature branch). PRs target `dev`, not `master`.
- **The deploy direction is `dev` → production → `master`. `master` is a record, never a source.**
  Production is deployed FROM the UAT-verified image: `promote.yml` (manual dispatch, gated on the
  `production` environment) points `api-production` at whatever image `api-testing` is already
  running. What ships to prod is the artefact UAT exercised — that is the whole point of the
  pipeline, so never route a prod deploy through `master`.
- Merging `dev` → `master` **records what already shipped**. It builds a `master`-tagged image that
  no job deploys (`deploy-testing` is guarded to `dev`; there is no deploy-production job), so the
  merge cannot reach production. It still needs explicit permission — see the rule above.
- The dashboard repo works the same way from the other end: `deploy-prod.yml` refuses to run unless
  dispatched from `dev`, and fast-forwards `master` to the deployed commit as its final step.
- To answer "what is running in production?", read the running image
  (`az containerapp show -n api-production ...`) or `/actuator/info`. Never infer it from `master`.
- "commit/push/PR" means commit to the current branch and open a PR targeting `dev` unless the user says otherwise.
- If in doubt, ask. The cost of asking is lower than an unintended production change.

---

**These guidelines are working if:** fewer unnecessary changes in diffs, fewer rewrites due to overcomplication, and clarifying questions come before implementation rather than after mistakes.
