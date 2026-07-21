-- Fix gdpr.archive_donor_info(): point it at the view that actually exists, and make it
-- SECURITY DEFINER so the application role never needs rights on the gdpr schema.
--
-- WHY THIS IS NOT A FLYWAY MIGRATION
--   gdpr.archive_donor_info() is owned by techaid_admin. Flyway runs as api_uat / api_prod,
--   which cannot CREATE OR REPLACE a function they do not own ("must be owner of function").
--   See db/admin/README.md.
--
-- THE BUG
--   The function's body selected from gdpr.donors_to_delete. That view does not exist in
--   techaid_uat or techaid_prod — both have gdpr.donors_to_archive. Because this is a
--   BEFORE DELETE trigger on public.donors, EVERY donor delete failed, for every role
--   including techaid_admin:
--       ERROR: relation "gdpr.donors_to_delete" does not exist
--   so the deleteDonor GraphQL mutation was broken in both environments.
--
--   Note the misleading first symptom: as api_uat the error is instead
--       ERROR: permission denied for schema gdpr
--   which looks like a grants problem. It is not. Granting api_uat USAGE on gdpr and INSERT
--   on donors_archive only changes the message to the missing-relation error above.
--
-- WHY SECURITY DEFINER
--   The function was SECURITY INVOKER, so the trigger body ran as whoever issued the DELETE.
--   That would require granting the application role standing write access to the GDPR
--   archive. As SECURITY DEFINER it runs as its owner (techaid_admin), so api_uat / api_prod
--   need NO grants on the gdpr schema at all — they can trigger the controlled archive path
--   and nothing else. search_path is pinned because an unpinned SECURITY DEFINER function is
--   a privilege-escalation vector.
--
-- BEHAVIOUR NOTE
--   updated_at is written from gdpr.donors_to_archive.kits_max_created_at, and that view only
--   contains retention-eligible donors (last kit, or record creation, over 12 months old). A
--   donor deleted outside the retention path therefore archives with updated_at NULL. That is
--   pre-existing behaviour, preserved here deliberately — this change is not the place to
--   alter what gets archived.
--
-- APPLIED TO
--   techaid_uat  — 2026-07-21. Verified: deleted 39 orphaned E2E donors (ids 1558-1596) as
--                  api_uat with no gdpr grants; gdpr.donors_archive went 1394 -> 1433, all 39
--                  traces present.
--   techaid_prod — 2026-07-21. Verified with a rolled-back smoke test: as api_prod (via
--                  SET ROLE), deleted kit-less donor 992; donors 425 -> 424 and
--                  gdpr.donors_archive 1486 -> 1487 with the trace for 992 present; after
--                  ROLLBACK both counts returned to 425 / 1486 and donor 992 was intact.
--                  The pre-change definition is captured in
--                  ROLLBACK_archive_donor_info_prod_20260721.sql (kept off-repo with the
--                  maintainer's backups) should it ever need reverting.
--
-- RUN AS: techaid_admin, connected to the target database.

DO $$
BEGIN
  IF current_database() NOT IN ('techaid_uat', 'techaid_prod') THEN
    RAISE EXCEPTION 'Refusing to run against database %', current_database();
  END IF;
  IF to_regclass('gdpr.donors_to_archive') IS NULL THEN
    RAISE EXCEPTION 'gdpr.donors_to_archive is missing in % - investigate before applying', current_database();
  END IF;
END $$;

CREATE OR REPLACE FUNCTION gdpr.archive_donor_info()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, gdpr, public
AS $function$
  BEGIN
    insert into gdpr.donors_archive(donor_id, created_at, updated_at, referral)
    values(OLD.id,
           OLD.created_at,
           (select kits_max_created_at from gdpr.donors_to_archive
             where id = OLD.id),
             OLD.referral);
    return OLD;
  END;
$function$;

-- Verification: expect security = DEFINER, owner = techaid_admin,
-- proconfig = {"search_path=pg_catalog, gdpr, public"}.
SELECT p.proname,
       pg_get_userbyid(p.proowner) AS owner,
       CASE WHEN p.prosecdef THEN 'DEFINER' ELSE 'INVOKER' END AS security,
       p.proconfig
FROM pg_proc p
JOIN pg_namespace n ON n.oid = p.pronamespace
WHERE n.nspname = 'gdpr' AND p.proname = 'archive_donor_info';
