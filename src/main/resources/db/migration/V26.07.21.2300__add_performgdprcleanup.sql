-- Bring gdpr.performgdprcleanup() — the actual GDPR retention routine — under version control.
--
-- BACKGROUND
--   This function exists in techaid_uat and techaid_prod (verified byte-identical in both on
--   2026-07-21) and is what the pg_cron job `gdpr-weekly-cleanup` calls every Saturday. It is
--   the third piece of the gdpr schema found to exist only in the live databases, after the
--   donors_to_delete -> donors_to_archive rename and the archive_donor_info() trigger
--   (V26.07.21.2130, db/admin/2026-07-21__fix_archive_donor_info.sql).
--
--   Because it was in no migration, it was absent from every fresh and test database, so
--   nothing that depends on it could be tested at all. That is what blocks PR #80's in-app
--   cleanup from having an end-to-end test.
--
-- REPRODUCED VERBATIM
--   Captured with pg_get_functiondef from techaid_prod. Deliberately NOT modified:
--
--   * It is left SECURITY INVOKER, exactly as live. Making it SECURITY DEFINER (so api_uat /
--     api_prod could call it without rights on the gdpr schema) is a prerequisite for enabling
--     the gdpr-in-app-cleanup flag, but that is a change, not a capture, and belongs with that
--     work rather than smuggled into a convergence migration.
--   * It selects from gdpr.donors_to_archive, whose `donor_parents.type <> 'BUSINESS'`
--     predicate excludes every donor with no donor parent under three-valued logic. That may
--     mean retention has been anonymising far fewer donors than intended. It is preserved here
--     so the repo reflects what production actually runs; measure it before changing it.
--
-- WHY THE GUARD
--   Same as V26.07.21.2130: in UAT and production the gdpr objects are owned by techaid_admin
--   and Flyway runs as the app role, which has no rights there — and needs none, since the
--   function already exists. The has_schema_privilege gate makes this a deliberate no-op in
--   those databases; the work happens only on a fresh database.

DO $mig$
BEGIN
    IF to_regnamespace('gdpr') IS NULL THEN
        RAISE NOTICE 'gdpr schema absent - skipping';
        RETURN;
    END IF;

    IF NOT has_schema_privilege(current_user, 'gdpr', 'CREATE') THEN
        RAISE NOTICE 'role % has no CREATE on gdpr - skipping (expected in UAT/production)', current_user;
        RETURN;
    END IF;

    IF to_regprocedure('gdpr.performgdprcleanup()') IS NOT NULL THEN
        RETURN;
    END IF;

    EXECUTE $fn$
        CREATE OR REPLACE FUNCTION gdpr.performgdprcleanup()
         RETURNS text
         LANGUAGE plpgsql
        AS $function$
            DECLARE
              result text;
              donor_archived_count numeric;
              donor_audit_trail_archived_count numeric;
              device_request_details_archived_count numeric;
              device_request_clientref_archived_count numeric;
            BEGIN

              UPDATE public.donors d
              SET name = 'Donor - Erased due to GDPR policy',
                email = '',
                phone_number = '',
                post_code = '',
                coordinates = null,
                updated_at = CURRENT_DATE,
                referral = '',
                archived = 'Y'
              FROM gdpr.donors_to_archive dtd
              WHERE d.id = dtd.id;

              GET DIAGNOSTICS donor_archived_count = ROW_COUNT;

              UPDATE public.donors_audit_trail d
              SET name = 'Donor - Erased due to GDPR policy',
                email = '',
                phone_number = '',
                post_code = '',
                referral = ''
              FROM gdpr.donors_to_archive dtd
              WHERE d.id = dtd.id;

              GET DIAGNOSTICS donor_audit_trail_archived_count = ROW_COUNT;

              UPDATE public.device_requests
              SET details = 'RECORD DELETED BY SYSTEM - GDPR'
              WHERE updated_at <= (CURRENT_DATE - '26 weeks'::interval)
                    AND details <> 'RECORD DELETED BY SYSTEM - GDPR';

              GET DIAGNOSTICS device_request_details_archived_count = ROW_COUNT;

              UPDATE public.device_requests
              SET client_ref = 'WIPED - GDPR'
              WHERE updated_at <= (CURRENT_DATE - '52 weeks'::interval)
                    AND client_ref <> 'WIPED - GDPR';

              GET DIAGNOSTICS device_request_clientref_archived_count = ROW_COUNT;

              result = format('GDPR Cleanup: Archived %s inactive donors, %s audit trail donor records, %s device request details, and %s device request client refs', donor_archived_count, donor_audit_trail_archived_count, device_request_details_archived_count, device_request_clientref_archived_count);

              RAISE LOG 'GDPR Cleanup: Archived % inactive donors, % audit trail donor records, % device request details, and % device request client refs', donor_archived_count, donor_audit_trail_archived_count, device_request_details_archived_count, device_request_clientref_archived_count;

              RETURN result;
            END;
        $function$
    $fn$;
    RAISE NOTICE 'created gdpr.performgdprcleanup()';
END $mig$;
