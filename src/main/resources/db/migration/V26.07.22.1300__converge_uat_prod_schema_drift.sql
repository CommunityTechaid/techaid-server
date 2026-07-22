-- Converge five schema divergences between techaid_uat and techaid_prod (issue #91,
-- findings 1, 4, 5, 6 and 7), measured by a full schema comparison on 2026-07-21.
--
-- BACKGROUND
--   UAT is not a faithful rehearsal environment for production. Years of Hibernate
--   ddl-auto=update plus hand-applied DDL left the two databases differing in column
--   defaults, one column width, two database objects and four stray columns, so a change
--   verified on UAT can behave differently in production.
--
--   Finding 1 - `archived` defaults. The column is `character(1)` holding 'Y'/'N'
--     everywhere (the entities map it as a non-nullable Kotlin Boolean through
--     org.hibernate.type.YesNoConverter, so the Boolean is the Java-side view of a
--     char(1) column, not a boolean column). UAT carries `default 'N'` on
--     donor_parents, referring_organisation_contacts and referring_organisations;
--     production carries none on any of the three, and is additionally NOT NULL on the
--     first two. An INSERT omitting `archived` therefore succeeds on UAT and raises a
--     not-null violation in production; on referring_organisations it silently writes
--     NULL, which every `archived = 'N'` predicate then drops. Production was measured
--     on 2026-07-21 and has zero NULLs in all three columns, so this is latent, not
--     live - which is exactly why it is cheap to close now.
--
--   Finding 4 - device_requests_audit_trail.details is varchar(255) on UAT and
--     varchar(4096) on production. Here UAT is the MORE restrictive side, so it
--     manufactures false failures for audit strings production accepts. Both databases
--     already have varchar(4096) on the base column device_requests.details, so 4096 is
--     the established width; the audit twin on UAT simply never got widened.
--
--   Finding 5 - admin_config.can_public_request_desktop is NOT NULL default false on
--     UAT and NOT NULL with no default in production. Same insert-time asymmetry as
--     finding 1.
--
--   Finding 6 - the fuzzystrmatch extension and a hand-written public.iif() exist only
--     in production, created by no migration. Any SQL using soundex/levenshtein/iif - a
--     Superset dashboard, an ad-hoc report, a future migration - works in production and
--     fails on UAT with "function does not exist". Converging means ADDING them to UAT
--     and to fresh databases, never removing them from production.
--
--   Finding 7 - four columns exist only on UAT, declared by no entity and no migration:
--     referring_organisations.address, referring_organisations.domain,
--     referring_organisation_contacts.first_name and kit_audit_trail.organisation_id.
--     They are ddl-auto residue. Production has never had them and runs fine, so nothing
--     in the application can depend on them. UAT runs SPRING_PROFILES_ACTIVE=testing with
--     no DDL_AUTO override, which falls through to ddl-auto: validate, so Hibernate will
--     not recreate them - the drop is permanent. None of the four carries an index, a
--     constraint or a view dependency in the 2026-07-21 UAT snapshot.
--
-- DIRECTION OF CONVERGENCE - always toward the more permissive side
--   Add the missing defaults so an insert cannot fail; widen the narrow column; add the
--   missing objects. Nothing here is made more restrictive than it already is in either
--   environment, and nothing is removed from production. In particular this does NOT add
--   NOT NULL to referring_organisations.archived, and does NOT relax the existing NOT NULL
--   on the other two - changing nullability is a separate decision with a data backfill
--   attached, not a convergence.
--
-- WHY THE GUARDS
--   One migration, three different starting states - fresh/test databases (built from
--   V26.07.03.0900, which creates all three archived columns NOT NULL with NO default,
--   can_public_request_desktop NOT NULL with no default, details as TEXT, and none of the
--   four orphan columns), UAT, and production. A bare ALTER would be wrong somewhere. Each
--   statement inspects the catalog first and fires only where the drift actually exists, so
--   the migration is a no-op on re-run and on any database already in the target state.
--
--   Unlike the gdpr migrations, no ownership gate is needed for the public tables:
--   api_prod owns all 41 public tables and sequences (confirmed by the 2026-07-22
--   production-replica rehearsal), so Flyway can issue these ALTERs directly. public.iif
--   is the one exception - in production it is owned by techaid_admin and api_prod holds
--   only EXECUTE, so CREATE OR REPLACE would fail with "must be owner of function". It is
--   therefore guarded on absence and created, never replaced.
--
-- ACCEPTED NEW DIVERGENCE
--   The UAT and fresh-database copies of fuzzystrmatch and public.iif will be owned by the
--   migrating app role, not by techaid_admin as in production. Ownership differs; the
--   callable surface does not. That is deliberate - matching the owner would need admin
--   credentials this migration does not and should not have.
--
-- SCOPE NOTE - what this deliberately leaves alone
--   Finding 8 (the baseline's CHECK constraints, absent from BOTH live databases) is out of
--   scope. That was already decided in #89: adding them piecemeal is worse than leaving
--   them consistently absent. Do not add CHECK constraints here.
--   Also untouched, though visible in the same snapshot: kits.archived carries
--   `default false` on a character(1) column in BOTH databases, and donors.archived
--   carries `default 'N'` in both. Those are symmetric, so they are not drift and are
--   not this migration's business.

DO $mig$
DECLARE
    tbl text;
    col record;
    populated bigint;
BEGIN
    -- Finding 1. Give `archived` the 'N' default wherever it is missing. Guarded on the
    -- character type as well as the absent default: 'N' is only a valid default for the
    -- char(1) representation, and failing loudly here would be worse than skipping.
    FOREACH tbl IN ARRAY ARRAY['donor_parents', 'referring_organisation_contacts', 'referring_organisations']
    LOOP
        IF EXISTS (
            SELECT 1 FROM information_schema.columns c
            WHERE c.table_schema = current_schema()
              AND c.table_name = tbl
              AND c.column_name = 'archived'
              AND c.data_type IN ('character', 'character varying')
              AND c.column_default IS NULL
        ) THEN
            EXECUTE format('ALTER TABLE %I ALTER COLUMN archived SET DEFAULT %L', tbl, 'N');
            RAISE NOTICE 'set %.archived default to ''N''', tbl;
        END IF;
    END LOOP;

    -- Finding 5. Same treatment for the one admin_config flag that drifted. The other
    -- five can_public_request_* columns have no default in EITHER database, so they are
    -- symmetric and are left as they are.
    IF EXISTS (
        SELECT 1 FROM information_schema.columns c
        WHERE c.table_schema = current_schema()
          AND c.table_name = 'admin_config'
          AND c.column_name = 'can_public_request_desktop'
          AND c.data_type = 'boolean'
          AND c.column_default IS NULL
    ) THEN
        ALTER TABLE admin_config ALTER COLUMN can_public_request_desktop SET DEFAULT false;
        RAISE NOTICE 'set admin_config.can_public_request_desktop default to false';
    END IF;

    -- Finding 4. Widen the audit column to the width production and both base columns
    -- already use. The character_maximum_length test makes this strictly a widening:
    -- production (4096) is left alone, and a fresh database - where the column is TEXT,
    -- which reports a NULL maximum length and is wider than any varchar - is skipped
    -- rather than narrowed.
    IF EXISTS (
        SELECT 1 FROM information_schema.columns c
        WHERE c.table_schema = current_schema()
          AND c.table_name = 'device_requests_audit_trail'
          AND c.column_name = 'details'
          AND c.data_type = 'character varying'
          AND c.character_maximum_length < 4096
    ) THEN
        ALTER TABLE device_requests_audit_trail ALTER COLUMN details TYPE varchar(4096);
        RAISE NOTICE 'widened device_requests_audit_trail.details to varchar(4096)';
    END IF;

    -- Finding 6a. fuzzystrmatch is a TRUSTED extension in PG13+, so the app role can create
    -- it without admin rights, and techaid-pg-svr already lists it in azure.extensions
    -- (a server-level parameter, so it covers both databases). IF NOT EXISTS makes this a
    -- no-op in production; the availability check keeps a Postgres build without contrib
    -- installed - a developer machine, a stripped container - from failing the whole
    -- migration, and says so in the log rather than skipping silently.
    IF EXISTS (SELECT 1 FROM pg_available_extensions WHERE name = 'fuzzystrmatch') THEN
        EXECUTE 'CREATE EXTENSION IF NOT EXISTS fuzzystrmatch WITH SCHEMA public';
    ELSE
        RAISE NOTICE 'fuzzystrmatch is not available on this server - skipping (issue #91 finding 6)';
    END IF;

    -- Finding 6b. public.iif, reproduced verbatim from prod_schema.sql. Guarded on absence
    -- and deliberately NOT written as CREATE OR REPLACE: production's copy is owned by
    -- techaid_admin while Flyway runs as api_prod, so a replace would fail there with
    -- "must be owner of function". This must be a clean no-op in production.
    IF to_regprocedure('public.iif(boolean,anyelement,anyelement)') IS NULL THEN
        EXECUTE $iif$
            CREATE FUNCTION public.iif(condition boolean, true_result anyelement, false_result anyelement)
            RETURNS anyelement
            LANGUAGE sql IMMUTABLE
            AS $body$
              SELECT CASE WHEN condition THEN true_result ELSE false_result END
            $body$
        $iif$;
        RAISE NOTICE 'created public.iif (issue #91 finding 6)';
    END IF;

    -- Finding 7. Drop the four UAT-only orphan columns. DROP COLUMN IF EXISTS makes this a
    -- no-op in production and in fresh databases, which never had them.
    --
    -- The non-NULL count is logged BEFORE each drop. The decision to drop was made without
    -- inspecting the data, so the deploy log is the only record of what was discarded. It is
    -- an audit trail, not a gate: a non-zero count does not abort, because these columns are
    -- unreachable from the application either way.
    FOR col IN
        SELECT * FROM (VALUES
            ('referring_organisations', 'address'),
            ('referring_organisations', 'domain'),
            ('referring_organisation_contacts', 'first_name'),
            ('kit_audit_trail', 'organisation_id')
        ) AS t(table_name, column_name)
    LOOP
        IF EXISTS (
            SELECT 1 FROM information_schema.columns c
            WHERE c.table_schema = current_schema()
              AND c.table_name = col.table_name
              AND c.column_name = col.column_name
        ) THEN
            EXECUTE format('SELECT count(%I) FROM %I', col.column_name, col.table_name) INTO populated;
            RAISE NOTICE 'dropping orphan column %.% - % non-null values discarded',
                col.table_name, col.column_name, populated;
            EXECUTE format('ALTER TABLE %I DROP COLUMN IF EXISTS %I', col.table_name, col.column_name);
        END IF;
    END LOOP;
END $mig$;
