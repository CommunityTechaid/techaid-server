-- Converge the gdpr schema in FRESH databases with what techaid_uat and techaid_prod actually
-- have, so tests stop passing against a shape that exists nowhere real.
--
-- BACKGROUND
--   V22.20.13.1517__gdpr.sql creates gdpr.donors_to_delete. Both live databases instead have
--   gdpr.donors_to_archive: the view was renamed and extended by hand and committed nowhere
--   ("donors_to_archive" appears in no commit on any branch). gdpr.performgdprcleanup() was
--   updated to match; gdpr.archive_donor_info() was NOT, so it went on selecting from the
--   now-missing donors_to_delete. Being a BEFORE DELETE trigger on donors, that made every
--   donor delete fail for every role including techaid_admin, and deleteDonor was broken in
--   both environments until 2026-07-21 (see db/admin/2026-07-21__fix_archive_donor_info.sql).
--
--   Nothing could catch it: the zonky test database is built from these migrations, where
--   donors_to_delete DOES exist, so donor deletion passed in CI and failed everywhere real.
--   This migration closes that gap.
--
-- WHY THE GUARDS
--   In UAT and production every gdpr object is owned by techaid_admin and the app role has no
--   rights on the schema, so Flyway (running as api_uat / api_prod) cannot issue DDL there.
--   It does not need to: those databases are ALREADY in the target state. The
--   has_schema_privilege gate makes this migration a deliberate no-op there, and the real work
--   happens only on a fresh database, where the migrating role owns the schema it just created.
--
-- SCOPE NOTE — deliberately NOT fixing a suspected bug
--   The live view's `donor_parents.type <> 'BUSINESS'` predicate is reproduced verbatim. Under
--   three-valued logic that excludes every donor with no donor parent (NULL <> 'BUSINESS' is
--   NULL, which WHERE treats as false), which may mean retention selects far fewer donors than
--   intended. That is a real question, but this migration's job is to make fresh databases
--   MATCH the live ones. Changing behaviour here would defeat the purpose and hide the issue.
--   Fix it separately, once measured.

DO $mig$
BEGIN
    IF to_regnamespace('gdpr') IS NULL THEN
        RAISE NOTICE 'gdpr schema absent - skipping convergence';
        RETURN;
    END IF;

    IF NOT has_schema_privilege(current_user, 'gdpr', 'CREATE') THEN
        RAISE NOTICE 'role % has no CREATE on gdpr - skipping (expected in UAT/production)', current_user;
        RETURN;
    END IF;

    -- 1. The retention view under its live name.
    IF to_regclass('gdpr.donors_to_archive') IS NULL THEN
        EXECUTE $v$
            create view gdpr.donors_to_archive as
              select donors.id, donors.name, donors.created_at,
                     coalesce(max(kits.created_at), donors.created_at) as kits_max_created_at
                from donors
                left join kits on donors.id = kits.donor_id
                left join donor_parents on donors.donor_parent_id = donor_parents.id
               where donors.name not similar to '%#(business|droppoint)%'
                 and donors.name <> 'Donor - Erased due to GDPR policy'
                 and donor_parents.type <> 'BUSINESS'
                 and donors.is_lead_contact = false
               group by kits.donor_id, donors.id
              having coalesce(max(kits.created_at), donors.created_at)
                     <= (current_date - INTERVAL '12 months')
        $v$;
        RAISE NOTICE 'created gdpr.donors_to_archive';
    END IF;

    -- 2. Repoint the archive trigger, and harden it the same way the live databases were.
    --    SECURITY DEFINER means the application role needs no rights on gdpr at all; the
    --    search_path is pinned because an unpinned SECURITY DEFINER function is a
    --    privilege-escalation vector.
    IF EXISTS (
        SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
        WHERE n.nspname = 'gdpr' AND p.proname = 'archive_donor_info'
          AND pg_get_functiondef(p.oid) LIKE '%donors_to_delete%'
    ) THEN
        EXECUTE $f$
            CREATE OR REPLACE FUNCTION gdpr.archive_donor_info()
            RETURNS trigger
            LANGUAGE plpgsql
            SECURITY DEFINER
            SET search_path = pg_catalog, gdpr, public
            AS $body$
              BEGIN
                insert into gdpr.donors_archive(donor_id, created_at, updated_at, referral)
                values(OLD.id,
                       OLD.created_at,
                       (select kits_max_created_at from gdpr.donors_to_archive
                         where id = OLD.id),
                         OLD.referral);
                return OLD;
              END;
            $body$
        $f$;
        RAISE NOTICE 'repointed gdpr.archive_donor_info at donors_to_archive';
    END IF;

    -- 3. Retire the stale name so a fresh database cannot accidentally depend on it.
    IF to_regclass('gdpr.donors_to_delete') IS NOT NULL THEN
        EXECUTE 'DROP VIEW gdpr.donors_to_delete';
        RAISE NOTICE 'dropped gdpr.donors_to_delete';
    END IF;
END $mig$;
