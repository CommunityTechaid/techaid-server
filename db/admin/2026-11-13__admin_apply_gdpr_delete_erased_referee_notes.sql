-- ADMIN-APPLIED counterpart to V26.11.13.1010 (#374). Run as techaid_admin against techaid_uat,
-- then techaid_prod, each AFTER the app deploy that ran V26.11.13.1000 in that environment.
--
-- APPLIED TO
--   techaid_uat   not yet
--   techaid_prod  not yet - change control: the first run afterwards DELETES the notes of every
--                 contact already carrying the erased marker. Count first with the read-only
--                 check script, and have a point-in-time restore window in hand.
--
-- WHY THIS EXISTS. gdpr.performgdprcleanup() is owned by techaid_admin and lives in the gdpr
-- schema, on which api_uat/api_prod have no CREATE. V26.11.13.1010 therefore self-gates and no-ops
-- in those environments; the function only changes here.
--
-- WHAT IT CHANGES. Body = V26.08.18.1600's (the body applied by
-- 2026-08-18__admin_apply_gdpr_drop_coordinates_scrub.sql) plus four additions - see the
-- V26.11.13.1010 header for the itemised list. The new statement:
--     DELETE FROM public.referring_organisation_contacts_notes n
--     USING public.referring_organisation_contacts c
--     WHERE n.referring_organisation_contact_id = c.id
--           AND c.full_name = 'Contact - Erased due to GDPR policy';
-- Scoped by the erased marker only - see the migration header for why.
--
-- THIS SCRIPT DELETES NOTHING ITSELF. It only replaces the function. The deletion happens on the
-- next run of the cleanup (Friday 18:00 London, or the next cold-start catch-up if a run is overdue).
--
-- PRIVILEGE. The function is SECURITY INVOKER and the in-app job runs it as api_uat/api_prod, so
-- that role needs DELETE on referring_organisation_contacts_notes. The check script prints it.
--
-- SAFE TO RUN TWICE. CREATE OR REPLACE, no data change.

\pset pager off
\set ON_ERROR_STOP on

\echo '=== GUARD ==='
DO $$ BEGIN
    IF current_database() NOT IN ('techaid_prod', 'techaid_uat') THEN
        RAISE EXCEPTION 'ABORT: connected to %', current_database();
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = 'gdpr_cleanup_runs'
                      AND column_name = 'referring_contact_notes_count') THEN
        RAISE EXCEPTION 'ABORT: gdpr_cleanup_runs.referring_contact_notes_count is missing - deploy the app (V26.11.13.1000) first';
    END IF;
    RAISE NOTICE 'database: %, user: %', current_database(), current_user;
END $$;

\echo '=== BEFORE: the live function does not yet delete referee notes (expect false) ==='
SELECT position('DELETE FROM public.referring_organisation_contacts_notes' IN prosrc) > 0 AS deletes_referee_notes,
       md5(prosrc) AS md5, length(prosrc) AS len
  FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
 WHERE n.nspname = 'gdpr' AND p.proname = 'performgdprcleanup';

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
      referring_contact_notes_deleted_count numeric;
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

      -- #374: the notes of an erased referee contact are DELETED (owner decision 2026-11-13),
      -- including contacts erased by earlier runs. Scoped by the contact's own erased marker
      -- and NOTHING else - the same full_name the UPDATE above writes and the audit-trail
      -- scrub's already-erased branch reads. No timestamp, organisation or archived condition:
      -- the 2026-08-12 over-erasure came from a timestamp that did not mean what it seemed to,
      -- and a deleted note cannot be restored without a point-in-time restore. Idempotent:
      -- once an erased contact has no notes there is nothing left to match.
      DELETE FROM public.referring_organisation_contacts_notes n
      USING public.referring_organisation_contacts c
      WHERE n.referring_organisation_contact_id = c.id
            AND c.full_name = 'Contact - Erased due to GDPR policy';

      GET DIAGNOSTICS referring_contact_notes_deleted_count = ROW_COUNT;

      result = format(
        'GDPR Cleanup: Archived %s inactive donors, %s audit trail donor records, %s device request details, %s device request client refs, %s device request contact names, %s audit device request details, %s audit device request client refs, %s audit device request contact names, %s device request notes, %s referring contacts, %s audit referring contacts, %s referring contact notes',
        donor_archived_count, donor_audit_trail_archived_count,
        device_request_details_archived_count, device_request_clientref_archived_count,
        device_request_contact_name_archived_count,
        device_request_details_audit_archived_count, device_request_clientref_audit_archived_count,
        device_request_contact_name_audit_archived_count,
        device_request_notes_archived_count,
        referring_contact_archived_count, referring_contact_audit_archived_count,
        referring_contact_notes_deleted_count
      );

      RAISE LOG '%', result;

      INSERT INTO public.gdpr_cleanup_runs (
        donor_count, donor_audit_count, device_request_details_count, device_request_clientref_count,
        device_request_contact_name_count, device_request_details_audit_count,
        device_request_clientref_audit_count, device_request_contact_name_audit_count,
        device_request_notes_count, referring_contact_count, referring_contact_audit_count,
        referring_contact_notes_count, summary
      ) VALUES (
        donor_archived_count, donor_audit_trail_archived_count,
        device_request_details_archived_count, device_request_clientref_archived_count,
        device_request_contact_name_archived_count,
        device_request_details_audit_archived_count, device_request_clientref_audit_archived_count,
        device_request_contact_name_audit_archived_count,
        device_request_notes_archived_count,
        referring_contact_archived_count, referring_contact_audit_archived_count,
        referring_contact_notes_deleted_count, result
      );

      RETURN result;
    END;
$function$;

\echo '=== AFTER: it must delete referee notes (expect true) ==='
SELECT position('DELETE FROM public.referring_organisation_contacts_notes' IN prosrc) > 0 AS deletes_referee_notes,
       md5(prosrc) AS md5, length(prosrc) AS len
  FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
 WHERE n.nspname = 'gdpr' AND p.proname = 'performgdprcleanup';
