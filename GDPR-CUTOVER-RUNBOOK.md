# GDPR retention cutover — production runbook

**Prepared 2026-08-12. For execution ~17:00 the same day.**

Everything here has already been done in UAT. This is the production repeat.

You need: Git Bash, the `techaid_admin` password, and about twenty minutes.

---

## The three steps

```bash
cd /d/Code/techaid-server/scripts/gdpr-cutover

./1-apply-prod-sql.sh          # replaces the view + function, grants api_prod. NO DATA CHANGE.
./2-enable-and-trigger.sh      # flips the flag and restarts the app. THIS ERASES DATA.
./3-verify-and-retire.sh       # proves it worked, then retires pg_cron.
./4-close-issues.sh --dry-run  # re-measures, reports which issues would close
./4-close-issues.sh            # closes only those whose own gate passes
```

Each script prompts before anything irreversible, prints before/after state, and writes a
timestamped log to `scripts/gdpr-cutover/logs/`. Type `yes` exactly — anything else aborts.

Set the password once to avoid re-typing it (leading space keeps it out of bash history):

```bash
 export TECHAID_ADMIN_PW='...'
```

---

## Before you start

Production must already be running the build that carries the migrations. Check:

```bash
curl -s https://api.communitytechaid.org.uk/actuator/info
```

You want **`2.5.1`**. If it still says `2.5.0`, promote first — `gh workflow run promote.yml`,
then approve it in the GitHub UI. Step 1 refuses to run if the migrations are missing, so you
cannot get this wrong silently.

---

## What each step does, and what "good" looks like

### Step 1 — apply the corrected SQL

Replaces `gdpr.donors_to_archive` and `gdpr.performgdprcleanup()` with the corrected bodies,
and grants `api_prod` `USAGE` on the `gdpr` schema plus `EXECUTE` on the function. **Those
grants have never existed in production** — that is the one prerequisite of the in-app job
that has never been exercised there.

Changes no data. Safe at any hour.

| Before | After |
|---|---|
| flag `f`, runs `0`, `api_prod USAGE = f`, function ~1989 chars | both privileges `t`, function ~6200 chars, runs **still 0** |

Runs must still be **0** afterwards. If something has written a run, step 2 cannot prove the
grant works, because the startup catch-up only fires when the last run is over 7 days old.

### Step 2 — enable and trigger

Flips `gdpr-in-app-cleanup` to `true`, then restarts the container so Spring fires
`ApplicationReadyEvent`, the catch-up sees an empty `gdpr_cleanup_runs`, and the job runs —
**as `api_prod`, on the exact path the Monday schedule uses.**

This is the step that erases data. Expect roughly:

| | rows |
|---|---|
| donors anonymised | ~149 |
| stranded audit rows reached | ~7,500 |
| note bodies cleared | ~4,981 |
| referring-org contacts cleared | ~920 |
| kit coordinates nulled | ~3,930 |

The restart costs a **40–90 second outage** — `maxReplicas` is 1, so there is no second
replica to cover it. At 17:00 that is defensible; if you would rather not, run:

```bash
./2-enable-and-trigger.sh --wait
```

which flips the flag and stops. Production scales to zero at 20:00 London and boots at 08:00,
and that boot fires the same catch-up. Then run step 3 tomorrow morning. **Either path gives
the same result** — the restart just means you get to watch.

If the grant is wrong, the job logs the failure loudly and does **not** crash the app. The
fallback is to run the function as `techaid_admin`; the call is sitting commented at the foot
of `db/admin/2026-08-13__admin_apply_gdpr_retention_scope_prod.sql`.

### Step 3 — verify, then retire pg_cron

Prints the recorded run and re-counts every category. **The retirement half refuses to run
unless every count is 0** — that is issue #62's guard, enforced in code rather than by memory:
pg_cron keeps owning retention until the in-app job has demonstrably succeeded.

Then it disables pg_cron (against the `postgres` maintenance database, not `techaid_prod` —
that catalog lives elsewhere on Azure Flexible Server) and offers to backfill the 13
historical run records recovered from Azure logs.

To look without touching anything: `./3-verify-and-retire.sh --verify`

### Step 4 — close the issues, gated on the same measurement

`./4-close-issues.sh` re-measures production and closes each GitHub issue **only if that
issue's own condition holds** — not on "the deploy ran". Each is closed with a comment carrying
the measured numbers that justified it, so the audit trail is evidence rather than assertion.

| Issue | Closes when |
|---|---|
| #62 | flag on, ≥1 recorded run, corrected function live, pg_cron inactive |
| #93 | view no longer joins `donor_parents`, 0 parentless donors eligible |
| #95 | view no longer filters `is_lead_contact`, 0 lead contacts past threshold |
| #126 | every retention category at 0 |
| #127 | all three audit columns at 0 under the *widened* predicate |
| #128 | `collection_contact_name` at 0, live and audit |
| #129 | referring-org contacts at 0, live and audit |

Run `--dry-run` first; it reports without closing anything. Safe to re-run — already-closed
issues are skipped, and anything that fails its gate simply stays open.

**A note on why there is a global precondition.** Every gate also requires the cutover to have
actually happened (flag on, ≥1 run, function > 5000 chars). Without it, an issue whose category
happens to measure zero would close on the strength of there never having been anything to
clear. #128 is exactly that case — `collection_contact_name` measured 0 rows past threshold
*before* the cutover as well as after, and a counts-only gate passed it while production was
still running the old narrow function. A dry run caught it. "The rule exists and has run" is a
separate condition from "no rows remain", and both are required.

---

## If it goes wrong

| Symptom | Do this |
|---|---|
| Step 1 errors | Nothing is half-applied — view and function are each replaced atomically and no data statement runs. Read the log, fix, re-run. |
| Step 2: app does not come back | `az containerapp revision list -n api-production -g tada-2026 -o table`. A crash-looped revision must **not** be restarted — it wedges at zero replicas. Roll back instead: `gh workflow run promote.yml -f image_tag=dev-3aa71ff` |
| Step 2: job logs `Access Denied` | The grant did not take. Re-run step 1, or use the commented `techaid_admin` fallback. Do not weaken the gate. |
| Step 3: counts are not 0 | Do **not** retire pg_cron — the script already refuses. Read `summary` in `gdpr_cleanup_runs` and the container logs. |
| Retired pg_cron and regret it | `SELECT cron.alter_job(2, active := true);` against the `postgres` database. The job definition survives — that is why `alter_job` was used rather than `unschedule`. |

Rollback anchor for the application image: `ghcr.io/communitytechaid/techaid-server:dev-3aa71ff`
(v2.4.0, revision `api-production--0000020`).

**Rolling the image back does not undo the SQL**, and nothing undoes erased data. The erasure
is the point; there is no restore path, by design.

---

## What this changes, in one table

| Field | Retained | Source |
|---|---|---|
| `donors.{name,email,phone_number,post_code}` + audit | 12 months | spreadsheet, "Individual Donors" |
| `referring_organisation_contacts.{full_name,email,phone_number,address}` + audit | 12 months | spreadsheet, "Referees" |
| `device_requests.details` + audit | 6 months | spreadsheet, "Requests" |
| `device_requests.client_ref` + audit | 12 months | spreadsheet, "Requests" |
| `device_requests.collection_contact_name` + audit | 12 months | spreadsheet, "Requests" |
| `device_requests_notes.content` | **12 months** | spreadsheet, "Requests" |
| `kits.coordinates` | 12 months, or immediately once the donor is erased | not on the spreadsheet — maintainer decision, #126 |

Every row of the team's spreadsheet ("GDPR data removal review 26-08-11.xlsx") is implemented
at the stated period.

### Two things worth knowing

**`device_requests_notes.content` is 12 months, not 6.** Issues #98, #96, #126 and PR #130 all
say 26 weeks. All four predate the spreadsheet and were never reconciled to it. Applying 26
weeks would erase a further 1,033 note bodies — 96.9% of the table — against written policy.
A test seeds a note at 40 weeks, strictly between the two thresholds, so anyone "fixing" this
gets a red test rather than a silent loss.

**Donor business exclusions are gone.** Any donor with no activity for 12 months is scrubbed,
regardless of `#business`/`#droppoint` tags, lead-contact flag, or a `BUSINESS` parent. 20 →
149 donors. Sampling showed the overwhelming majority are individual people — named human
contacts at organisations — so the old exclusion was reasoning about the parent record and
withholding erasure from natural persons.

---

## Afterwards

- **PR #130** (historical scrub) is now largely superseded — the forward job does that work
  weekly. Its remaining differences are scope decisions the spreadsheet does not support.
  Recommend closing.
- **Issues to close:** #62, #96, #126, #127, #128, #129. #92 stays open until UAT and prod are
  both confirmed.
- **`dev` → `master`** bookkeeping merge, once prod is confirmed healthy.
- **Next scheduled run:** Monday 09:30 London, in-app. First unattended proof that the
  handover worked.
