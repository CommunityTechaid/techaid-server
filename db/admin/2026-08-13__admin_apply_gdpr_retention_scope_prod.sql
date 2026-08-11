-- Production counterpart of db/admin/2026-08-12__admin_apply_gdpr_retention_scope_uat.sql.
-- Rehearsed successfully in UAT on 2026-08-12 (see that file for the exact result) - this is
-- the same script, pointed at techaid_prod, with grants to api_prod instead of api_uat.
--
-- PREREQUISITES BEFORE RUNNING
--   1. This branch's GDPR work (V26.08.11.1400, 1450, 1600, 1650) must already be in
--      production - i.e. dev has been promoted to master and deployed. The plain migrations
--      (flag row, gdpr_cleanup_runs table) apply automatically on that deploy; the two gdpr-
--      schema migrations are self-gated no-ops there, which is exactly why this script exists.
--   2. Run as techaid_admin.
--   3. After this runs, also flip the gdpr-in-app-cleanup flag to true in production (Feature
--      Flags admin page, or `UPDATE feature_flags SET enabled = true WHERE flag_key =
--      'gdpr-in-app-cleanup';`) - this script does not do that, so the in-app job stays off
--      and pg_cron keeps running until someone does.
--   4. Once the in-app job has run successfully at least once in production, pg_cron
--      (gdpr-weekly-cleanup, Sat 04:04 UTC) can be unscheduled - a separate, later step, not
--      part of this script. Do not unschedule it before then (issue #62).
--
-- WHAT THIS DOES
--   Same as V26.08.11.1400 + V26.08.11.1650's function/view bodies (#95 lead-contact
--   exemption removed, #127 audit-trail parity, #128 collection_contact_name, #129
--   referring-org contacts, notes forward rule, #92/#98 stats recording), applied for real
--   since techaid_admin owns these objects and Flyway does not. Plus grants api_prod real
--   access, matching what was granted to api_uat on 2026-08-12.
--
-- BEFORE/AFTER queries are diagnostic only (no hard-coded expected counts, unlike
-- db/admin/2026-08-11__gdpr_historical_scrub.sql - production's current backlog has not been
-- freshly measured for this specific run). Read the printed counts rather than assuming them.
--
-- APPLIED TO
--   (none yet - run once prerequisite 1 above is satisfied.)

DO $$ BEGIN IF current_database() <> 'techaid_prod' THEN RAISE EXCEPTION 'ABORT: %', current_database(); END IF; END $$;

SELECT '=== BEFORE ===' AS section;
SELECT 'donors eligible (old view, lead-contact still exempt)' AS what, count(*) AS n FROM gdpr.donors_to_archive;
SELECT 'device_requests.details past 26wk unscrubbed' AS what, count(*) AS n FROM device_requests WHERE updated_at <= (current_date - interval '26 weeks') AND details <> 'RECORD DELETED BY SYSTEM - GDPR';
SELECT 'device_requests.client_ref past 52wk unscrubbed' AS what, count(*) AS n FROM device_requests WHERE updated_at <= (current_date - interval '52 weeks') AND client_ref <> 'WIPED - GDPR';
SELECT 'device_requests.collection_contact_name past 52wk unscrubbed' AS what, count(*) AS n FROM device_requests WHERE updated_at <= (current_date - interval '52 weeks') AND collection_contact_name IS NOT NULL AND collection_contact_name <> 'WIPED - GDPR';
SELECT 'device_requests_audit_trail.details behind an already-erased live row' AS what, count(*) AS n FROM device_requests_audit_trail dat JOIN device_requests d ON dat.id = d.id WHERE d.updated_at <= (current_date - interval '26 weeks') AND dat.details <> 'RECORD DELETED BY SYSTEM - GDPR';
SELECT 'device_requests_notes past 52wk unscrubbed' AS what, count(*) AS n FROM device_requests_notes WHERE updated_at <= (current_date - interval '52 weeks') AND content <> 'Note content deleted due to GDPR policy';
SELECT 'referring_organisation_contacts past 12mo unscrubbed' AS what, count(*) AS n FROM referring_organisation_contacts WHERE updated_at <= (current_date - interval '12 months') AND full_name <> 'Contact - Erased due to GDPR policy';
SELECT 'gdpr_cleanup_runs row count' AS what, count(*) AS n FROM gdpr_cleanup_runs;

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

GRANT USAGE ON SCHEMA gdpr TO api_prod;
GRANT EXECUTE ON FUNCTION gdpr.performgdprcleanup() TO api_prod;

SELECT '=== RUNNING gdpr.performgdprcleanup() now, as techaid_admin ===' AS section;
SELECT gdpr.performgdprcleanup() AS run_summary;

SELECT '=== AFTER ===' AS section;
SELECT 'donors eligible (should be 0 now)' AS what, count(*) AS n FROM gdpr.donors_to_archive;
SELECT 'device_requests.details past 26wk unscrubbed (should be 0)' AS what, count(*) AS n FROM device_requests WHERE updated_at <= (current_date - interval '26 weeks') AND details <> 'RECORD DELETED BY SYSTEM - GDPR';
SELECT 'device_requests.client_ref past 52wk unscrubbed (should be 0)' AS what, count(*) AS n FROM device_requests WHERE updated_at <= (current_date - interval '52 weeks') AND client_ref <> 'WIPED - GDPR';
SELECT 'device_requests.collection_contact_name past 52wk unscrubbed (should be 0)' AS what, count(*) AS n FROM device_requests WHERE updated_at <= (current_date - interval '52 weeks') AND collection_contact_name IS NOT NULL AND collection_contact_name <> 'WIPED - GDPR';
SELECT 'device_requests_audit_trail.details behind an already-erased live row (should be 0)' AS what, count(*) AS n FROM device_requests_audit_trail dat JOIN device_requests d ON dat.id = d.id WHERE d.updated_at <= (current_date - interval '26 weeks') AND dat.details <> 'RECORD DELETED BY SYSTEM - GDPR';
SELECT 'device_requests_notes past 52wk unscrubbed (should be 0)' AS what, count(*) AS n FROM device_requests_notes WHERE updated_at <= (current_date - interval '52 weeks') AND content <> 'Note content deleted due to GDPR policy';
SELECT 'referring_organisation_contacts past 12mo unscrubbed (should be 0)' AS what, count(*) AS n FROM referring_organisation_contacts WHERE updated_at <= (current_date - interval '12 months') AND full_name <> 'Contact - Erased due to GDPR policy';

SELECT '=== gdpr_cleanup_runs new row ===' AS section;
SELECT * FROM gdpr_cleanup_runs ORDER BY id DESC LIMIT 1;
