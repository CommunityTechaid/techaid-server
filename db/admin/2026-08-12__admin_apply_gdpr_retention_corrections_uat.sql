-- Brings UAT's already-applied gdpr.donors_to_archive view and gdpr.performgdprcleanup()
-- function up to V26.08.12.1100's corrected policy. UAT's SUPERSEDED bodies (V26.08.11.1400/
-- .1650) were admin-applied on 2026-08-12 - see
-- db/admin/2026-08-12__admin_apply_gdpr_retention_scope_uat.sql. This script replaces them
-- with the corrected versions. Confirmed by the maintainer 2026-08-12 after the decision
-- record was re-read end to end; see V26.08.12.1100 for the full corrections write-up.
--
-- Three corrections carried by this script (2, 3, 4 below - numbering aligned with
-- V26.08.12.1100), plus note 0 recording a change deliberately NOT made:
--   0. NOT A CHANGE, recorded because it was very nearly made: device_requests_notes.content
--      STAYS AT 52 WEEKS. The authoritative source is the team's retention spreadsheet,
--      "GDPR data removal review 26-08-11.xlsx", sheet "Requests" row 5, which says 12 months
--      for this field. #98/#96/#126/PR #130 all say 26 weeks and all predate the spreadsheet.
--      The spreadsheet outranks the issue comments.
--   2. gdpr.donors_to_archive: both remaining business exclusions dropped - the
--      '%#(business|droppoint)%' name tag and donor_parents.type <> 'BUSINESS'. (The
--      is_lead_contact exemption was already dropped by V26.08.11.1400; it is not one of
--      these two.)
--   3. kits.coordinates newly in scope: NULLed once past 12 months, or immediately when the
--      owning donor is already erased.
--   4. device_requests_audit_trail / referring_organisation_contacts_audit_trail: an OR-branch
--      gap closed, so rows behind an already-erased live row are reachable regardless of what
--      that live row's updated_at now says.
--
-- GRANTS: api_uat was already granted USAGE on gdpr and EXECUTE on gdpr.performgdprcleanup() on
-- 2026-08-12 (see the script named above); this file does not repeat those grants, only
-- verifies in the AFTER block that they are still in place.
--
-- UAT's data has already been scrubbed clean by that earlier run, so the BEFORE/AFTER blocks
-- below carry no hard-coded expected counts - they are for eyeballing, not a gate.
--
-- THIS SCRIPT DOES NOT CALL gdpr.performgdprcleanup(). It only replaces the view/function and
-- verifies the existing grants; it does not touch data.
--
-- APPLIED TO
--   techaid_uat, 2026-08-12 13:08 UTC, against UAT running 2.5.1 / 1c53641.
--   Result: clean apply. Verified afterwards -
--     * gdpr.donors_to_archive no longer contains the name tag or the donor_parents join
--     * gdpr.performgdprcleanup() carries all four OR-branches, the kits.coordinates scrub,
--       the kit_coordinates_count recording, and notes still at 52 weeks
--     * CREATE OR REPLACE FUNCTION PRESERVED the api_uat grants (USAGE + EXECUTE still true)
--       - worth knowing, because this script does not re-grant them
--     * smoke run of gdpr.performgdprcleanup() returned all zeros and recorded run id 2
--   CAVEAT: UAT had already been scrubbed clean on 2026-08-11, and holds 0 kits with
--   coordinates and 0 donors past 12 months, so the kits.coordinates statement and the
--   widened donor predicate were exercised structurally here but NOT against real data.
--   Their behavioural coverage comes from GdprSchemaConvergenceTest, which seeds those cases.

DO $$ BEGIN IF current_database() <> 'techaid_uat' THEN RAISE EXCEPTION 'ABORT: %', current_database(); END IF; END $$;

SELECT '=== BEFORE ===' AS section;
SELECT 'donors eligible (old view, business exclusions still applied)' AS what, count(*) AS n FROM gdpr.donors_to_archive;
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

SELECT '=== AFTER: schema corrected, data deliberately UNCHANGED ===' AS section;
SELECT 'api_uat has USAGE on gdpr (expect t, granted 2026-08-12)' AS what, has_schema_privilege('api_uat', 'gdpr', 'USAGE')::text AS n;
SELECT 'api_uat has EXECUTE on performgdprcleanup (expect t, granted 2026-08-12)' AS what, has_function_privilege('api_uat', 'gdpr.performgdprcleanup()', 'EXECUTE')::text AS n;

SELECT 'donors eligible under the NEW view' AS what, count(*) AS n FROM gdpr.donors_to_archive;
SELECT 'device_requests.details past 26wk unscrubbed' AS what, count(*) AS n FROM device_requests WHERE updated_at <= (current_date - interval '26 weeks') AND details <> 'RECORD DELETED BY SYSTEM - GDPR';
SELECT 'device_requests.client_ref past 52wk unscrubbed' AS what, count(*) AS n FROM device_requests WHERE updated_at <= (current_date - interval '52 weeks') AND client_ref <> 'WIPED - GDPR';
SELECT 'device_requests_audit_trail.details behind an already-erased live row' AS what, count(*) AS n FROM device_requests_audit_trail dat JOIN device_requests d ON dat.id = d.id WHERE d.updated_at <= (current_date - interval '26 weeks') AND dat.details <> 'RECORD DELETED BY SYSTEM - GDPR';
SELECT 'referring_organisation_contacts past 12mo unscrubbed' AS what, count(*) AS n FROM referring_organisation_contacts WHERE updated_at <= (current_date - interval '12 months') AND full_name <> 'Contact - Erased due to GDPR policy';
SELECT 'kits with coordinates' AS what, count(*) AS n FROM kits WHERE coordinates IS NOT NULL;

SELECT '=== gdpr_cleanup_runs (expect unchanged - this script does not run the function) ===' AS section;
SELECT count(*) AS rows_so_far FROM gdpr_cleanup_runs;
