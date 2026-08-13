-- Applies V26.08.12.1100's corrected gdpr.donors_to_archive view and gdpr.performgdprcleanup()
-- function to techaid_prod, since techaid_admin owns these objects and Flyway does not (see
-- db/admin/README.md). Confirmed by the maintainer 2026-08-12 after the decision record was
-- re-read end to end - see V26.08.12.1100 for the full corrections write-up.
--
-- Three corrections carried by this script (2, 3, 4 below - numbering aligned with
-- V26.08.12.1100), plus note 0 recording a change deliberately NOT made:
--   0. NOT A CHANGE, recorded because it was very nearly made: device_requests_notes.content
--      STAYS AT 52 WEEKS. The authoritative source is the team's retention spreadsheet,
--      "GDPR data removal review 26-08-11.xlsx", sheet "Requests" row 5, which says 12 months
--      for this field. #98/#96/#126/PR #130 all say 26 weeks and all predate the spreadsheet.
--      Applying 26 weeks would have destroyed a further 1,033 note bodies (6,014 vs 4,981)
--      against written policy, irreversibly. The spreadsheet outranks the issue comments.
--   2. gdpr.donors_to_archive: both remaining business exclusions dropped - the
--      '%#(business|droppoint)%' name tag and donor_parents.type <> 'BUSINESS'. (The
--      is_lead_contact exemption was already dropped by V26.08.11.1400; it is not one of
--      these two.) Measured impact in production: 20 -> 149 eligible donors, every one of
--      the 129 from the donor_parents.type predicate - the name tag matches 0 rows today.
--   3. kits.coordinates newly in scope: NULLed once past 12 months, or immediately when the
--      owning donor is already erased.
--   4. device_requests_audit_trail / referring_organisation_contacts_audit_trail: an OR-branch
--      gap closed, so rows behind an already-erased live row are reachable regardless of what
--      that live row's updated_at now says.
--
-- PREREQUISITES BEFORE RUNNING
--   1. This branch's GDPR work (V26.08.11.1400, 1450, 1600, 1650, V26.08.12.1000, 1100) must
--      already be in production - i.e. dev has been promoted to master and deployed. The plain
--      migrations (flag row, gdpr_cleanup_runs table and columns) apply automatically on that
--      deploy; the gdpr-schema migrations are self-gated no-ops there, which is exactly why
--      this script exists.
--   2. Run as techaid_admin.
--   3. After this runs, flip the gdpr-in-app-cleanup flag to true in production (Feature Flags
--      admin page, or `UPDATE feature_flags SET enabled = true WHERE flag_key =
--      'gdpr-in-app-cleanup';`) - this script does not do that, so the in-app job stays off
--      and pg_cron keeps running until someone does.
--   4. Then restart api-production so the startup catch-up fires and the in-app job clears the
--      backlog AS api_prod. See the block above the AFTER section at the foot of this file for
--      why the run is deliberately not performed here, and for the full five-step sequence.
--   5. Only once that app-triggered run has succeeded may pg_cron (gdpr-weekly-cleanup, Sat
--      04:04 UTC) be disabled - db/admin/2026-08-13__disable_pg_cron_gdpr_weekly_prod.sql,
--      a separate step against the `postgres` database. Do not disable it before then (#62).
--
-- WHAT THIS DOES
--   Replaces gdpr.donors_to_archive and gdpr.performgdprcleanup() with V26.08.12.1100's
--   corrected bodies and grants api_prod real access, matching what was granted to api_uat on
--   2026-08-12. Applied for real here since techaid_admin owns these objects and Flyway does
--   not.
--
-- THIS SCRIPT MUTATES NO DATA. It replaces the view and function and grants api_prod access;
-- the retention backlog is left in place for the application to clear (see the foot of this
-- file). BEFORE and AFTER counts should therefore MATCH - a difference means something ran that
-- should not have.
--
-- Production's backlog was freshly re-measured, read-only, on 2026-08-12 against the CORRECTED
-- view/function; the expected values are recorded inline against the AFTER queries. Treat them
-- as a sanity check, not a gate - a day of ordinary traffic moves them slightly.
--
-- APPLIED TO
--   (none yet - run once prerequisite 1 above is satisfied.)

DO $$ BEGIN IF current_database() <> 'techaid_prod' THEN RAISE EXCEPTION 'ABORT: %', current_database(); END IF; END $$;

SELECT '=== BEFORE ===' AS section;
SELECT 'donors eligible (old view, lead-contact still exempt)' AS what, count(*) AS n FROM gdpr.donors_to_archive;
SELECT 'device_requests.details past 26wk unscrubbed' AS what, count(*) AS n FROM device_requests WHERE updated_at <= (current_date - interval '26 weeks') AND details <> 'RECORD DELETED BY SYSTEM - GDPR';
SELECT 'device_requests.client_ref past 52wk unscrubbed' AS what, count(*) AS n FROM device_requests WHERE updated_at <= (current_date - interval '52 weeks') AND client_ref <> 'WIPED - GDPR';
SELECT 'device_requests.collection_contact_name past 52wk unscrubbed' AS what, count(*) AS n FROM device_requests WHERE updated_at <= (current_date - interval '52 weeks') AND collection_contact_name IS NOT NULL AND collection_contact_name <> 'WIPED - GDPR';
SELECT 'device_requests_audit_trail.details behind an already-erased live row' AS what, count(*) AS n FROM device_requests_audit_trail dat JOIN device_requests d ON dat.id = d.id WHERE d.updated_at <= (current_date - interval '26 weeks') AND dat.details <> 'RECORD DELETED BY SYSTEM - GDPR';
SELECT 'device_requests_notes past 52wk unscrubbed' AS what, count(*) AS n FROM device_requests_notes WHERE updated_at <= (current_date - interval '52 weeks') AND content <> 'Note content deleted due to GDPR policy';
SELECT 'referring_organisation_contacts past 12mo unscrubbed' AS what, count(*) AS n FROM referring_organisation_contacts WHERE updated_at <= (current_date - interval '12 months') AND full_name <> 'Contact - Erased due to GDPR policy';
SELECT 'kits with coordinates' AS what, count(*) AS n FROM kits WHERE coordinates IS NOT NULL;
SELECT 'gdpr_cleanup_runs row count' AS what, count(*) AS n FROM gdpr_cleanup_runs;

create or replace view gdpr.donors_to_archive as
  select donors.id, donors.name, donors.created_at,
         coalesce(max(kits.created_at), donors.created_at) as kits_max_created_at
    from donors
    left join kits on donors.id = kits.donor_id
   where donors.name <> 'Donor - Erased due to GDPR policy'
   group by kits.donor_id, donors.id
  having coalesce(max(kits.created_at), donors.created_at)
         <= (current_date - INTERVAL '12 months');

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
      device_request_details_audit_archived_count numeric;
      device_request_clientref_audit_archived_count numeric;
      device_request_contact_name_archived_count numeric;
      device_request_contact_name_audit_archived_count numeric;
      device_request_notes_archived_count numeric;
      referring_contact_archived_count numeric;
      referring_contact_audit_archived_count numeric;
      kit_coordinates_archived_count numeric;
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

      -- Correction 3. Placed after the donor erasure above so the donor-erased branch also
      -- catches donors erased by THIS run, not only by a previous one.
      UPDATE public.kits k
      SET coordinates = NULL
      WHERE k.coordinates IS NOT NULL
            AND (k.created_at <= (CURRENT_DATE - '12 months'::interval)
                 OR EXISTS (SELECT 1 FROM public.donors d
                             WHERE d.id = k.donor_id
                               AND d.name = 'Donor - Erased due to GDPR policy'));

      GET DIAGNOSTICS kit_coordinates_archived_count = ROW_COUNT;

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

      UPDATE public.device_requests
      SET collection_contact_name = 'WIPED - GDPR'
      WHERE updated_at <= (CURRENT_DATE - '52 weeks'::interval)
            AND collection_contact_name IS NOT NULL
            AND collection_contact_name <> 'WIPED - GDPR';

      GET DIAGNOSTICS device_request_contact_name_archived_count = ROW_COUNT;

      -- Correction 4 applies to the next three statements: the added OR branch keys off the
      -- LIVE row already carrying the sentinel, so these rows can no longer hide behind a
      -- refreshed updated_at. Widening only.
      UPDATE public.device_requests_audit_trail dat
      SET details = 'RECORD DELETED BY SYSTEM - GDPR'
      FROM public.device_requests d
      WHERE dat.id = d.id
            AND (d.updated_at <= (CURRENT_DATE - '26 weeks'::interval)
                 OR d.details = 'RECORD DELETED BY SYSTEM - GDPR')
            AND dat.details <> 'RECORD DELETED BY SYSTEM - GDPR';

      GET DIAGNOSTICS device_request_details_audit_archived_count = ROW_COUNT;

      UPDATE public.device_requests_audit_trail dat
      SET client_ref = 'WIPED - GDPR'
      FROM public.device_requests d
      WHERE dat.id = d.id
            AND (d.updated_at <= (CURRENT_DATE - '52 weeks'::interval)
                 OR d.client_ref = 'WIPED - GDPR')
            AND dat.client_ref <> 'WIPED - GDPR';

      GET DIAGNOSTICS device_request_clientref_audit_archived_count = ROW_COUNT;

      UPDATE public.device_requests_audit_trail dat
      SET collection_contact_name = 'WIPED - GDPR'
      FROM public.device_requests d
      WHERE dat.id = d.id
            AND (d.updated_at <= (CURRENT_DATE - '52 weeks'::interval)
                 OR d.collection_contact_name = 'WIPED - GDPR')
            AND dat.collection_contact_name IS NOT NULL
            AND dat.collection_contact_name <> 'WIPED - GDPR';

      GET DIAGNOSTICS device_request_contact_name_audit_archived_count = ROW_COUNT;

      -- UNCHANGED at 52 weeks, deliberately - see note 0 in this file's header. The
      -- retention spreadsheet says 12 months for this field. Do not "fix" this to 26 weeks
      -- on the strength of #98/#96/#126/PR #130, all of which predate the spreadsheet.
      UPDATE public.device_requests_notes n
      SET content = 'Note content deleted due to GDPR policy'
      WHERE n.updated_at <= (CURRENT_DATE - '52 weeks'::interval)
            AND n.content <> 'Note content deleted due to GDPR policy';

      GET DIAGNOSTICS device_request_notes_archived_count = ROW_COUNT;

      UPDATE public.referring_organisation_contacts c
      SET full_name = 'Contact - Erased due to GDPR policy',
        email = '',
        phone_number = '',
        address = ''
      WHERE c.updated_at <= (CURRENT_DATE - '12 months'::interval)
            AND c.full_name <> 'Contact - Erased due to GDPR policy';

      GET DIAGNOSTICS referring_contact_archived_count = ROW_COUNT;

      -- Correction 4, extended: the same already-erased-live-row OR branch applied here.
      UPDATE public.referring_organisation_contacts_audit_trail cat
      SET full_name = 'Contact - Erased due to GDPR policy',
        email = '',
        phone_number = '',
        address = ''
      FROM public.referring_organisation_contacts c
      WHERE cat.id = c.id
            AND (c.updated_at <= (CURRENT_DATE - '12 months'::interval)
                 OR c.full_name = 'Contact - Erased due to GDPR policy')
            AND cat.full_name <> 'Contact - Erased due to GDPR policy';

      GET DIAGNOSTICS referring_contact_audit_archived_count = ROW_COUNT;

      result = format(
        'GDPR Cleanup: Archived %s inactive donors, %s audit trail donor records, %s device request details, %s device request client refs, %s device request contact names, %s audit device request details, %s audit device request client refs, %s audit device request contact names, %s device request notes, %s referring contacts, %s audit referring contacts, %s kit coordinates',
        donor_archived_count, donor_audit_trail_archived_count,
        device_request_details_archived_count, device_request_clientref_archived_count,
        device_request_contact_name_archived_count,
        device_request_details_audit_archived_count, device_request_clientref_audit_archived_count,
        device_request_contact_name_audit_archived_count,
        device_request_notes_archived_count,
        referring_contact_archived_count, referring_contact_audit_archived_count,
        kit_coordinates_archived_count
      );

      RAISE LOG '%', result;

      INSERT INTO public.gdpr_cleanup_runs (
        donor_count, donor_audit_count, device_request_details_count, device_request_clientref_count,
        device_request_contact_name_count, device_request_details_audit_count,
        device_request_clientref_audit_count, device_request_contact_name_audit_count,
        device_request_notes_count, referring_contact_count, referring_contact_audit_count,
        kit_coordinates_count, summary
      ) VALUES (
        donor_archived_count, donor_audit_trail_archived_count,
        device_request_details_archived_count, device_request_clientref_archived_count,
        device_request_contact_name_archived_count,
        device_request_details_audit_archived_count, device_request_clientref_audit_archived_count,
        device_request_contact_name_audit_archived_count,
        device_request_notes_archived_count,
        referring_contact_archived_count, referring_contact_audit_archived_count,
        kit_coordinates_archived_count, result
      );

      RETURN result;
    END;
$function$;

GRANT USAGE ON SCHEMA gdpr TO api_prod;
GRANT EXECUTE ON FUNCTION gdpr.performgdprcleanup() TO api_prod;

-- DELIBERATELY NOT RUN HERE. The UAT counterpart ended by calling the function as
-- techaid_admin; production does not, because that would prove the wrong thing.
--
--     SELECT gdpr.performgdprcleanup() AS run_summary;
--
-- The grants two lines above are the ONLY prerequisite of the in-app job that has never been
-- exercised in production - measured 2026-08-12, prod's gdpr schema had nspacl = NULL, i.e. no
-- role had ever held anything on it. Running the function as its owner exercises the function
-- body (already proven in UAT on 2026-08-12) while leaving the grant untested, and it writes a
-- fresh row to gdpr_cleanup_runs, which suppresses the very startup catch-up we want to observe.
--
-- Instead, leave the backlog in place and let the application clear it, as api_prod, on the real
-- code path:
--   1. (this script) apply the view + function + grants. No data changes.
--   2. UPDATE feature_flags SET enabled = true WHERE flag_key = 'gdpr-in-app-cleanup';
--   3. Restart api-production. gdpr_cleanup_runs is empty, so lastRunAt() returns NULL,
--      GdprDonorCleanup.catchUpOnStartup() sees "overdue", and the job runs as api_prod.
--   4. Confirm the run: a row in gdpr_cleanup_runs, and "GDPR retention cleanup finished" in
--      the container logs. That satisfies issue #62's guard for unscheduling pg_cron.
--   5. Only then: db/admin/2026-08-13__disable_pg_cron_gdpr_weekly_prod.sql, then the
--      gdpr_cleanup_runs backfill (2026-08-12__backfill_gdpr_cleanup_runs_from_logs.sql) LAST -
--      its rows are dated to 2026-08-08 and would make the job look recently-run if applied
--      before step 3.
--
-- If step 3 fails, GdprDonorCleanup catches and logs the exception without crashing the app;
-- the fallback is to uncomment the line above and run it as techaid_admin, exactly as UAT did.

SELECT '=== AFTER: schema applied, data deliberately UNCHANGED ===' AS section;
SELECT 'api_prod has USAGE on gdpr (expect t)' AS what, has_schema_privilege('api_prod', 'gdpr', 'USAGE')::text AS n;
SELECT 'api_prod has EXECUTE on performgdprcleanup (expect t)' AS what, has_function_privilege('api_prod', 'gdpr.performgdprcleanup()', 'EXECUTE')::text AS n;

-- Backlog should be UNCHANGED from the BEFORE block above - this script mutates no data.
-- Measured 2026-08-12 (read-only, ahead of this script's intended run), for reference, against
-- the CORRECTED view/function:
--   donors eligible under the NEW view .......................... 149
--   device_requests.details past 26wk ............................ 13
--   device_requests.client_ref past 52wk ......................... 15
--   device_requests.collection_contact_name past 52wk ............. 0
--   audit details behind an already-erased live row ............ 6429  (+1069 via the new OR branch)
--   audit client_ref behind an already-erased live row ......... 3162  (+471 via the new OR branch)
--   device_requests_notes past 52wk ............................ 4981
--   referring_organisation_contacts past 12mo ................... 920
--   referring_organisation_contacts audit past 12mo ............. 293
--   kits holding coordinates ................................... 3930
SELECT 'donors eligible under the NEW view (expect ~149, unchanged)' AS what, count(*) AS n FROM gdpr.donors_to_archive;
SELECT 'device_requests.details past 26wk unscrubbed (expect ~13, unchanged)' AS what, count(*) AS n FROM device_requests WHERE updated_at <= (current_date - interval '26 weeks') AND details <> 'RECORD DELETED BY SYSTEM - GDPR';
SELECT 'device_requests.client_ref past 52wk unscrubbed (expect ~15, unchanged)' AS what, count(*) AS n FROM device_requests WHERE updated_at <= (current_date - interval '52 weeks') AND client_ref <> 'WIPED - GDPR';
SELECT 'device_requests_audit_trail.details behind an already-erased live row (expect ~6429, unchanged)' AS what, count(*) AS n FROM device_requests_audit_trail dat JOIN device_requests d ON dat.id = d.id WHERE d.updated_at <= (current_date - interval '26 weeks') AND dat.details <> 'RECORD DELETED BY SYSTEM - GDPR';
SELECT 'device_requests_notes past 52wk unscrubbed (expect ~4981, unchanged)' AS what, count(*) AS n FROM device_requests_notes WHERE updated_at <= (current_date - interval '52 weeks') AND content <> 'Note content deleted due to GDPR policy';
SELECT 'referring_organisation_contacts past 12mo unscrubbed (expect ~920, unchanged)' AS what, count(*) AS n FROM referring_organisation_contacts WHERE updated_at <= (current_date - interval '12 months') AND full_name <> 'Contact - Erased due to GDPR policy';
SELECT 'kits holding coordinates (expect ~3930, unchanged)' AS what, count(*) AS n FROM kits WHERE coordinates IS NOT NULL;

SELECT '=== gdpr_cleanup_runs (expect 0 rows - the app writes the first one) ===' AS section;
SELECT count(*) AS rows_so_far FROM gdpr_cleanup_runs;
