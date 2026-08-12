#!/usr/bin/env bash
#
# STEP 1 of 3 - Apply the corrected GDPR view, function and grants to techaid_prod.
#
# THIS STEP CHANGES NO DATA. It replaces two database objects and grants api_prod the
# access it has never had. The retention backlog is deliberately left in place for the
# application to clear in step 2, so that the grant is proven on the real code path.
#
# Safe to run during business hours - nothing restarts, nothing is erased.

source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

SQL="$REPO_ROOT/db/admin/2026-08-13__admin_apply_gdpr_retention_scope_prod.sql"

say "STEP 1 - apply corrected GDPR objects to techaid_prod"
printf '   script: %s\n' "$SQL"

ensure_docker
get_pw
assert_prod_has_migrations

say "state BEFORE"
run_sql techaid_prod "
SELECT 'gdpr-in-app-cleanup flag' AS what, enabled::text AS value FROM feature_flags WHERE flag_key='gdpr-in-app-cleanup'
UNION ALL SELECT 'gdpr_cleanup_runs rows', count(*)::text FROM gdpr_cleanup_runs
UNION ALL SELECT 'api_prod USAGE on gdpr', has_schema_privilege('api_prod','gdpr','USAGE')::text
UNION ALL SELECT 'live function size (chars)', length(prosrc)::text
  FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
 WHERE n.nspname='gdpr' AND p.proname='performgdprcleanup';"

cat <<'NOTE'

   Expect above: flag = f, gdpr_cleanup_runs = 0, api_prod USAGE = f,
   function ~1989 chars (the old narrow body). If the flag is already TRUE or the
   function is already large, someone has run part of this - STOP and re-read the runbook.

NOTE

confirm "Apply the corrected view + function + grants to PRODUCTION? Type yes to proceed:"

say "applying"
run_sql_file techaid_prod "$SQL" "step1-apply-prod" \
    || die "psql reported an error - read the log above. Nothing has been half-applied:
       the view and function are each replaced atomically, and no data statement runs here."

say "state AFTER"
run_sql techaid_prod "
SELECT 'api_prod USAGE on gdpr' AS what, has_schema_privilege('api_prod','gdpr','USAGE')::text AS value
UNION ALL SELECT 'api_prod EXECUTE on cleanup fn', has_function_privilege('api_prod','gdpr.performgdprcleanup()','EXECUTE')::text
UNION ALL SELECT 'live function size (chars)', length(prosrc)::text
  FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
 WHERE n.nspname='gdpr' AND p.proname='performgdprcleanup'
UNION ALL SELECT 'gdpr_cleanup_runs rows (must still be 0)', count(*)::text FROM gdpr_cleanup_runs;"

cat <<'NOTE'

   Expect: both privileges TRUE, function now ~6200 chars, gdpr_cleanup_runs STILL 0.
   gdpr_cleanup_runs must still be 0 - if it is not, something ran the cleanup and
   step 2 will not be able to prove the api_prod grant works.

NOTE

ok "STEP 1 complete. Next: ./2-enable-and-trigger.sh"
