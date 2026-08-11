-- GDPR historical scrub — one-off backfill of every record already past retention.
-- Issue #126. Decisions recorded on #98 (retention scope meeting, 2026-08-11).
--
-- APPLIED TO
--   techaid_uat   2026-08-11  (rehearsal) - SUCCEEDED, see results below
--   techaid_prod  PENDING     (planned Thursday 2026-08-13)
--
-- UAT REHEARSAL RESULT, 2026-08-11
--   donors scrubbed                         145
--   donors_audit_trail rows scrubbed         13
--   device_requests.details wiped           408
--   device_requests.client_ref wiped        439
--   device_requests.collection_contact_name   2
--   audit_trail.details wiped                67
--   audit_trail.client_ref wiped             40
--   audit_trail.collection_contact_name       0
--   device_requests_notes.content wiped   2,916
--   Every count matched the pre-flight measurement exactly. All twelve checks in
--   db/admin/gdpr_retention_verification.sql returned 0 afterwards, including the three
--   special-category checks. Pre-scrub snapshot of all five tables:
--   C:\Users\tonya\Desktop\gdpr-uat-rehearsal-backup-2026-08-11\
--
-- PRODUCTION BACKLOG AS MEASURED 2026-08-11 (what Thursday will erase)
--   donors                                  149   + 78 donors_audit_trail rows
--   device_requests.details                   8
--   device_requests.client_ref                0
--   device_requests.collection_contact_name   0
--   device_requests_notes.content         6,000   <-- 96.6% of the whole table, see WARNING
--   audit_trail.details                   7,483   of which ~1,900 carry special-category text
--   audit_trail.client_ref                3,568
--
-- WARNING - device_requests_notes blast radius
--   These notes are written once and effectively never updated, so almost the entire table is
--   past 26 weeks: 6,000 of 6,208 rows in production, going back to 2020-05-04. Applying the
--   details clock to them erases six years of operational history in one pass. Only 26 of the
--   6,000 carry a special-category indicator. Confirm this is intended before Thursday - the
--   scope decision was taken on the assumption the notes were a recent addition.
--
-- WHY THIS IS NOT A FLYWAY MIGRATION
--   It is a one-off data change, not schema. It is also deliberately separate from the
--   forward-looking rule change to gdpr.donors_to_archive and gdpr.performgdprcleanup(),
--   which cannot run as the application role: every gdpr object is owned by techaid_admin
--   while the app connects as api_uat / api_prod. This script touches only `public` tables,
--   which ARE owned by the app role, so it runs without the admin credential.
--
-- WHAT IT DOES
--   Applies the retention rules as decided on 2026-08-11 to the whole existing backlog:
--
--   Donors, 12 months since the most recent associated device (kits.created_at), falling back
--   to donors.created_at. No exemption for lead contacts, business-attached donors, or the
--   vestigial #business/#droppoint name tag. Parent organisation records (donor_parents) are
--   NOT touched — that is generic business/droppoint information and is out of scope.
--
--   Device requests, on the existing clocks: details at 26 weeks, client_ref at 52 weeks,
--   collection_contact_name at 52 weeks (new).
--
--   Device request AUDIT TRAIL, matching the live row. The predicate is deliberately
--   `live row already wiped OR live row past the threshold`, not just the threshold: a request
--   wiped in the past whose live row has since been touched would otherwise keep its original
--   text in history forever, because a forward-looking rule has no reason to revisit it.
--
--   Device request notes, on the same 26-week clock as details (#98 decision 1).
--
-- WHAT IT DOES NOT TOUCH
--   * donor_parents / donor_parents_audit_trail   - organisational, explicitly out of scope
--   * note / note_aud (kit notes)                 - out of scope, the phone numbers are SIM
--                                                   card numbers against devices, not people
--   * referring_organisation_contacts*            - undecided, see #129
--   * kit_audit_trail                             - device attributes, not personal data

DO $$
BEGIN
    IF current_database() NOT IN ('techaid_uat', 'techaid_prod') THEN
        RAISE EXCEPTION 'ABORT: unexpected database %', current_database();
    END IF;
    RAISE NOTICE 'Running GDPR historical scrub against %', current_database();
END $$;

BEGIN;

DO $$
DECLARE
    v_donors            bigint;
    v_donor_audit       bigint;
    v_dr_details        bigint;
    v_dr_clientref      bigint;
    v_dr_collection     bigint;
    v_aud_details       bigint;
    v_aud_clientref     bigint;
    v_aud_collection    bigint;
    v_notes             bigint;
BEGIN

    -- 1. DONORS ------------------------------------------------------------------
    -- Individual donor records past 12 months since their most recent device.
    -- Every parent type, lead contact or not.
    CREATE TEMP TABLE _scrub_donors ON COMMIT DROP AS
    SELECT d.id
    FROM donors d
    LEFT JOIN kits k ON k.donor_id = d.id
    WHERE d.name <> 'Donor - Erased due to GDPR policy'
    GROUP BY d.id, d.created_at, d.name
    HAVING COALESCE(max(k.created_at), d.created_at) <= CURRENT_DATE - INTERVAL '1 year';

    UPDATE donors d
       SET name         = 'Donor - Erased due to GDPR policy',
           email        = '',
           phone_number = '',
           post_code    = '',
           coordinates  = NULL,
           referral     = '',
           archived     = 'Y',
           updated_at   = CURRENT_DATE
      FROM _scrub_donors s
     WHERE d.id = s.id;
    GET DIAGNOSTICS v_donors = ROW_COUNT;

    UPDATE donors_audit_trail a
       SET name         = 'Donor - Erased due to GDPR policy',
           email        = '',
           phone_number = '',
           post_code    = '',
           referral     = ''
      FROM _scrub_donors s
     WHERE a.id = s.id;
    GET DIAGNOSTICS v_donor_audit = ROW_COUNT;

    -- 2. DEVICE REQUESTS - live row ------------------------------------------------
    UPDATE device_requests
       SET details = 'RECORD DELETED BY SYSTEM - GDPR'
     WHERE updated_at <= CURRENT_DATE - INTERVAL '26 weeks'
       AND details IS NOT NULL AND details <> ''
       AND details <> 'RECORD DELETED BY SYSTEM - GDPR';
    GET DIAGNOSTICS v_dr_details = ROW_COUNT;

    UPDATE device_requests
       SET client_ref = 'WIPED - GDPR'
     WHERE updated_at <= CURRENT_DATE - INTERVAL '52 weeks'
       AND client_ref IS NOT NULL AND client_ref <> ''
       AND client_ref <> 'WIPED - GDPR';
    GET DIAGNOSTICS v_dr_clientref = ROW_COUNT;

    UPDATE device_requests
       SET collection_contact_name = NULL
     WHERE updated_at <= CURRENT_DATE - INTERVAL '52 weeks'
       AND collection_contact_name IS NOT NULL AND collection_contact_name <> '';
    GET DIAGNOSTICS v_dr_collection = ROW_COUNT;

    -- 3. DEVICE REQUESTS - audit trail ---------------------------------------------
    -- "live already wiped OR live past threshold" - see the header note on why.
    UPDATE device_requests_audit_trail a
       SET details = 'RECORD DELETED BY SYSTEM - GDPR'
      FROM device_requests l
     WHERE l.id = a.id
       AND (l.details = 'RECORD DELETED BY SYSTEM - GDPR'
            OR l.updated_at <= CURRENT_DATE - INTERVAL '26 weeks')
       AND a.details IS NOT NULL AND a.details <> ''
       AND a.details <> 'RECORD DELETED BY SYSTEM - GDPR';
    GET DIAGNOSTICS v_aud_details = ROW_COUNT;

    UPDATE device_requests_audit_trail a
       SET client_ref = 'WIPED - GDPR'
      FROM device_requests l
     WHERE l.id = a.id
       AND (l.client_ref = 'WIPED - GDPR'
            OR l.updated_at <= CURRENT_DATE - INTERVAL '52 weeks')
       AND a.client_ref IS NOT NULL AND a.client_ref <> ''
       AND a.client_ref <> 'WIPED - GDPR';
    GET DIAGNOSTICS v_aud_clientref = ROW_COUNT;

    UPDATE device_requests_audit_trail a
       SET collection_contact_name = NULL
      FROM device_requests l
     WHERE l.id = a.id
       AND (l.collection_contact_name IS NULL
            OR l.updated_at <= CURRENT_DATE - INTERVAL '52 weeks')
       AND a.collection_contact_name IS NOT NULL AND a.collection_contact_name <> '';
    GET DIAGNOSTICS v_aud_collection = ROW_COUNT;

    -- 4. DEVICE REQUEST NOTES --------------------------------------------------------
    -- Same 26-week clock as device_requests.details.
    -- NOTE the blast radius: in production this is ~6,000 of 6,208 rows, because these notes
    -- are written once and never updated, so almost the entire table is past 26 weeks.
    UPDATE device_requests_notes
       SET content = 'RECORD DELETED BY SYSTEM - GDPR'
     WHERE COALESCE(updated_at, created_at) <= CURRENT_DATE - INTERVAL '26 weeks'
       AND content IS NOT NULL AND content <> ''
       AND content <> 'RECORD DELETED BY SYSTEM - GDPR';
    GET DIAGNOSTICS v_notes = ROW_COUNT;

    RAISE NOTICE '=== GDPR historical scrub, database % ===', current_database();
    RAISE NOTICE 'donors scrubbed                        : %', v_donors;
    RAISE NOTICE 'donors_audit_trail rows scrubbed       : %', v_donor_audit;
    RAISE NOTICE 'device_requests.details wiped          : %', v_dr_details;
    RAISE NOTICE 'device_requests.client_ref wiped       : %', v_dr_clientref;
    RAISE NOTICE 'device_requests.collection_contact_name: %', v_dr_collection;
    RAISE NOTICE 'audit_trail.details wiped              : %', v_aud_details;
    RAISE NOTICE 'audit_trail.client_ref wiped           : %', v_aud_clientref;
    RAISE NOTICE 'audit_trail.collection_contact_name    : %', v_aud_collection;
    RAISE NOTICE 'device_requests_notes.content wiped    : %', v_notes;
END $$;

COMMIT;
