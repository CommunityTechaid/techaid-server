-- Issue #93: make gdpr.donors_to_archive stop silently excluding donors that have no donor parent.
--
-- WHY THIS IS NOT A FLYWAY MIGRATION
--   gdpr.donors_to_archive is owned by techaid_admin. Flyway runs as api_uat / api_prod, which
--   cannot CREATE OR REPLACE a view they do not own ("must be owner of view"). See
--   db/admin/README.md. The matching Flyway migration,
--   V26.07.22.1200__fix_parentless_donor_retention.sql, gates itself off with has_schema_privilege
--   and is therefore a deliberate no-op in both live databases; it fixes fresh/test databases only.
--   This file is how the fix reaches UAT and production.
--
-- THE BUG
--   The view LEFT JOINs donor_parents and then filters `donor_parents.type <> 'BUSINESS'`. For a
--   donor with no donor parent that expression is NULL, and WHERE treats NULL as false, so the
--   donor never enters the view. gdpr.performgdprcleanup() — the weekly pg_cron retention job —
--   selects from this view, so parentless donors were never anonymised and kept their PII
--   indefinitely, with no error anywhere.
--
-- THE CHANGE — one predicate
--       and donor_parents.type <> 'BUSINESS'
--   becomes
--       and (donor_parents.type is null or donor_parents.type <> 'BUSINESS')
--   Everything else is reproduced verbatim from the live definition (captured with pg_get_viewdef
--   from techaid_prod on 2026-07-22 and byte-equivalent to V26.07.21.2130). Do not tidy it.
--
-- BLAST RADIUS — measured 2026-07-22, re-check before applying
--   At measurement time techaid_prod had 425 donors and ZERO with a null donor_parent_id, so this
--   fix selected zero additional rows. The pre-flight block below re-measures that and RAISEs a
--   NOTICE with the count, because every row this view yields is irreversibly anonymised by the
--   next Saturday run. If the count is not what you expect, ROLL BACK and re-decide rather than
--   pressing on.
--
-- APPLIED TO
--   techaid_uat  — 2026-07-22, as techaid_admin, in a single transaction (psql -1).
--                  Pre-flight: 154 donors, 14 with no donor parent, NOTICE reported 3 newly
--                  eligible. Verified after commit: gdpr.donors_to_archive went 3 -> 6 rows,
--                  exactly the 3 predicted. View still owned by techaid_admin; definition
--                  confirmed to carry (donor_parents.type IS NULL OR ... <> 'BUSINESS') and to
--                  be otherwise unchanged. gdpr_donors_trigger on donors still resolves to
--                  archive_donor_info (still SECURITY DEFINER), so CREATE OR REPLACE preserved
--                  the view OID as intended.
--                  NOTE: UAT has no pg_cron entry (see issue #92), so nothing anonymises those
--                  3 donors automatically. This applied the DDL; it did not rehearse the data
--                  change, and no such rehearsal is available anywhere.
--   techaid_prod — NOT YET APPLIED. Measured 2026-07-22 at 0 newly eligible of 425 donors, but
--                  re-read the pre-flight NOTICE at apply time rather than trusting that figure:
--                  in prod the next Saturday 04:04 run anonymises whatever the view yields,
--                  irreversibly. UAT returning 3 where prod returned 0 is exactly why.
--
-- RUN AS: techaid_admin, connected to the target database. Run inside a transaction so the
--         pre-flight count can be read before committing.

DO $$
DECLARE
  newly_eligible bigint;
BEGIN
  IF current_database() NOT IN ('techaid_uat', 'techaid_prod') THEN
    RAISE EXCEPTION 'Refusing to run against database %', current_database();
  END IF;
  IF to_regclass('gdpr.donors_to_archive') IS NULL THEN
    RAISE EXCEPTION 'gdpr.donors_to_archive is missing in % - investigate before applying', current_database();
  END IF;

  -- How many donors this change will newly expose to anonymisation: parentless, not already
  -- erased, not a lead contact, not a #business/#droppoint record, and past the 12-month
  -- threshold. Measured as zero on techaid_prod on 2026-07-22.
  SELECT count(*) INTO newly_eligible
    FROM (
      SELECT donors.id
        FROM donors
        LEFT JOIN kits ON donors.id = kits.donor_id
       WHERE donors.donor_parent_id IS NULL
         AND donors.name NOT SIMILAR TO '%#(business|droppoint)%'
         AND donors.name <> 'Donor - Erased due to GDPR policy'
         AND donors.is_lead_contact = false
       GROUP BY kits.donor_id, donors.id
      HAVING coalesce(max(kits.created_at), donors.created_at) <= (current_date - INTERVAL '12 months')
    ) AS newly;

  RAISE NOTICE 'Issue #93: % donor(s) will be newly eligible for anonymisation in %',
               newly_eligible, current_database();
END $$;

CREATE OR REPLACE VIEW gdpr.donors_to_archive AS
  select donors.id, donors.name, donors.created_at,
         coalesce(max(kits.created_at), donors.created_at) as kits_max_created_at
    from donors
    left join kits on donors.id = kits.donor_id
    left join donor_parents on donors.donor_parent_id = donor_parents.id
   where donors.name not similar to '%#(business|droppoint)%'
     and donors.name <> 'Donor - Erased due to GDPR policy'
     and (donor_parents.type is null or donor_parents.type <> 'BUSINESS')
     and donors.is_lead_contact = false
   group by kits.donor_id, donors.id
  having coalesce(max(kits.created_at), donors.created_at)
         <= (current_date - INTERVAL '12 months');

-- Verification: expect owner = techaid_admin and the definition to contain
-- "(donor_parents.type IS NULL OR donor_parents.type <> 'BUSINESS'::text)".
SELECT c.relname,
       pg_get_userbyid(c.relowner) AS owner,
       pg_get_viewdef(c.oid, true) AS definition
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'gdpr' AND c.relname = 'donors_to_archive';
