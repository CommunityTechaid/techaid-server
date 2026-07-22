-- Issue #93: gdpr.donors_to_archive silently excludes every donor with no donor parent from
-- GDPR retention, so their PII is kept indefinitely.
--
-- THE DEFECT
--   The view joins donor_parents with a LEFT JOIN and then filters `donor_parents.type <> 'BUSINESS'`.
--   For a donor with no donor parent that expression evaluates to NULL, not true, and WHERE treats
--   NULL as false — so the donor never appears in the view, is never picked up by
--   gdpr.performgdprcleanup(), and is never anonymised. Silently, with no error anywhere.
--
-- THE FIX — one predicate, nothing else
--       and donor_parents.type <> 'BUSINESS'
--   becomes
--       and (donor_parents.type is null or donor_parents.type <> 'BUSINESS')
--   The other four conditions (the '#business'/'#droppoint' name exclusion, the
--   'Donor - Erased due to GDPR policy' exclusion, is_lead_contact = false, and the 12-month
--   HAVING threshold) are reproduced verbatim from V26.07.21.2130, which was verified
--   byte-equivalent to the live view on 2026-07-22. Every row this view yields is irreversibly
--   anonymised by the Saturday pg_cron job, so widening it further would be destructive.
--
-- BLAST RADIUS: MEASURED, NOT ASSUMED
--   Read-only measurement against techaid_prod on 2026-07-22 (recorded on issue #93): of 425
--   donors, ZERO have a null donor_parent_id, so this fix selects zero additional rows today.
--   donor_parents.type only ever holds 'BUSINESS' (153) or 'DROPPOINT' (39) and is never NULL, so
--   the LEFT-JOIN NULL this fixes can arise only from parentless donors. It ships as a
--   correctness fix that closes the hole before it has an occupant: DonorMutations accepts a null
--   donorParentId on create, and a null id on update actively detaches an existing parent, so the
--   API can mint a parentless donor at any time.
--
-- WHY THE GUARDS
--   Same as V26.07.21.2130 and V26.07.21.2300. In UAT and production every gdpr object is owned by
--   techaid_admin and Flyway runs as the app role (api_uat / api_prod), which has no rights on the
--   schema — a CREATE OR REPLACE VIEW there would fail with "must be owner of view". The
--   has_schema_privilege gate makes this migration a deliberate no-op in those databases; the real
--   work happens on a fresh database, where the migrating role owns the schema it created.
--
--   CONSEQUENCE, stated plainly: this migration does NOT fix UAT or production. Applying it there
--   is an admin-applied statement, run as techaid_admin, recorded under db/admin/ per the rules in
--   db/admin/README.md. See db/admin/2026-07-22__fix_parentless_donor_retention.sql.
--
--   CREATE OR REPLACE VIEW (rather than DROP + CREATE) keeps the view's OID, so
--   gdpr.archive_donor_info() — a BEFORE DELETE trigger on donors that selects from this view —
--   keeps working through the change. Dropping it would cascade or fail.

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
             and donors.is_lead_contact = false
           group by kits.donor_id, donors.id
          having coalesce(max(kits.created_at), donors.created_at)
                 <= (current_date - INTERVAL '12 months')
    $v$;
    RAISE NOTICE 'gdpr.donors_to_archive now includes parentless donors (issue #93)';
END $mig$;
