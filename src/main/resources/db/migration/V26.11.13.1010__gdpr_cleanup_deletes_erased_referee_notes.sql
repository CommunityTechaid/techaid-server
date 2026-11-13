-- #374: gdpr.performgdprcleanup() deletes the notes of erased referee contacts.
--
-- The cleanup blanks an erased referee's name, email, phone and address but never touched
-- referring_organisation_contacts_notes, so free-text staff notes about the person were kept
-- forever. Owner decisions (2026-11-13):
--   1. the notes are DELETED (rows removed), not blanked;
--   2. contacts erased by earlier runs are covered too - the first run after this ships deletes
--      the notes of every contact carrying the erased marker (951 such contacts in production
--      on 2026-11-12; how many have notes is counted before merge, not assumed).
--
-- SCOPE. The DELETE matches on the contact's own erased marker ONLY:
--     c.full_name = 'Contact - Erased due to GDPR policy'
-- the literal the erasure UPDATE in this same function writes, the one the audit-trail scrub's
-- already-erased branch reads (#129), and ReferringOrganisationContact.GDPR_ERASED_NAME that the
-- #372 read-only guard checks. No timestamp, organisation or archived condition: the 2026-08-12
-- incident erased 920 referees through a rule keyed on a timestamp (fixed in V26.08.13.1200), and
-- a deleted note, unlike a blanked field, leaves nothing behind. This statement can only reach a
-- contact that is already erased, so it adds no new way to select a person for erasure.
-- GdprCleanupFunctionBodyTest pins the statement's text; GdprErasedRefereeNotesTest its effect.
--
-- AUDIT. The notes are not audited: ReferringOrganisationContactNote has no @Audited and the
-- contact's notes collection is @NotAudited. The only history table that ever existed for them,
-- referring_organisation_contacts_notes_aud, held 0 rows and was dropped from production on
-- 2026-08-11 (db/admin/2026-08-11__drop_orphan_aud_tables.sql); UAT and fresh databases never had
-- it. So deleting the live row leaves no copy of the text in the database (backups and
-- point-in-time restore aside).
--
-- IDEMPOTENT AND CHEAP. Once an erased contact has no notes there is nothing left to match, so the
-- Friday run and every weekday 08:00 cold-start catch-up delete nothing and record 0.
--
-- The body below is V26.08.18.1600's with exactly four additions and nothing else:
--   1. the referring_contact_notes_deleted_count declaration
--   2. the DELETE and its GET DIAGNOSTICS, after the referee audit-trail scrub (so contacts
--      erased earlier in the same run are included)
--   3. `%s referring contact notes` in the summary format string, and its argument
--   4. referring_contact_notes_count in the gdpr_cleanup_runs INSERT (column added by
--      V26.11.13.1000, which therefore runs first)
--
-- In UAT and production this file SELF-GATES and does nothing (the app roles have no CREATE on
-- the gdpr schema). There the function changes only when techaid_admin runs
-- db/admin/2026-11-13__admin_apply_gdpr_delete_erased_referee_notes.sql, after the deploy that
-- ran V26.11.13.1000. A green Flyway row here proves nothing about UAT or production.

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
$function$
    $fn$;
    RAISE NOTICE 'gdpr.performgdprcleanup() now deletes the notes of erased referee contacts.';
END $mig$;
