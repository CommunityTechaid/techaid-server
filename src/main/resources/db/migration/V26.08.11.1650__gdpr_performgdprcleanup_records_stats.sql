-- Extends gdpr.performgdprcleanup() (V26.08.11.1400) to record its own run in
-- public.gdpr_cleanup_runs (V26.08.11.1600), so every run leaves a structured, permanent
-- trace instead of only a log line. Behaviour is otherwise unchanged - same scope, same
-- thresholds, same placeholders.
--
-- WHY THE GUARD
--   Same as every prior gdpr-schema migration: in UAT and production the gdpr objects are
--   owned by techaid_admin and Flyway runs as the app role, which has no CREATE on that
--   schema. This migration is therefore a deliberate no-op there; the corresponding
--   admin-applied statement belongs under db/admin/, run as techaid_admin, alongside
--   V26.08.11.1400's admin-apply.

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

              UPDATE public.device_requests
              SET collection_contact_name = 'WIPED - GDPR'
              WHERE updated_at <= (CURRENT_DATE - '52 weeks'::interval)
                    AND collection_contact_name IS NOT NULL
                    AND collection_contact_name <> 'WIPED - GDPR';

              GET DIAGNOSTICS device_request_contact_name_archived_count = ROW_COUNT;

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

              INSERT INTO public.gdpr_cleanup_runs (
                donor_count, donor_audit_count, device_request_details_count, device_request_clientref_count,
                device_request_contact_name_count, device_request_details_audit_count,
                device_request_clientref_audit_count, device_request_contact_name_audit_count,
                device_request_notes_count, referring_contact_count, referring_contact_audit_count, summary
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
        $function$
    $fn$;
    RAISE NOTICE 'gdpr.performgdprcleanup() now records its own run in public.gdpr_cleanup_runs';
END $mig$;
