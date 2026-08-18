-- ADMIN-APPLIED counterpart to V26.08.18.1600. Run as techaid_admin against techaid_uat and
-- techaid_prod BEFORE V26.08.18.1700 drops the coordinates columns.
--
-- WHY THIS EXISTS. gdpr.performgdprcleanup() is owned by techaid_admin and lives in the gdpr
-- schema, on which api_uat/api_prod have no CREATE. V26.08.18.1600 therefore self-gates and
-- no-ops in those environments - it records a successful Flyway row having done nothing. The
-- function only changes here.
--
-- WHY IT IS URGENT ONCE THE COLUMNS GO. A plpgsql body resolves column names at EXECUTION.
-- A stale function does not fail the migration; it fails the next Friday 18:00 retention run,
-- and since 2026-08-18 that job is the only thing performing retention, with no alert on it
-- yet (#174). The failure would be a silent stop to GDPR erasure.
--
-- Body is production's live prosrc (md5 0142fe9a6dabebb6f6217ecba12342e1) with the six
-- coordinates deletions and nothing else - see V26.08.18.1600 for the itemised list.
--
-- SAFE TO RUN TWICE. CREATE OR REPLACE, no data change.

\pset pager off
\set ON_ERROR_STOP on

\echo '=== GUARD ==='
DO $$ BEGIN
    IF current_database() NOT IN ('techaid_prod', 'techaid_uat') THEN
        RAISE EXCEPTION 'ABORT: connected to %', current_database();
    END IF;
    RAISE NOTICE 'database: %, user: %', current_database(), current_user;
END $$;

\echo '=== BEFORE: the live function still mentions coordinates ==='
SELECT count(*) FILTER (WHERE prosrc ILIKE '%coordinates%') AS mentions_coordinates_expect_1,
       md5(prosrc) AS md5
  FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
 WHERE n.nspname = 'gdpr' AND p.proname = 'performgdprcleanup'
 GROUP BY prosrc;

\echo '=== REPLACE ==='
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
    BEGIN

      UPDATE public.donors d
      SET name = 'Donor - Erased due to GDPR policy',
        email = '',
        phone_number = '',
        post_code = '',
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

      -- Correction 5 (incident 2026-08-12). Retention for a referring contact is now scoped
      -- by ACTIVITY, not by the contact row's own updated_at. A referee's row is only
      -- touched when someone edits the contact itself; it does NOT move when that referee
      -- submits device requests. Keying erasure to it therefore erased referees who had
      -- been referring continuously - including five with deliveries in flight.
      -- gdpr.referring_contacts_to_archive encodes "last activity" the way
      -- gdpr.donors_to_archive already does for donors (max over the child rows).
      UPDATE public.referring_organisation_contacts c
      SET full_name = 'Contact - Erased due to GDPR policy',
        email = '',
        phone_number = '',
        address = ''
      FROM gdpr.referring_contacts_to_archive rca
      WHERE c.id = rca.id
            AND c.full_name <> 'Contact - Erased due to GDPR policy';

      GET DIAGNOSTICS referring_contact_archived_count = ROW_COUNT;

      -- Correction 4, extended: the same already-erased-live-row OR branch applied here.
      -- Correction 4 preserved: "live row already erased OR live row out of retention".
      -- Only the second half changes - it now consults the activity-scoped view. The
      -- already-erased branch is what keeps history erasable once the live row is gone.
      UPDATE public.referring_organisation_contacts_audit_trail cat
      SET full_name = 'Contact - Erased due to GDPR policy',
        email = '',
        phone_number = '',
        address = ''
      FROM public.referring_organisation_contacts c
      WHERE cat.id = c.id
            AND (EXISTS (SELECT 1 FROM gdpr.referring_contacts_to_archive rca
                          WHERE rca.id = c.id)
                 OR c.full_name = 'Contact - Erased due to GDPR policy')
            AND cat.full_name <> 'Contact - Erased due to GDPR policy';

      GET DIAGNOSTICS referring_contact_audit_archived_count = ROW_COUNT;

      result = format(
        'GDPR Cleanup: Archived %s inactive donors, %s audit trail donor records, %s device request details, %s device request client refs, %s device request contact names, %s audit device request details, %s audit device request client refs, %s audit device request contact names, %s device request notes, %s referring contacts, %s audit referring contacts',
        donor_archived_count, donor_audit_trail_archived_count,
        device_request_details_archived_count, device_request_clientref_archived_count,
        device_request_contact_name_archived_count,
        device_request_details_audit_archived_count, device_request_clientref_audit_archived_count,
        device_request_contact_name_audit_archived_count,
        device_request_notes_archived_count,
        referring_contact_archived_count, referring_contact_audit_archived_count
      );

      RAISE LOG '%', result;

      INSERT INTO public.gdpr_cleanup_runs (
        donor_count, donor_audit_count, device_request_details_count, device_request_clientref_count,
        device_request_contact_name_count, device_request_details_audit_count,
        device_request_clientref_audit_count, device_request_contact_name_audit_count,
        device_request_notes_count, referring_contact_count, referring_contact_audit_count,
        summary
      ) VALUES (
        donor_archived_count, donor_audit_trail_archived_count,
        device_request_details_archived_count, device_request_clientref_archived_count,
        device_request_contact_name_archived_count,
        device_request_details_audit_archived_count, device_request_clientref_audit_archived_count,
        device_request_contact_name_audit_archived_count,
        device_request_notes_archived_count,
        referring_contact_archived_count, referring_contact_audit_archived_count, result
      );

      RETURN result;
    END;
$function$;

\echo '=== AFTER: it must mention coordinates nowhere ==='
SELECT count(*) FILTER (WHERE prosrc ILIKE '%coordinates%') AS mentions_coordinates_must_be_0,
       md5(prosrc) AS md5, length(prosrc) AS len
  FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
 WHERE n.nspname = 'gdpr' AND p.proname = 'performgdprcleanup'
 GROUP BY prosrc;

\echo '=== the columns are still there; this script does not drop them ==='
SELECT table_name, column_name
  FROM information_schema.columns
 WHERE column_name = 'coordinates' AND table_schema = 'public'
 ORDER BY table_name;
