#!/usr/bin/env bash
#
# STEP 2 of 3 - Turn the in-app retention job on, and let it clear the backlog.
#
# THIS IS THE STEP THAT ERASES DATA. It is erased by the application, running as api_prod,
# on the exact code path the Monday schedule uses - which is the whole point of doing it
# this way rather than calling the function as techaid_admin.
#
# Usage:
#   ./2-enable-and-trigger.sh            flip the flag, then RESTART the app to trigger now
#   ./2-enable-and-trigger.sh --wait     flip the flag only; the next natural boot triggers it
#
# --wait costs nothing and interrupts nobody: production scales to zero at 20:00 London and
# boots again at 08:00, and that boot fires the same startup catch-up. The restart path just
# means you get to watch it happen, at the cost of a 40-90 second cold start.

source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

MODE="restart"
[[ "${1:-}" == "--wait" ]] && MODE="wait"

say "STEP 2 - enable the in-app GDPR job (mode: $MODE)"

ensure_docker
get_pw

# --- preconditions -------------------------------------------------------------------
say "checking step 1 actually completed"
usage="$(run_sql_quiet techaid_prod "SELECT has_schema_privilege('api_prod','gdpr','USAGE')")"
exec_="$(run_sql_quiet techaid_prod "SELECT has_function_privilege('api_prod','gdpr.performgdprcleanup()','EXECUTE')")"
size="$(run_sql_quiet techaid_prod "SELECT length(prosrc) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='gdpr' AND p.proname='performgdprcleanup'")"
runs="$(run_sql_quiet techaid_prod "SELECT count(*) FROM gdpr_cleanup_runs")"

printf '   api_prod USAGE=%s  EXECUTE=%s  fn_chars=%s  cleanup_runs=%s\n' "$usage" "$exec_" "$size" "$runs"
[[ "$usage" == "t" && "$exec_" == "t" ]] || die "api_prod does not hold the grants - run ./1-apply-prod-sql.sh first"
[[ "$size" -gt 5000 ]] || die "the live function is still the old narrow body ($size chars) - run ./1-apply-prod-sql.sh first"
ok "step 1 is in place"

if [[ "$runs" != "0" ]]; then
    warn "gdpr_cleanup_runs already has $runs row(s)."
    warn "The startup catch-up only fires when the last run is over 7 days old, so it will"
    warn "NOT fire now. The Monday 09:30 cron will still run it. Continue only if you know why."
    confirm "Continue anyway? Type yes:"
fi

# --- the backlog we expect to disappear ----------------------------------------------
say "backlog BEFORE (record these - step 3 checks they reach 0)"
run_sql techaid_prod "
SELECT 'donors eligible' AS what, count(*)::text AS n FROM gdpr.donors_to_archive
UNION ALL SELECT 'audit details stranded', count(*)::text FROM device_requests_audit_trail dat JOIN device_requests d ON dat.id=d.id
  WHERE (d.updated_at <= (current_date - interval '26 weeks') OR d.details='RECORD DELETED BY SYSTEM - GDPR') AND dat.details <> 'RECORD DELETED BY SYSTEM - GDPR'
UNION ALL SELECT 'notes past 52wk', count(*)::text FROM device_requests_notes
  WHERE updated_at <= (current_date - interval '52 weeks') AND content <> 'Note content deleted due to GDPR policy'
UNION ALL SELECT 'referring contacts past 12mo', count(*)::text FROM referring_organisation_contacts
  WHERE updated_at <= (current_date - interval '12 months') AND full_name <> 'Contact - Erased due to GDPR policy'
UNION ALL SELECT 'kits holding coordinates', count(*)::text FROM kits WHERE coordinates IS NOT NULL;"

cat <<'NOTE'

   Roughly expected (measured 2026-08-12): 149 donors, ~7500 stranded audit rows,
   4981 notes, 920 referring contacts, 3930 kits with coordinates.

   Turning the flag on means the application WILL erase these. It is not reversible.

NOTE

confirm "Enable the in-app GDPR retention job in PRODUCTION? Type yes:"

say "flipping the flag"
run_sql techaid_prod "UPDATE feature_flags SET enabled = true, updated_at = now() WHERE flag_key = 'gdpr-in-app-cleanup';"
run_sql techaid_prod "SELECT flag_key, enabled, updated_at FROM feature_flags WHERE flag_key='gdpr-in-app-cleanup';"
ok "flag is on"

if [[ "$MODE" == "wait" ]]; then
    cat <<'NOTE'

   Flag is on and nothing has been erased yet.

   The job fires on the application's next boot. Production scales to zero at 20:00
   London and boots at 08:00 - it also wakes overnight when scanners hit it, so it may
   well run sooner. Either way, run ./3-verify-and-retire.sh tomorrow morning.

NOTE
    ok "STEP 2 complete (waiting for a natural boot). Next: ./3-verify-and-retire.sh"
    exit 0
fi

# --- restart to trigger now ------------------------------------------------------------
say "restarting $APP to fire the startup catch-up"
REV="$(az containerapp revision list -n "$APP" -g "$RG" \
        --query "[?properties.active && properties.trafficWeight==\`100\`].name | [0]" -o tsv)"
[[ -n "$REV" ]] || die "could not identify the active revision"
printf '   active revision: %s\n' "$REV"
warn "the API will be unavailable for roughly 40-90 seconds (maxReplicas is 1)"
confirm "Restart it now? Type yes:"

az containerapp revision restart -n "$APP" -g "$RG" --revision "$REV"
ok "restart issued"

say "waiting for the app to come back (this is the cold start)"
for i in $(seq 1 30); do
    sleep 10
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 https://api.communitytechaid.org.uk/actuator/health || true)"
    printf '   [%02d] health HTTP %s\n' "$i" "$code"
    [[ "$code" == "200" ]] && { ok "app is back"; break; }
done

say "waiting for the cleanup to record its run"
for i in $(seq 1 12); do
    n="$(run_sql_quiet techaid_prod "SELECT count(*) FROM gdpr_cleanup_runs")"
    printf '   [%02d] gdpr_cleanup_runs rows: %s\n' "$i" "$n"
    [[ "$n" != "0" ]] && { ok "the job ran"; break; }
    sleep 15
done

ok "STEP 2 complete. Next: ./3-verify-and-retire.sh"
