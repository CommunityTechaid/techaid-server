#!/usr/bin/env bash
#
# STEP 3 of 3 - Prove the in-app job did the work, then retire pg_cron.
#
# The retirement half REFUSES to run unless the verification half passes. That ordering is
# the guard from issue #62: pg_cron must keep owning retention until the in-app job has
# demonstrably succeeded in production at least once.
#
# Usage:
#   ./3-verify-and-retire.sh            verify only, then ask before retiring pg_cron
#   ./3-verify-and-retire.sh --verify   verify only, never offer to retire

source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

VERIFY_ONLY=false
[[ "${1:-}" == "--verify" ]] && VERIFY_ONLY=true

say "STEP 3 - verify the run, then retire pg_cron"

ensure_docker
get_pw

# --- 1. did it run at all? -------------------------------------------------------------
say "the recorded run"
runs="$(run_sql_quiet techaid_prod "SELECT count(*) FROM gdpr_cleanup_runs")"
if [[ "$runs" == "0" ]]; then
    die "gdpr_cleanup_runs is empty - the job has not run.
       If you used --wait, production may not have rebooted yet. Check:
         curl -s https://api.communitytechaid.org.uk/actuator/health
       and re-run this script after the next boot (08:00 London on a weekday)."
fi

run_sql techaid_prod "
SELECT ran_at, donor_count, donor_audit_count,
       device_request_details_count AS dr_details, device_request_clientref_count AS dr_clientref,
       device_request_details_audit_count AS aud_details, device_request_clientref_audit_count AS aud_clientref,
       device_request_notes_count AS notes, referring_contact_count AS ref_contacts,
       referring_contact_audit_count AS ref_aud, kit_coordinates_count AS kit_coords
  FROM gdpr_cleanup_runs ORDER BY id DESC LIMIT 3;"

say "the run summary text"
run_sql techaid_prod "SELECT summary FROM gdpr_cleanup_runs ORDER BY id DESC LIMIT 1;"

# --- 2. is the backlog actually gone? ---------------------------------------------------
say "remaining backlog (every row must read 0)"
run_sql techaid_prod "
SELECT 'donors eligible' AS what, count(*)::text AS n FROM gdpr.donors_to_archive
UNION ALL SELECT 'audit details stranded', count(*)::text FROM device_requests_audit_trail dat JOIN device_requests d ON dat.id=d.id
  WHERE (d.updated_at <= (current_date - interval '26 weeks') OR d.details='RECORD DELETED BY SYSTEM - GDPR') AND dat.details <> 'RECORD DELETED BY SYSTEM - GDPR'
UNION ALL SELECT 'audit client_ref stranded', count(*)::text FROM device_requests_audit_trail dat JOIN device_requests d ON dat.id=d.id
  WHERE (d.updated_at <= (current_date - interval '52 weeks') OR d.client_ref='WIPED - GDPR') AND dat.client_ref <> 'WIPED - GDPR'
UNION ALL SELECT 'notes past 52wk', count(*)::text FROM device_requests_notes
  WHERE updated_at <= (current_date - interval '52 weeks') AND content <> 'Note content deleted due to GDPR policy'
UNION ALL SELECT 'referring contacts past 12mo', count(*)::text FROM referring_organisation_contacts
  WHERE updated_at <= (current_date - interval '12 months') AND full_name <> 'Contact - Erased due to GDPR policy'
UNION ALL SELECT 'kits past 12mo holding coordinates', count(*)::text FROM kits
  WHERE coordinates IS NOT NULL AND created_at <= (current_date - interval '12 months');"

# --- 3. machine-checked gate -------------------------------------------------------------
say "gate check"
remaining="$(run_sql_quiet techaid_prod "
SELECT (SELECT count(*) FROM gdpr.donors_to_archive)
     + (SELECT count(*) FROM device_requests_notes WHERE updated_at <= (current_date - interval '52 weeks') AND content <> 'Note content deleted due to GDPR policy')
     + (SELECT count(*) FROM referring_organisation_contacts WHERE updated_at <= (current_date - interval '12 months') AND full_name <> 'Contact - Erased due to GDPR policy')
     + (SELECT count(*) FROM kits WHERE coordinates IS NOT NULL AND created_at <= (current_date - interval '12 months'))")"

printf '   total rows still past retention: %s\n' "$remaining"
if [[ "$remaining" != "0" ]]; then
    warn "the backlog is NOT empty. pg_cron must stay scheduled."
    die "verification failed - do not retire pg_cron. Read the summary above and the
       container logs, then decide whether to re-run the job or investigate."
fi
ok "backlog is empty - the in-app job did the work"

say "note: a run recorded here proves the app executed it as api_prod, because api_prod is
   the only role the application connects as. That is issue #62's guard satisfied."

$VERIFY_ONLY && { ok "verify-only mode, stopping here."; exit 0; }

# --- 4. retire pg_cron -------------------------------------------------------------------
cat <<'NOTE'

   Retiring pg_cron means the Saturday 04:04 UTC job stops running and the application
   becomes solely responsible for retention (Monday 09:30 London, plus a startup catch-up
   for any missed slot).

   It is reversible with one statement:  SELECT cron.alter_job(2, active := true);

NOTE
confirm "Disable the pg_cron gdpr-weekly-cleanup job? Type yes:"

say "disabling pg_cron (this runs against the 'postgres' maintenance database, not techaid_prod)"
run_sql_file postgres "$REPO_ROOT/db/admin/2026-08-13__disable_pg_cron_gdpr_weekly_prod.sql" "step3-disable-pgcron" \
    || die "the disable script errored - pg_cron may still be active. Check with:
       SELECT jobid, jobname, active FROM cron.job;   (against the postgres database)"

active="$(run_sql_quiet postgres "SELECT active FROM cron.job WHERE jobid=2")"
[[ "$active" == "f" ]] || die "pg_cron job 2 still shows active=$active - investigate before finishing"
ok "pg_cron is disabled"

# --- 5. backfill the historical run stats -------------------------------------------------
say "backfilling gdpr_cleanup_runs from the Azure log history"
printf '   This adds 13 historical rows (2026-05-16 to 2026-08-08) recovered from logs.\n'
printf '   Run it LAST: its rows are dated in the past and are only a record, not a trigger.\n'
if ask "Run the backfill? Type yes (anything else skips it - it can be run later):"; then
    run_sql_file techaid_prod "$REPO_ROOT/db/admin/2026-08-12__backfill_gdpr_cleanup_runs_from_logs.sql" "step3-backfill" \
        || warn "backfill errored - not fatal, it is a historical record only. Re-run later."
else
    warn "backfill skipped - run it later with:
       ./_lib.sh is not executable; instead re-run this script, or apply
       db/admin/2026-08-12__backfill_gdpr_cleanup_runs_from_logs.sql by hand."
fi

say "final state"
run_sql techaid_prod "SELECT count(*) AS total_runs_recorded, min(ran_at) AS earliest, max(ran_at) AS latest FROM gdpr_cleanup_runs;"

ok "STEP 3 complete. The GDPR cutover is done."
