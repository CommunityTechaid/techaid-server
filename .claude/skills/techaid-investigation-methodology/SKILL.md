---
name: techaid-investigation-methodology
description: The discipline that turns a hunch into an accepted result in techaid-server. Load BEFORE starting any root-cause investigation, before declaring a root cause, when tempted to apply a fix without captured evidence, when a bug report is vague ("imports are failing", "the API is slow"), or when deciding whether an experimental change should be adopted or retired. Contains the evidence bar, the instrument-before-fixing rule, hypothesis-predicts-observations, discriminating experiments, adversarial refutation, the idea lifecycle, and a copy-paste investigation template — each grounded in a worked example from this repo's real history.
---

# TechAid Investigation Methodology

How this project converts "something is wrong" into a verified root cause and an adopted (or deliberately retired) change. Every method below is a recipe plus a worked example from this repo's actual git history — read the cited commit messages in full (`git show -s --format='%B' <hash>`); they are the house standard for investigation writeups.

**Jargon used in this file (defined once):**
- **Negative observation** — something that did NOT break or DID keep working. Negatives kill wrong hypotheses faster than positives confirm right ones.
- **Discriminating experiment** — the cheapest check whose outcome differs between two surviving hypotheses.
- **Residue** — the durable artifact an investigation leaves behind so it never has to be re-fought: a test, an alert, a migration, a skill/doc update, or a revert commit with a real explanation.
- **UAT** — the `api-testing` Container App, auto-deployed from `dev` (see techaid-deploy-and-operate).

## When NOT to use this skill

- You have a known symptom and want the triage table → **techaid-debugging-playbook**.
- You want to know whether a battle was already fought and settled → **techaid-failure-archaeology**.
- You need the actual measurement commands (KQL queries, log locations, access-log fields) → **techaid-diagnostics-and-observability**.
- You are past root cause and shipping the fix → **techaid-change-control** and **techaid-validation-and-qa**.

---

## 1. The evidence bar: one mechanism must explain ALL observations — including the negatives

A root cause is not "a thing that could cause this." It is the single mechanism that accounts for **every** observation you have, including everything that kept working. If your hypothesis doesn't explain a negative, it is wrong or incomplete — do not ship a fix on it.

**Recipe:**
1. List every observation, positives and negatives, before hypothesizing.
2. For each candidate mechanism, walk the full list: does it explain this observation? One "no" disqualifies it.
3. Only a mechanism that survives the whole list may be called the root cause.

**Worked example — App Insights request capture (May 2026, commit `4e76b27`).**
Symptom: the App Insights `requests` table had only 2 entries in 7 days (both scheduled tasks) despite hundreds of `POST /graphql` per day in production.
- Naive hypothesis: "the agent is broken / not attached."
- Negatives that kill it: customMetrics, dependencies, traces, and performanceCounters all flowed fine — a dead agent explains none of that.
- Surviving mechanism: the agent (3.5.4, late 2024) started cleanly but its **servlet auto-instrumentation** predated Spring Boot 3.x / Jakarta Servlet fixes — so exactly the HTTP-server-request slice was missing while everything else worked.
- Fix: bump to 3.7.8 (`4e76b27`, `AI_AGENT_VERSION` in `Dockerfile`), **verified by requests appearing afterward** — the fix is only accepted once the original symptom is observed to disappear.

Read `git show -s --format='%B' 4e76b27`: note how the message states the positive symptom, the negatives, the mechanism, and the verification plan in four sentences.

## 2. Instrument before fixing: never patch what you haven't captured

If the failing input/state is invisible, your first change is **logging, not a fix**. A fix built on a guessed input is a coin flip; a fix built on a captured input is engineering.

**Worked example — the lenient-scalar saga (June 2026, commits `49a0a3c` → `dde5891` → `c66e4a5` → `ba8b276`).**
Symptom: bulk kit imports from a Google Sheet + Apps Script failed with `Expected a String input, but it was a 'Integer'` — but GraphQL coercion errors return HTTP 200 and never reached the error handler, access log, or App Insights. The failure was invisible.
1. **First change was instrumentation** (`49a0a3c`, PR #40): extend `GraphQlTelemetryInterceptor` to log GraphQL errors *with the JSON-serialised input variables*, so `"lotId": 26060301` (number) is distinguishable from `"lotId": "26060301"` (string). Residue: `GraphQlErrorLoggingTest`.
2. The captured payload pinned the exact culprit: a numeric `lotId` into `CreateKitInput.lotId` (typed String).
3. **Only then** was a fix attempted (`dde5891`, PR #42): override the built-in String scalar schema-wide with lenient coercion.
4. That broad fix failed schema assembly and was **reverted** (`c66e4a5`, PR #43).
5. The retry (`ba8b276`, PR #44) was **narrow**: a distinct `LenientString` scalar applied in the SDL only to the numeric-prone kit-input fields (`lotId`, `locationCode`, `serialNo`), with `SchemaAssemblyTest` guarding the exact assembly step that broke #42.

Two lessons baked in:
- **Prefer the narrow intervention.** The schema-wide override traded away strict typing everywhere to fix three fields; the targeted scalar didn't.
- **A revert is the method working, not failing.** `c66e4a5` plus the documented retry is exactly the lifecycle in §6. The commit messages of all three (`dde5891`, `c66e4a5`, `ba8b276`) narrate cause, capture, approach, and what the tests guard — hold your own writeups to that standard.

## 3. State what each hypothesis predicts BEFORE you run the check

Write the prediction down first ("if H is true, I will see X in place Y"), then look. If you look first, you will rationalize whatever you find. This also makes triage fast: each hypothesis names its own one check.

**Worked example — the 2026-07-01 production outage (Azure-side facts embedded here as of 2026-07-03; not repo-verifiable).**
Symptom: `api-production` down for ~75 minutes from its 07:00 UTC scale-up.
- H1 "a deploy broke it" → predicts a new image/revision around the failure time. Check: revision history. **Observed: no new revision.** H1 dead.
- H2 "database down" → predicts connection errors in the app's console logs. Check: `ContainerAppConsoleLogs_CL`. **Observed: no app logs at all — the container never started.** H2 dead, and this negative is itself a strong clue.
- H3 "platform failure" → predicts container-creation failures in `ContainerAppSystemLogs_CL`. **Observed: ~20× `ContainerCreateFailure`, overlay-filesystem mount error, same broken host node retried repeatedly.** Confirmed.
- Fix followed the mechanism: manual stop/start rescheduled the pod onto a healthy node. Nothing app-side was touched — because no evidence implicated the app.
- Residue: a scheduled-query alert on `ContainerCreateFailure`/`ProbeFailed`, **backtested over 14 days before being trusted** — it fired only during the real incident. Backtesting an alert against history before relying on it is a house method: an alert is a hypothesis about what "bad" looks like, and history is the cheapest experiment.

## 4. Discriminating experiments: prove what IS, don't guess

When two hypotheses survive, or when the ground truth is simply unknown, design the cheapest check that yields a different answer under each possibility. When the unknown is "what state is the system actually in," build a harness that dumps the state and diff it — don't reason it out from code.

**Worked example — the DDL diff harness behind the Flyway baseline (July 2026, commit `76b092f`, PR #49).**
Problem: most tables predated Flyway or had been created on the fly by `ddl-auto=update`, so nobody could say from the migrations what the real schema was — and any guess baked into a baseline migration would fail on some environment.
Experiment: boot **two Spring contexts** — one with Flyway only, one with Flyway + `ddl-auto=update` — and diff the resulting schemas. The diff is, by construction, exactly what Hibernate had been silently creating.
Result: baseline migration `V26.07.03.0900__baseline_unmanaged_schema.sql` written verbatim from Hibernate 6's schema export with `IF NOT EXISTS` guards, so it is a no-op wherever the objects already exist. The harness itself was deliberately **not committed** — it was scaffolding; the migration plus `SchemaValidationTest` (boots Hibernate `validate` against a Flyway-only embedded Postgres) is its residue.

The pattern generalizes: when you need to know what production config/schema/behavior actually is, find or build the two-sided comparison that makes the answer mechanical instead of argued.

## 5. Red/green as proof-of-fix

A fix is proven when a test that failed before the change passes after it, and that test stays in the suite. This is a house norm for every backend change, not just investigations. Recipe, mechanics, and the embedded-Postgres setup live in **techaid-validation-and-qa** — don't improvise a variant. In this skill's terms: the red test is the captured evidence (§2) made executable, and the green run is the verification gate (§1).

## 6. The idea lifecycle: hunch → adopted change or documented retirement

Every change that alters behavior walks this ladder. Skipping rungs is how the settled battles in techaid-failure-archaeology got fought the first time.

1. **Hunch** — "I think X is wrong / Y would help."
2. **Captured evidence** — instrumentation or existing telemetry showing the phenomenon (§2). No capture, no next rung.
3. **Falsifiable hypothesis** — a mechanism, with written predictions for each observation (§3), surviving the negatives (§1) and the adversarial case (§7).
4. **Discriminating experiment** — the cheapest check or harness that settles it (§4).
5. **Change behind normal change control** — red/green test (§5), PR to `dev`, review; never route around **techaid-change-control**.
6. **UAT trial + soak** — deployed to `api-testing` with **measured** success criteria stated in advance ("the error count for this operation goes to zero", "requests appear in App Insights"). Never judged by eye.
7. **Adopt or retire.**
   - Adopt: the change merges, and the relevant skill/doc is updated so the knowledge outlives the session.
   - Retire: revert with a commit message that explains *why*, so the next person doesn't retry it blind. `c66e4a5` (reverting the schema-wide scalar) is the model; `8c48102` ("Reverting sequence definition in order to track down duplicate audit record bug") shows reverts used as instruments too — undoing a change *to isolate a variable* is a legitimate experiment.

**Where good changes have historically come from** (as of 2026-07-05): incident residue (the availability alert, `GraphQlErrorLoggingTest`, `SchemaValidationTest`, the baseline migration), the systematic July-2026 code review (PRs #48/#49 — auth gates, indexes, timeouts, `ddl-auto=validate`), and drift found in passing (stale docs, dead config) — logged and fixed under change control, not silently.

## 7. Adversarial refutation: argue against yourself before you declare

Before writing "root cause: X", write the strongest case AGAINST X and check *that* case's predictions too. If you can't refute the counter-case with an observation you already have (or one cheap check), you are not done.

Applied to the outage in §3: the counter-case to "platform failure" was "the app crashed so early it produced no logs" — which would look identical in `ContainerAppConsoleLogs_CL` (empty either way). What refutes it: `ContainerCreateFailure` in the system logs means the container was **never created**, so no app code ran; and the identical pod booted cleanly on a different node minutes later with zero app-side changes. Only after the counter-case died was the root cause declared and the alert built on it.

## 8. Investigation template (copy into your scratchpad)

```markdown
# Investigation: <one-line symptom>          Date: YYYY-MM-DD

## Symptom
Exact user-visible failure, first/last seen, environment (local / UAT / production).

## Observations
| # | Observation | Positive/Negative | Source (command / log / table) |
|---|-------------|-------------------|--------------------------------|
(Include what KEPT working. If the failing input is invisible → STOP, add instrumentation first (§2).)

## Hypotheses
| H | Mechanism | Predicts (specific, in advance) | Check | Result |
|---|-----------|----------------------------------|-------|--------|
(Kill any H that fails one observation. Write predictions BEFORE running checks.)

## Adversarial case
Strongest argument against the surviving H, and the observation/check that refutes it.

## Verdict
The one mechanism that explains ALL observations, incl. negatives.

## Residue
- [ ] Red/green test (see techaid-validation-and-qa)
- [ ] Alert (backtested against history) / dashboard change, if operational
- [ ] Skill or doc updated (which one)
- [ ] If retired: revert commit with a why-message
```

---

## Provenance and maintenance

Authored 2026-07-03, finalized 2026-07-05, from this repo's git history and the cited commit messages; Azure-side outage/telemetry details (§3) embedded as of 2026-07-03 and are not repo-verifiable.

Re-verification commands (run from repo root):
- Worked-example commits still exist and say what this file claims: `git show -s --format='%B' 49a0a3c dde5891 c66e4a5 ba8b276 4e76b27 76b092f 8c48102`
- Residue tests still in suite: `ls src/test/kotlin/cta/graphql/GraphQlErrorLoggingTest.kt src/test/kotlin/cta/db/SchemaValidationTest.kt src/test/kotlin/cta/app/config/LenientStringScalarTest.kt src/test/kotlin/cta/graphql/SchemaAssemblyTest.kt`
- Baseline migration present: `ls src/main/resources/db/migration/V26.07.03.0900__baseline_unmanaged_schema.sql`
- Agent version claim: `grep AI_AGENT_VERSION Dockerfile`
- Sibling skills referenced here still exist: `ls .claude/skills/`
