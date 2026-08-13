-- Corrects the referring-contact retention scope after the 2026-08-12 over-erasure.
--
-- WHAT WENT WRONG. The referee predicate shipped in V26.08.11.1400 and carried through
-- V26.08.12.1100/1200 read:
--
--     WHERE c.updated_at <= (CURRENT_DATE - '12 months'::interval)
--
-- referring_organisation_contacts.updated_at only moves when the contact record itself is
-- edited. Referring activity lands on device_requests, a child table. A referee who has
-- referred continuously for years but whose own contact details have not been retyped
-- since 2024 therefore looks a year stale on every run. The run that committed at
-- 2026-08-12 16:54:22 UTC erased 920 contacts on that basis; 17 of them were active, and
-- 5 of those had device requests still in PROCESSING_COLLECTION_DELIVERY_ARRANGED.
--
-- WHY THIS SHAPE. gdpr.donors_to_archive already solves the identical problem for donors:
-- it takes coalesce(max(kits.created_at), donors.created_at) rather than trusting the
-- parent row's timestamp. The donor rule was right and the referee rule was written
-- without it. This migration brings referees onto the same footing rather than inventing
-- a second idiom.
--
-- MEASURED IMPACT against production as it stands (2026-08-13):
--   * 920 contacts currently erased
--   * 903 of those the corrected rule would still erase - unchanged, correctly stale
--   *  17 the corrected rule spares - these are the rows the restore puts back
--   *   0 contacts currently intact that the corrected rule would newly catch
-- The last line is the important one: this change only ever narrows what is erased. It
-- cannot widen erasure, so it carries no new GDPR exposure of its own.
--
-- NOT IN SCOPE. referring_organisation_contacts_notes is not consulted as an activity
-- signal. Adding a note is arguably activity, but no note in production is newer than its
-- contact's newest device request, so including it changes nothing today and would widen
-- the join for no measured benefit. Revisit only with evidence.

DO $mig$
DECLARE
    r record;
BEGIN
    IF to_regnamespace('gdpr') IS NULL THEN
        RAISE NOTICE 'gdpr schema absent - skipping';
        RETURN;
    END IF;

    IF NOT has_schema_privilege(current_user, 'gdpr', 'CREATE') THEN
        RAISE NOTICE 'role % has no CREATE on gdpr - skipping (expected in UAT/production)', current_user;
        RETURN;
    END IF;

    EXECUTE $v$
    -- Mirrors gdpr.donors_to_archive: a contact is out of retention only when NOTHING
    -- about it has moved for 12 months - neither the contact row itself nor any device
    -- request raised through it. created_at is the floor for a contact that has never
    -- had a request, so a brand-new referee is never caught.
    create or replace view gdpr.referring_contacts_to_archive as
      select c.id,
             greatest(
               c.updated_at,
               coalesce(max(greatest(d.created_at, d.updated_at)), c.created_at)
             ) as last_activity
        from public.referring_organisation_contacts c
        left join public.device_requests d
               on d.referring_organisation_contact_id = c.id
       group by c.id, c.updated_at, c.created_at
      having greatest(
               c.updated_at,
               coalesce(max(greatest(d.created_at, d.updated_at)), c.created_at)
             ) <= (current_date - INTERVAL '12 months');
    $v$;

    -- The api role reads this view from inside performgdprcleanup(), which runs as the
    -- caller. Same grant donors_to_archive needed in PR #142.
    --
    -- The grantee list is COPIED from gdpr.donors_to_archive rather than hard-coded to
    -- 'api_prod'. That view is the established, already-correct precedent in every
    -- environment, so mirroring it makes this migration right in UAT and production
    -- without naming either. Hard-coding 'api_prod' over-grants in UAT (and vice versa).
    FOR r IN SELECT grantee FROM information_schema.role_table_grants
              WHERE table_schema = 'gdpr'
                AND table_name   = 'donors_to_archive'
                AND privilege_type = 'SELECT'
    LOOP
        EXECUTE format('GRANT SELECT ON gdpr.referring_contacts_to_archive TO %I', r.grantee);
        RAISE NOTICE 'granted SELECT on gdpr.referring_contacts_to_archive to % (mirrors donors_to_archive)', r.grantee;
    END LOOP;

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
$function$
    $fn$;
    RAISE NOTICE 'Referee retention now scoped by activity via gdpr.referring_contacts_to_archive.';
END $mig$;
