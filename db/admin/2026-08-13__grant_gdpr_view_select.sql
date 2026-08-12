-- =====================================================================================
-- Grant the api role SELECT on gdpr.donors_to_archive.
--
-- WHY THIS EXISTS
-- ---------------
-- 2026-08-13__admin_apply_gdpr_retention_scope_prod.sql granted api_prod:
--
--     GRANT USAGE   ON SCHEMA   gdpr                        TO api_prod;
--     GRANT EXECUTE ON FUNCTION gdpr.performgdprcleanup()   TO api_prod;
--
-- but gdpr.performgdprcleanup() is SECURITY INVOKER (prosecdef = false), so its body
-- executes with the *caller's* privileges. Line 18 of the body reads the view:
--
--     UPDATE public.donors d SET ... FROM gdpr.donors_to_archive dtd WHERE d.id = dtd.id
--
-- gdpr.donors_to_archive is owned by techaid_admin with a NULL relacl - owner only. So
-- EXECUTE let api_prod *enter* the function and the very first statement then failed:
--
--     ERROR: permission denied for view donors_to_archive
--     PL/pgSQL function gdpr.performgdprcleanup() line 18 at SQL statement
--
-- Observed in production 2026-08-12 16:43:22Z, on the startup catch-up fired by
-- scripts/gdpr-cutover/2-enable-and-trigger.sh. The function raised before its first
-- write, so the whole run rolled back atomically: no data was erased and no row was
-- written to gdpr_cleanup_runs.
--
-- THE UAT "PROOF" DID NOT COVER THIS
-- ----------------------------------
-- api_uat has exactly the same gap - verified 2026-08-12 by SET ROLE api_uat, same
-- error. Both recorded UAT runs show donor_count = 0, so the donor branch (the only
-- branch that touches this view) has never succeeded via the application in any
-- environment. UAT cleared notes and referring contacts, which do not read the view,
-- which is why the backlog appeared to clear and the job appeared proven.
--
-- WHY SELECT IS SUFFICIENT
-- ------------------------
-- gdpr.donors_to_archive is the only gdpr-schema object the function body references
-- (checked with regexp_matches over prosrc). gdpr.donors_archive is not referenced by
-- this function. Verified end-to-end 2026-08-12 against real production data: with this
-- grant in place, SET ROLE api_prod + SELECT gdpr.performgdprcleanup() ran to completion
-- inside a rolled-back transaction and reported all twelve categories.
--
-- SAFETY
-- ------
-- MUTATES NO DATA. Read-only privilege on a view whose rows the app is already
-- authorised to erase through the function. Reversible:
--     REVOKE SELECT ON gdpr.donors_to_archive FROM api_prod;
--
-- HOW TO RUN
-- ----------
-- Production:  psql "...dbname=techaid_prod..." -v ON_ERROR_STOP=1 -v api_role=api_prod \
--                  -f 2026-08-13__grant_gdpr_view_select.sql
-- UAT:         same, dbname=techaid_uat, -v api_role=api_uat
--
-- Run it as techaid_admin (the view owner).
-- =====================================================================================

\if :{?api_role}
\else
    \echo 'ERROR: pass the role, e.g.  -v api_role=api_prod'
    \quit 1
\endif

\echo ''
\echo '=== BEFORE (expect f) ==='
SELECT :'api_role' AS role,
       has_table_privilege(:'api_role', 'gdpr.donors_to_archive', 'SELECT') AS view_select;

GRANT SELECT ON gdpr.donors_to_archive TO :"api_role";

\echo ''
\echo '=== AFTER (all three must be t) ==='
SELECT :'api_role' AS role,
       has_schema_privilege(:'api_role', 'gdpr', 'USAGE')                             AS schema_usage,
       has_table_privilege(:'api_role', 'gdpr.donors_to_archive', 'SELECT')           AS view_select,
       has_function_privilege(:'api_role', 'gdpr.performgdprcleanup()', 'EXECUTE')    AS fn_execute;

\echo ''
\echo '=== the grant is now recorded on the view ==='
SELECT c.relname, c.relacl::text AS acl
  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'gdpr' AND c.relname = 'donors_to_archive';

\echo ''
\echo '=== gdpr_cleanup_runs must still be 0 in prod - the app writes the first row ==='
SELECT count(*) AS runs_recorded FROM gdpr_cleanup_runs;
