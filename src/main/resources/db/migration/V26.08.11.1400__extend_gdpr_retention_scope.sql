-- Extend GDPR retention to the scope confirmed by the 2026-08-11 team review (issue #98):
--   * #95  — drop the `is_lead_contact = false` exemption from gdpr.donors_to_archive. 34
--     named individuals were permanently exempt with no recorded rationale; any individual
--     donor with no device for 12 months is now scrubbed regardless of lead-contact status.
--   * #127 — scrub device_requests_audit_trail.details/client_ref in step with the live row,
--     on the same clock (26 weeks / 52 weeks), keyed off the live row's updated_at so ordering
--     within this function does not matter (the live UPDATEs below never touch updated_at).
--   * #128 — scrub device_requests.collection_contact_name and its audit trail at 52 weeks.
--   * device_requests_notes.content — confirmed in scope at 12 months (52 weeks), keyed off
--     the note's own updated_at. Notes are written once and rarely updated, so this is
--     effectively "12 months since the note was left".
--   * #129 — referring_organisation_contacts (full_name/email/phone_number/address) and its
--     audit trail, at 12 months, keyed off the contact's own updated_at. The live scrub
--     deliberately does NOT bump updated_at (matching how device_requests scrubs already
--     behave), so the audit-trail parity UPDATE below can safely reuse the same predicate.
--
-- Explicitly OUT of this migration:
--   * donor_parents — organisational data, out of scope by decision.
--   * note / note_aud (kit notes) — #96 decided these are SIM card numbers, not people.
--   * device_requests_notes_aud — dead schema, already dropped from prod (issue #92, PR #125).
--
-- WHY THE GUARD
--   Same as every prior gdpr-schema migration (V26.07.21.2130, V26.07.21.2300,
--   V26.07.22.1200): in UAT and production the gdpr objects are owned by techaid_admin and
--   Flyway runs as the app role, which has no CREATE on that schema. This migration is
--   therefore a deliberate no-op there; the corresponding admin-applied statement belongs
--   under db/admin/ per db/admin/README.md, run as techaid_admin.
--
--   CREATE OR REPLACE VIEW keeps gdpr.donors_to_archive's OID, so
--   gdpr.archive_donor_info() (a BEFORE DELETE trigger that selects from this view) keeps
--   working through the change.

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

    EXECUTE $v$
        create or replace view gdpr.donors_to_archive as
          select donors.id, donors.name, donors.created_at,
                 coalesce(max(kits.created_at), donors.created_at) as kits_max_created_at
            from donors
            left join kits on donors.id = kits.donor_id
            left join donor_parents on donors.donor_parent_id = donor_parents.id
           where donors.name not similar to '%#(business|droppoint)%'
             and donors.name <> 'Donor - Erased due to GDPR policy'
             and (donor_parents.type is null or donor_parents.type <> 'BUSINESS')
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

              -- #128: collection_contact_name, same 52-week clock as client_ref.
              UPDATE public.device_requests
              SET collection_contact_name = 'WIPED - GDPR'
              WHERE updated_at <= (CURRENT_DATE - '52 weeks'::interval)
                    AND collection_contact_name IS NOT NULL
                    AND collection_contact_name <> 'WIPED - GDPR';

              GET DIAGNOSTICS device_request_contact_name_archived_count = ROW_COUNT;

              -- #127: audit-trail parity for details/client_ref, keyed off the LIVE row's
              -- updated_at (never mutated by the two UPDATEs above), so this is independent of
              -- statement order and also catches any audit row still holding original content
              -- from before this migration existed.
              UPDATE public.device_requests_audit_trail dat
              SET details = 'RECORD DELETED BY SYSTEM - GDPR'
              FROM public.device_requests d
              WHERE dat.id = d.id
                    AND d.updated_at <= (CURRENT_DATE - '26 weeks'::interval)
                    AND dat.details <> 'RECORD DELETED BY SYSTEM - GDPR';

              GET DIAGNOSTICS device_request_details_audit_archived_count = ROW_COUNT;

              UPDATE public.device_requests_audit_trail dat
              SET client_ref = 'WIPED - GDPR'
              FROM public.device_requests d
              WHERE dat.id = d.id
                    AND d.updated_at <= (CURRENT_DATE - '52 weeks'::interval)
                    AND dat.client_ref <> 'WIPED - GDPR';

              GET DIAGNOSTICS device_request_clientref_audit_archived_count = ROW_COUNT;

              UPDATE public.device_requests_audit_trail dat
              SET collection_contact_name = 'WIPED - GDPR'
              FROM public.device_requests d
              WHERE dat.id = d.id
                    AND d.updated_at <= (CURRENT_DATE - '52 weeks'::interval)
                    AND dat.collection_contact_name IS NOT NULL
                    AND dat.collection_contact_name <> 'WIPED - GDPR';

              GET DIAGNOSTICS device_request_contact_name_audit_archived_count = ROW_COUNT;

              -- Notes confirmed in scope at 12 months (52 weeks), keyed off the note's own
              -- updated_at. device_requests_notes_aud is deliberately NOT touched here: it is
              -- dead schema, already dropped from prod (issue #92).
              UPDATE public.device_requests_notes n
              SET content = 'Note content deleted due to GDPR policy'
              WHERE n.updated_at <= (CURRENT_DATE - '52 weeks'::interval)
                    AND n.content <> 'Note content deleted due to GDPR policy';

              GET DIAGNOSTICS device_request_notes_archived_count = ROW_COUNT;

              -- #129: referring_organisation_contacts, 12 months off the contact's own
              -- updated_at. Deliberately does NOT bump updated_at on scrub (matching how the
              -- device_requests scrubs above behave), so the audit-trail parity UPDATE below
              -- can safely reuse the same predicate regardless of statement order.
              UPDATE public.referring_organisation_contacts c
              SET full_name = 'Contact - Erased due to GDPR policy',
                email = '',
                phone_number = '',
                address = ''
              WHERE c.updated_at <= (CURRENT_DATE - '12 months'::interval)
                    AND c.full_name <> 'Contact - Erased due to GDPR policy';

              GET DIAGNOSTICS referring_contact_archived_count = ROW_COUNT;

              UPDATE public.referring_organisation_contacts_audit_trail cat
              SET full_name = 'Contact - Erased due to GDPR policy',
                email = '',
                phone_number = '',
                address = ''
              FROM public.referring_organisation_contacts c
              WHERE cat.id = c.id
                    AND c.updated_at <= (CURRENT_DATE - '12 months'::interval)
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

              RETURN result;
            END;
        $function$
    $fn$;
    RAISE NOTICE 'gdpr.donors_to_archive and gdpr.performgdprcleanup() extended per issue #98 2026-08-11 scope';
END $mig$;
