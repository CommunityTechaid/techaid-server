-- Retire the vestigial KitStatus.ALLOCATION_ASSESSMENT value (#85) from the
-- kit_audit_trail.status check constraint, so the database stops accepting a status the
-- application enum no longer declares.
--
-- WHY THIS IS A NO-OP IN UAT AND PRODUCTION — this is not a bug
--   The constraint this migration tightens exists ONLY in fresh databases (local dev and the
--   zonky embedded-Postgres test databases). V26.07.03.0900__baseline_unmanaged_schema.sql
--   creates kit_audit_trail with `create table if not exists`, and both live databases already
--   had the table (the schema predates Flyway), so that statement was a no-op there and the
--   check constraint was never created. Confirmed 2026-07-21 by querying pg_constraint on
--   techaid_uat and techaid_prod: zero check constraints on kits or kit_audit_trail.
--
--   So this migration deliberately only ADDS the tightened constraint where one was already
--   present. It must NOT introduce a brand-new constraint into production: every Envers audit
--   insert would then start validating against the enum, creating a write-failure mode that
--   does not exist today and that nobody asked for. Where the constraint is absent, the table
--   is left untouched.
--
-- SAFE TO TIGHTEN
--   ALLOCATION_ASSESSMENT has never been written. Production holds 0 rows with it in both
--   `kits` and `kit_audit_trail`; the audit trail's 183,409 rows — the system's entire
--   transition history, including the legacy v1 status migration — use only 11 statuses. See
--   issue #85 for the full distribution.
--
-- The constraint is looked up in pg_constraint rather than named literally, because its name is
-- Hibernate-generated and only ever existed in generated schemas.

DO $mig$
DECLARE
    v_conname text;
BEGIN
    IF to_regclass('public.kit_audit_trail') IS NULL THEN
        RAISE NOTICE 'kit_audit_trail absent - nothing to do';
        RETURN;
    END IF;

    SELECT c.conname
      INTO v_conname
      FROM pg_constraint c
      JOIN pg_attribute a
        ON a.attrelid = c.conrelid
       AND a.attnum = ANY (c.conkey)
     WHERE c.conrelid = 'public.kit_audit_trail'::regclass
       AND c.contype = 'c'
       AND a.attname = 'status'
       AND array_length(c.conkey, 1) = 1;

    IF v_conname IS NULL THEN
        RAISE NOTICE 'no check constraint on kit_audit_trail.status - skipping (expected in UAT/production)';
        RETURN;
    END IF;

    EXECUTE format('ALTER TABLE public.kit_audit_trail DROP CONSTRAINT IF EXISTS %I', v_conname);

    EXECUTE format(
        'ALTER TABLE public.kit_audit_trail ADD CONSTRAINT %I CHECK (status IN (%s))',
        v_conname,
        $vals$'DONATION_NEW','PROCESSING_START','PROCESSING_WIPED','PROCESSING_OS_INSTALLED','PROCESSING_STORED','ALLOCATION_READY','ALLOCATION_QC_COMPLETED','ALLOCATION_DELIVERY_ARRANGED','DISTRIBUTION_DELIVERED','DISTRIBUTION_RECYCLED','DISTRIBUTION_REPAIR_RETURN'$vals$
    );
END
$mig$;
