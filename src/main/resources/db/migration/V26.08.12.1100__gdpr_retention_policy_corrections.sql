-- Corrects V26.08.11.1400/.1650 to match the retention policy the team actually decided.
-- Confirmed by the maintainer 2026-08-12 after the decision record was re-read end to end.
--
-- Three corrections (2, 3, 4 below), each with its measured production impact (read-only,
-- 2026-08-12), plus note 0 recording a change that was considered and deliberately NOT made.
-- The numbering is kept aligned with the "Correction N" comments in the function body.
--
--   0. NOT A CHANGE - device_requests_notes.content STAYS AT 52 WEEKS. Recorded here because
--      it was very nearly changed, and the next person to read #98, #96, #126 or PR #130 will
--      find 26 weeks written in all four and reach for the same wrong conclusion.
--      The authoritative source is the team's retention spreadsheet, "GDPR data removal
--      review 26-08-11.xlsx", sheet "Requests", row 5:
--          Add a new note for this request | device_requests_notes | content | 12 months
--      That is the document PR #134 cited as "the 2026-08-11 spreadsheet review", and it
--      makes V26.08.11.1400's 52 weeks CORRECT. The 26-week figure in the issue threads
--      predates the spreadsheet and was never reconciled to it; #126's unticked "confirm the
--      blast radius is intended" is the trace of that.
--      Applying 26 weeks would have destroyed a further 1,033 note bodies (6,014 vs 4,981,
--      96.9% of the 6,208-row table) against written policy, irreversibly.
--      RULE FOR NEXT TIME: the spreadsheet outranks the issue comments. Check it first.
--
--   2. DROP BOTH DONOR BUSINESS EXCLUSIONS. #98: "This removes *two* predicates from
--      gdpr.donors_to_archive, not one. The lead-contact flag was never the commercial
--      exemption; donor_parents.type <> 'BUSINESS' was doing most of that work silently."
--      V26.08.11.1400 removed only the lead-contact flag and kept both business exclusions.
--      IMPACT: 20 -> 149 donors, i.e. 129 more. All 129 come from the donor_parents.type
--      predicate; the '%#(business|droppoint)%' name tag matches 0 rows in production today
--      and is dropped as dead weight rather than for effect.
--      WHY THIS IS RIGHT, not merely decided: a sample of the newly-caught donors shows the
--      overwhelming majority are individual people - named human contacts at businesses,
--      charities, schools and councils - holding a person's name, email, phone and postcode.
--      The parent is the organisation; the donor row is a natural person. The exclusion was
--      reasoning about the wrong record, so it was withholding erasure from exactly the
--      people GDPR covers.
--
--   3. KITS.COORDINATES, NEW FORWARD RULE. Approved on #126 ("Approved after the gap
--      review"): kits.coordinates is a geolocation derived from the collection address - a
--      home address for an individual donor. The retention routine NULLs donors.coordinates
--      and has never touched the copy on the kit, so the location outlives the erasure.
--      Until now this existed only in the unmerged one-off script (PR #130), meaning even a
--      backfill would start leaking again immediately. This makes it a standing rule.
--      IMPACT: 3,930 kits hold coordinates; all 3,930 are already past 12 months. 84 belong
--      to donors ALREADY showing 'Donor - Erased due to GDPR policy'.
--      The donor-erased branch currently matches no kit that the age branch does not, but it
--      is kept deliberately: it is the branch that makes erasure actually mean erasure, and
--      it must fire the moment a recent kit's donor is erased.
--      NOT IN SCOPE: kit_audit_trail has no coordinates column (verified - 35 columns, none
--      of them coordinates), so unlike #127 there is no audit-parity problem to solve here.
--
--   4. AUDIT-TRAIL GAP. V26.08.11.1400's three device_requests_audit_trail predicates are
--      gated only on the LIVE row's updated_at. When a scrubbed request is later edited for
--      any unrelated reason, updated_at moves back inside the retention window and the audit
--      rows behind it become permanently unreachable - they can never be scrubbed, because
--      the only thing that would scrub them is keyed to a timestamp that keeps moving away.
--      This was found by PR #130, whose predicate is "live row already wiped OR live row past
--      threshold". That encodes the right invariant: if the live row has been erased, its
--      history must be erased too, regardless of what its timestamp says now.
--      IMPACT: 1,069 details rows + 471 client_ref rows currently unreachable.
--      This branch only ever widens what is erased; it can never spare a row the old
--      predicate would have caught.
--      EXTENDED BEYOND PR #130: the same fix is applied to
--      referring_organisation_contacts_audit_trail, which has the identical defect for the
--      identical reason. PR #130 does not touch that table at all (#129 landed after it was
--      written), so this gap would otherwise have survived even a full historical scrub.
--
-- WHY THE GUARD
--   Unchanged from every prior gdpr-schema migration: in UAT and production the gdpr objects
--   are owned by techaid_admin and Flyway runs as the app role, which has no CREATE on that
--   schema. This migration is a deliberate no-op there; the admin-applied counterpart lives
--   under db/admin/ per db/admin/README.md.
--
--   CREATE OR REPLACE VIEW keeps gdpr.donors_to_archive's OID and column list unchanged, so
--   gdpr.archive_donor_info() (a BEFORE DELETE trigger selecting from this view) keeps
--   working across the change.

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

    -- Correction 2. The donor_parents join goes with the predicate that needed it; nothing
    -- else in the view referenced that table. Column list and order are preserved so the
    -- CREATE OR REPLACE succeeds and the trigger function is unaffected.
    EXECUTE $v$
        create or replace view gdpr.donors_to_archive as
          select donors.id, donors.name, donors.created_at,
                 coalesce(max(kits.created_at), donors.created_at) as kits_max_created_at
            from donors
            left join kits on donors.id = kits.donor_id
           where donors.name <> 'Donor - Erased due to GDPR policy'
           group by kits.donor_id, donors.id
          having coalesce(max(kits.created_at), donors.created_at)
                 <= (current_date - INTERVAL '12 months')
    $v$;

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

              -- Correction 3. Deliberately placed AFTER the donor erasure above so the
              -- donor-erased branch also catches donors erased by THIS run, not only by a
              -- previous one. kits.coordinates is jsonb, so NULL is the erasure - there is
              -- no sentinel to write and no meaningful "already scrubbed" marker.
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

              -- Correction 4 applies to the next three statements. The added OR branch keys
              -- off the LIVE row already carrying the sentinel, which no amount of later
              -- editing can undo, so these rows stop being able to hide behind a refreshed
              -- updated_at. Widening only.
              -- TEMP-REVERT-FOR-TEST-D: drop the OR branch to prove B3 discriminates.
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

              -- UNCHANGED at 52 weeks, deliberately. See note 0 in this file's header: the
              -- retention spreadsheet says 12 months for this field. Do not "fix" this to 26
              -- weeks on the strength of #98/#96/#126/PR #130, all of which predate it.
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

              -- TEMP-REVERT-FOR-TEST-D: drop the OR branch to prove B3 discriminates.
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
        $function$
    $fn$;
    RAISE NOTICE 'gdpr.performgdprcleanup() corrected: notes at 26 weeks, donor business exclusions dropped, kits.coordinates in scope, audit-trail gap closed';
END $mig$;
