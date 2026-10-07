-- Sequence vs max(id) audit - techaid_prod (PRODUCTION; read-only).                                   2026-10-07
--
-- WHY: on 2026-10-07T14:31:48Z createDonor on UAT failed with
--   duplicate key value violates unique constraint "donors_pkey"  Key (id)=(1663)
-- after ids 1655-1662 had been handed out the same day. 1663 is a 2022 GDPR-erased donor
-- and max(id). Question: was donor_sequence already sitting below max(id) BEFORE the
-- Boot 4.1 / Hibernate 7 server reached UAT? ON PROD this answers: will prod collide after the
-- promote? Prod has only ever run 3.x, so any will_collide = true here is pre-existing prod state.
--
-- READ-ONLY. Wrapped in BEGIN READ ONLY ... ROLLBACK. No DDL, no DML, no nextval()/setval()
-- (reading a sequence relation with SELECT does not advance it). max(id) is computed with
-- query_to_xml() so no helper function has to be created. Run as api_prod or techaid_admin with PGOPTIONS="-c default_transaction_read_only=on":
--   psql "host=techaid-pg-svr.postgres.database.azure.com dbname=techaid_prod user=... sslmode=require" \
--        -f db/admin/2026-10-07__readonly_sequence_vs_maxid_prod.sql
--
-- HOW TO READ RESULT 1: next_value is what the NEXT insert will get. will_collide = true means
-- existing rows sit at or above next_value, so some future inserts will fail. It does NOT mean
-- the very next insert fails. occupied_ahead is how many of those rows there are: each one
-- fails exactly one create attempt as the sequence climbs past it. A failed insert still
-- consumes its nextval, which is why the 2026-10-07 retry passed.
-- custom_rev_info_seq legitimately increments by 50 (Hibernate pooled optimizer): there the
-- check is next_value vs max(id), and the pooled optimizer hands out ids BELOW last_value, so a
-- max(id) up to last_value + 49 is normal - see the headroom column.

BEGIN READ ONLY;

-- 1. Every public sequence: state, owning table (explicit entity map, else pg_depend), max(id)
WITH map(seq, tbl, col) AS (
    VALUES
        ('donor_sequence',                                'donors',                                'id'),
        ('donor_parent_sequence',                         'donor_parents',                         'id'),
        ('kit_sequence',                                  'kits',                                  'id'),
        ('note_sequence',                                 'note',                                  'id'),
        ('device_requests_sequence',                      'device_requests',                       'id'),
        ('device_requests_note_sequence',                 'device_requests_notes',                 'id'),
        ('referring_organisation_sequence',               'referring_organisations',               'id'),
        ('referring_organisation_contacts_sequence',      'referring_organisation_contacts',       'id'),
        ('referring_organisations_note_sequence',         'referring_organisations_notes',         'id'),
        ('referring_organisation_contacts_note_sequence', 'referring_organisation_contacts_notes', 'id'),
        ('post_sequence',                                 'posts',                                 'id'),
        ('borough_groups_sequence',                       'borough_groups',                        'id'),
        ('borough_availability_sequence',                 'borough_availability',                  'id'),
        ('referrer_limit_exceptions_sequence',            'referrer_limit_exceptions',             'id'),
        ('delivery_windows_sequence',                     'delivery_windows',                      'id'),
        ('delivery_blocked_dates_sequence',               'delivery_blocked_dates',                'id'),
        ('delivery_bookings_sequence',                    'delivery_bookings',                     'id'),
        ('delivery_booking_overrides_sequence',           'delivery_booking_overrides',            'id'),
        ('delivery_day_boroughs_sequence',                'delivery_day_boroughs',                 'id'),
        ('custom_rev_info_seq',                           'custom_rev_info',                       'id')
),
owned AS (   -- serial/identity sequences (e.g. gdpr_cleanup_runs_id_seq) carry a pg_depend link
    SELECT s.relname AS seq, t.relname AS tbl, a.attname AS col, tn.nspname AS tbl_schema
    FROM pg_class s
    JOIN pg_depend d    ON d.objid = s.oid AND d.classid = 'pg_class'::regclass AND d.deptype IN ('a', 'i')
    JOIN pg_class t     ON t.oid = d.refobjid
    JOIN pg_namespace tn ON tn.oid = t.relnamespace
    JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = d.refobjsubid
    WHERE s.relkind = 'S'
),
seqs AS (
    SELECT ps.schemaname, ps.sequencename, ps.increment_by,
           (xpath('/row/v/text()', query_to_xml(format('select last_value as v from %I.%I', ps.schemaname, ps.sequencename), false, true, '')))[1]::text::bigint AS last_value,
           (xpath('/row/c/text()', query_to_xml(format('select is_called as c from %I.%I', ps.schemaname, ps.sequencename), false, true, '')))[1]::text::boolean AS is_called,
           COALESCE(o.tbl_schema, CASE WHEN m.tbl IS NOT NULL THEN 'public' END) AS tbl_schema,
           COALESCE(o.tbl, m.tbl) AS tbl,
           COALESCE(o.col, m.col) AS col,
           CASE WHEN o.tbl IS NOT NULL THEN 'pg_depend' WHEN m.tbl IS NOT NULL THEN 'entity map' ELSE 'UNMAPPED' END AS mapped_by
    FROM pg_sequences ps
    LEFT JOIN map m   ON m.seq = ps.sequencename AND ps.schemaname = 'public'
    LEFT JOIN owned o ON o.seq = ps.sequencename
    -- Skip schemas/sequences this role cannot read (e.g. `import`): reading them aborts the run.
    -- OID-based privilege checks never raise, unlike the name-based forms.
    JOIN pg_namespace sn ON sn.nspname = ps.schemaname
    JOIN pg_class sc     ON sc.relnamespace = sn.oid AND sc.relname = ps.sequencename
    WHERE has_schema_privilege(sn.oid, 'USAGE') AND has_sequence_privilege(sc.oid, 'SELECT')
),
measured AS (
    SELECT s.*,
           CASE WHEN s.is_called THEN s.last_value + s.increment_by ELSE s.last_value END AS next_value,
           CASE WHEN s.tbl IS NOT NULL AND EXISTS (
                    SELECT 1 FROM pg_class tc JOIN pg_namespace tn ON tn.oid = tc.relnamespace
                    WHERE tn.nspname = s.tbl_schema AND tc.relname = s.tbl
                      AND has_schema_privilege(tn.oid, 'USAGE') AND has_table_privilege(tc.oid, 'SELECT')) THEN
               (xpath('/row/m/text()', query_to_xml(format('select max(%I) as m from %I.%I', s.col, s.tbl_schema, s.tbl), false, true, '')))[1]::text::bigint
           END AS max_id
    FROM seqs s
)
SELECT schemaname, sequencename, mapped_by, tbl_schema || '.' || tbl AS owning_table,
       increment_by, last_value, is_called, next_value, max_id,
       next_value - max_id                         AS headroom,
       (max_id IS NOT NULL AND next_value <= max_id) AS will_collide,
       CASE WHEN max_id IS NOT NULL AND next_value <= max_id THEN
           (xpath('/row/n/text()', query_to_xml(format('select count(*) as n from %I.%I where %I >= %s', tbl_schema, tbl, col, next_value), false, true, '')))[1]::text::bigint
       END                                          AS occupied_ahead
FROM measured
ORDER BY will_collide DESC NULLS LAST, schemaname, sequencename;

-- 2. Discriminator for the donor collision. If donors with id BELOW max(id) were created
--    AFTER max(id)'s own created_at, the sequence had already been rewound beneath existing
--    rows at that time. The created_at of the earliest such row dates the rewind:
--      - earliest such row => sequence behind max(id) on PROD under 3.x: will collide whatever server runs
--      - no such rows                                        => prod sequences are healthy
WITH top AS (SELECT id, created_at FROM donors ORDER BY id DESC LIMIT 1)
SELECT d.id, d.created_at, d.updated_at, left(d.name, 30) AS name_prefix,
       (d.id < top.id AND d.created_at > top.created_at) AS created_below_max_after_it
FROM donors d, top
ORDER BY d.id DESC
LIMIT 60;

-- 2b. Same question summarised by month: how many donors were created with an id below the
--     then-existing max(id). Any month before 2026-10 with a non-zero count = pre-existing.
SELECT date_trunc('month', d.created_at) AS month, count(*) AS donors_created,
       count(*) FILTER (WHERE EXISTS (SELECT 1 FROM donors e
                                      WHERE e.id > d.id AND e.created_at < d.created_at)) AS created_below_an_older_row
FROM donors d
WHERE d.created_at >= now() - interval '24 months'
GROUP BY 1 ORDER BY 1;

-- 2c. Envers view of the same ids (independent of created_at, which is app-written):
--     first revision time per donor id in the top band. custom_rev_info.timestamp is epoch millis.
SELECT a.id, min(to_timestamp(r.timestamp / 1000.0)) AS first_revision_at,
       min(r.custom_user) AS first_user, count(*) AS revisions
FROM donors_audit_trail a
JOIN custom_rev_info r ON r.id = a.rev
WHERE a.id >= (SELECT max(id) - 60 FROM donors)
GROUP BY a.id ORDER BY a.id DESC;

-- 3. Same pattern on the other high-traffic tables: rows whose id is below an OLDER row's id
--    (cheap proxy for "sequence was behind" at the time). created_at exists on these tables.
SELECT 'kits' AS tbl, count(*) FROM kits k
 WHERE k.created_at > (SELECT created_at FROM kits ORDER BY id DESC LIMIT 1)
   AND k.id < (SELECT max(id) FROM kits)
UNION ALL
SELECT 'device_requests', count(*) FROM device_requests x
 WHERE x.created_at > (SELECT created_at FROM device_requests ORDER BY id DESC LIMIT 1)
   AND x.id < (SELECT max(id) FROM device_requests)
UNION ALL
SELECT 'referring_organisations', count(*) FROM referring_organisations x
 WHERE x.created_at > (SELECT created_at FROM referring_organisations ORDER BY id DESC LIMIT 1)
   AND x.id < (SELECT max(id) FROM referring_organisations);

-- 4. When did migrations last land here (dates the 4.x deploy / any restore-then-migrate).
SELECT installed_rank, version, description, installed_by, installed_on, success
FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 10;

ROLLBACK;
