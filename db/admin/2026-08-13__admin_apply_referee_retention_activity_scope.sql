-- Applies V26.08.13.1200's gdpr.referring_contacts_to_archive view and the corrected
-- gdpr.performgdprcleanup() to a live database. techaid_admin owns these objects and Flyway
-- runs as api_uat / api_prod, so the migration self-gates to a no-op there (see
-- db/admin/README.md) and this script is how the change actually lands.
--
-- APPLIED: techaid_prod 2026-08-13, techaid_uat 2026-08-13. Both verified after (see below).
--
-- WHY THIS EXISTS - the incident it remediates
--   Referee retention shipped in V26.08.11.1400 keyed on the contact row's own timestamp:
--
--       WHERE c.updated_at <= (CURRENT_DATE - '12 months'::interval)
--
--   referring_organisation_contacts.updated_at only moves when somebody edits the CONTACT
--   RECORD. Referring activity lands on device_requests, a child table, and never touches the
--   parent. A referee referring continuously for years, whose details had not been retyped
--   since 2024, looked a year stale on every run.
--
--   The run that committed 2026-08-12 16:54:22 UTC erased 920 contacts in production on that
--   basis. 17 were active; 5 of those had requests in PROCESSING_COLLECTION_DELIVERY_ARRANGED,
--   i.e. deliveries in flight to people whose referrer had just become uncontactable. UAT was
--   hit the night before by its own run at 2026-08-11 23:27:28 UTC: 1,358 of 1,366 erased,
--   13 of them active.
--
--   Both were restored from point-in-time backups, which were the ONLY surviving source - the
--   audit trail is scrubbed in the same transaction, so it cannot be used for recovery. That
--   is worth remembering before running anything in this directory: for these tables, the
--   backup window is the whole safety net.
--
-- WHAT CHANGES
--   The predicate moves into gdpr.referring_contacts_to_archive, which takes
--   GREATEST(contact.updated_at, max(device request activity)) with contact.created_at as the
--   floor. That is the same shape gdpr.donors_to_archive has always had for donors
--   (coalesce(max(kits.created_at), donors.created_at)); the referee rule was written without
--   that precedent rather than in disagreement with it.
--
--   Correction 4's "live row already erased OR live row out of retention" branch on
--   referring_organisation_contacts_audit_trail is preserved exactly - only the second half of
--   that OR now consults the view. Removing the first half would make history behind an erased
--   contact permanently unreachable.
--
-- MEASURED BEFORE APPLYING - this change only ever NARROWS erasure
--                        production        UAT
--     erased at the time       920        1,359
--     still erased by new rule 903        1,345
--     spared (restored)         17           13
--     NEWLY caught by new rule   0            0
--   The last row is the one that matters: no currently-intact contact becomes eligible, so this
--   carries no new GDPR exposure of its own. It cannot widen erasure, only withhold it.
--
-- GRANTS
--   The grantee list is COPIED from gdpr.donors_to_archive rather than hard-coded. Hard-coding
--   'api_prod' over-granted in UAT and vice versa - that happened during the incident response
--   on 2026-08-13 and had to be revoked. Mirroring the established view is self-correcting.
--
-- HOW IT WAS VERIFIED, both environments
--   1. gdpr.referring_contacts_to_archive exists; grants match gdpr.donors_to_archive.
--   2. No active referee remains erased:
--        WITH la AS (SELECT c.id, c.full_name,
--                           GREATEST(c.updated_at,
--                                    COALESCE(max(GREATEST(d.created_at, d.updated_at)),
--                                             c.created_at)) AS last_activity
--                      FROM referring_organisation_contacts c
--                      LEFT JOIN device_requests d
--                             ON d.referring_organisation_contact_id = c.id
--                     GROUP BY c.id, c.full_name, c.updated_at, c.created_at)
--        SELECT count(*) FROM la
--         WHERE full_name = 'Contact - Erased due to GDPR policy'
--           AND last_activity > CURRENT_DATE - INTERVAL '12 months';   -- must be 0
--   3. BEGIN; SELECT gdpr.performgdprcleanup(); ROLLBACK;  -- must report 0 referring contacts
--      This is the one that counts: it exercises the live function exactly as the app calls it.
--
-- RE-RUNNING IS SAFE. CREATE OR REPLACE throughout, and the DO block self-gates on the gdpr
-- schema being present and the current role holding CREATE on it.

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
