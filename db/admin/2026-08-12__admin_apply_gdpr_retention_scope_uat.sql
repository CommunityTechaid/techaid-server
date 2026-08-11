-- Records the admin-applied statement that was run live against techaid_uat on 2026-08-12,
-- as techaid_admin, ahead of committing it here. Written retroactively to close that gap -
-- see db/admin/README.md rule 1 ("record every admin-applied statement here before running
-- it"). The content below is exactly what ran, reconstructed from the session's script.
--
-- WHAT THIS DOES
--   Admin-applies the view/function bodies from V26.08.11.1400 and V26.08.11.1650 (both
--   self-gated no-ops in UAT/production, since gdpr objects are techaid_admin-owned) and
--   grants api_uat real access, so the in-app job (issue #62, flag gdpr-in-app-cleanup,
--   already flipped on in UAT this session) can run on its own from here.
--
-- RESULT WHEN RUN 2026-08-12 (techaid_uat)
--   gdpr.donors_to_archive and gdpr.performgdprcleanup() replaced successfully. A single
--   SELECT gdpr.performgdprcleanup() run immediately after cleared UAT's entire existing
--   backlog in one pass: 3 device_requests.details, 2 device_requests.client_ref, 3040
--   device_requests_notes.content, 1359 referring_organisation_contacts (+1 audit trail row).
--   0 donors (UAT was already scrubbed clean by the 2026-08-11 historical scrub, #126).
--   0 device_requests_audit_trail backlog (none existed in UAT). A verification pass after
--   confirmed every category at 0 remaining. gdpr_cleanup_runs recorded this as its first
--   row (id 1).
--
-- APPLIED TO
--   techaid_uat  2026-08-12  (this file documents that run)
--   techaid_prod - see db/admin/2026-08-13__admin_apply_gdpr_retention_scope_prod.sql,
--     prepared alongside this file, not yet applied.

DO $$ BEGIN IF current_database() <> 'techaid_uat' THEN RAISE EXCEPTION 'ABORT: %', current_database(); END IF; END $$;

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
$function$;

GRANT USAGE ON SCHEMA gdpr TO api_uat;
GRANT EXECUTE ON FUNCTION gdpr.performgdprcleanup() TO api_uat;
