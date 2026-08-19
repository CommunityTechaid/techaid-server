-- Corrects kits.updated_at for devices whose timestamp was written by #148 collateral, not by
-- anyone touching the device.
--
-- RUN AS: api_prod against techaid_prod (or api_uat/techaid_uat - the guard accepts either).
-- Read the SUMMARY section first; the UPDATE is at the bottom and is guarded by the backup
-- table existing.
--
-- ---------------------------------------------------------------------------------------
-- WHAT WENT WRONG (#148, fixed in code by #149/#157, live in production since 2026-08-13)
--
-- KitAttributes is mapped to a jsonb column and had no value-based equals, so Hibernate's
-- dirty check fell through to identity and reported EVERY loaded kit as dirty. Mutations that
-- touched a device request's kits collection pulled in every sibling kit, and each one was
-- rewritten at commit with a fresh updated_at - and, updated_at being audited, a kit_audit_trail
-- revision to match.
--
-- Measured in production 2026-08-18: 83,179 audit rows are collateral, in revisions that
-- touched more than one kit. The largest single revision rewrote 2,927 kits. 2,116 live kits
-- still carry a timestamp that came from one of those bursts.
--
-- WHY IT MATTERS. updatedAt is a sortable column on the device index AND a filter
-- (updatedAt: TimeComparison in kits.graphqls), so "devices touched this week" currently
-- returns devices nobody touched.
--
-- ---------------------------------------------------------------------------------------
-- HOW A COLLATERAL ROW IS IDENTIFIED
--
-- A kit_audit_trail row is collateral when BOTH hold:
--   1. every audited column except updated_at is identical to that kit's previous revision, AND
--   2. more than one kit shares its revision.
--
-- Condition 2 is not optional. KitAttributes is @NotAudited, so a genuine edit to notes,
-- status, credentials or network bumps updated_at and lands an audit row that is
-- indistinguishable from a spurious one on condition 1 alone. 32,733 rows look spurious on
-- condition 1 but sit alone in their revision; those are real edits and are NOT touched here.
-- Envers writes one revision per transaction, so a burst is what the bug looked like and a
-- lone row is what a real attributes edit looks like.
--
-- THE REPLACEMENT VALUE is the updated_at recorded on that kit's most recent ATTRIBUTABLE
-- real change - i.e. a value the column actually held at a moment the device was really
-- changed. A revision is attributable when it is not collateral AND one of: it is the kit's
-- creation (revtype 0), it has a predecessor to compare against, or it stands alone in its
-- revision. That last clause matters for exactly 2 production kits whose earliest audit row is
-- a lone single-kit MOD - no predecessor, but no burst either, so it is real.
--
-- THE FIRST REVISION OF A KIT IS NOT EVIDENCE OF A REAL CHANGE unless it is the creation.
-- 654 production kits were created in 2021-2023 but their earliest audit row is a 2026 MOD:
-- auditing did not capture their creation, so there is nothing to compare that row against.
-- Those rows are burst-shaped - kits 2669-2676 all carry 2026-01-13 14:37:43.98x, milliseconds
-- apart, in the peak month of the bug - so treating them as real would re-assert the exact
-- false signal this script removes. They fall back to created_at.
--
-- created_at UNDERSTATES for those 654: the devices demonstrably moved through the pipeline
-- between 2021 and 2026, and those edits are simply not in the trail. It is chosen anyway
-- because the purpose here is that updated_at should not claim a recent change that did not
-- happen. Understating drops them out of "recently updated"; overstating leaves them in.
-- If a better source for their real history ever appears, the backup table holds what they
-- had and this reasoning is what to revisit.
--
-- NULL HANDLING IS EXPLICIT AND LOAD-BEARING. body = prev_body is NULL on a first revision,
-- so `revtype = 1 AND body = prev_body AND kits_in_rev > 1` is NULL rather than false, and a
-- bare `FILTER (WHERE NOT is_collateral)` silently drops those rows from BOTH branches. That
-- accident produced the right answer for the 654 for the wrong reason. It is now spelled out.
--
-- WHY PLAIN SQL. Doing this through the application would audit updated_at and create 2,116
-- MORE revisions - fixing the symptom by adding to the cause. Direct SQL bypasses Envers.
-- There is no database trigger on kits.updated_at; @UpdateTimestamp is application-side.
--
-- ROLLBACK. The backup table holds the previous value for every row touched:
--   UPDATE kits k SET updated_at = b.old_updated_at
--     FROM kit_updated_at_backup_20260818 b WHERE b.kit_id = k.id;
-- ---------------------------------------------------------------------------------------

\pset pager off
\set ON_ERROR_STOP on
SET statement_timeout = '15min';

\echo '=== GUARD ==='
DO $$ BEGIN
    IF current_database() NOT IN ('techaid_prod', 'techaid_uat') THEN
        RAISE EXCEPTION 'ABORT: connected to %', current_database();
    END IF;
    RAISE NOTICE 'database: %, user: %', current_database(), current_user;
END $$;

-- One shared definition, materialised so the summary, the backup and the UPDATE cannot
-- disagree with each other. Dropped at the end of the session automatically.
CREATE TEMP TABLE collateral AS
WITH seq AS (
    SELECT k.id, k.rev, k.revtype, k.updated_at,
           to_jsonb(k) - 'rev' - 'revtype' - 'updated_at' AS body,
           lag(to_jsonb(k) - 'rev' - 'revtype' - 'updated_at') OVER (PARTITION BY k.id ORDER BY k.rev) AS prev_body,
           count(*) OVER (PARTITION BY k.rev) AS kits_in_rev
      FROM kit_audit_trail k
)
SELECT id, rev, updated_at, kits_in_rev,
       COALESCE(revtype = 1 AND body = prev_body AND kits_in_rev > 1, false) AS is_collateral,
       -- Can this revision be read as evidence of a real change? A first revision has no
       -- predecessor, so the body comparison cannot speak for it - but a creation always can,
       -- and a revision that touched only this kit was nobody's collateral.
       (revtype = 0 OR prev_body IS NOT NULL OR kits_in_rev = 1)             AS attributable
  FROM seq;

CREATE INDEX ON collateral (id, rev);
ANALYZE collateral;

\echo ''
\echo '=== SUMMARY 1: collateral audit rows by year ==='
SELECT to_char(date_trunc('year', updated_at), 'YYYY') AS year,
       count(*)                                        AS collateral_rows,
       count(DISTINCT id)                              AS kits_affected,
       max(kits_in_rev)                                AS biggest_single_burst
  FROM collateral
 WHERE is_collateral
 GROUP BY 1 ORDER BY 1;

\echo ''
\echo '=== SUMMARY 2: the most recent pollution, and when it stopped ==='
SELECT max(updated_at)                                            AS most_recent_collateral,
       count(*) FILTER (WHERE updated_at >= CURRENT_DATE - 7)     AS in_the_last_7_days,
       count(*) FILTER (WHERE updated_at >= CURRENT_DATE - 30)    AS in_the_last_30_days
  FROM collateral
 WHERE is_collateral;

\echo ''
\echo '=== SUMMARY 3: the ten most recent collateral revisions ==='
SELECT rev, min(updated_at) AS at, max(kits_in_rev) AS kits_in_that_revision
  FROM collateral
 WHERE is_collateral
 GROUP BY rev ORDER BY at DESC LIMIT 10;

-- ---------------------------------------------------------------------------------------
-- BACKUP. A real table, not a psql \copy: it survives the session, it is what the rollback
-- statement reads, and it records the reasoning (source) next to each value.
-- ---------------------------------------------------------------------------------------
\echo ''
\echo '=== BACKUP: building kit_updated_at_backup_20260818 ==='
DROP TABLE IF EXISTS kit_updated_at_backup_20260818;

CREATE TABLE kit_updated_at_backup_20260818 AS
WITH newest AS (
    SELECT DISTINCT ON (id) id, is_collateral FROM collateral ORDER BY id, rev DESC
),
truth AS (
    SELECT id, max(updated_at) FILTER (WHERE attributable AND NOT is_collateral) AS true_updated
      FROM collateral GROUP BY id
)
SELECT k.id                                              AS kit_id,
       k.updated_at                                      AS old_updated_at,
       COALESCE(t.true_updated, k.created_at)             AS new_updated_at,
       CASE WHEN t.true_updated IS NOT NULL
            THEN 'last non-collateral revision'
            ELSE 'created_at (trail holds no attributable real change)' END AS source,
       now()                                             AS backed_up_at
  FROM kits k
  JOIN newest n ON n.id = k.id
  LEFT JOIN truth t ON t.id = k.id
 WHERE n.is_collateral;

ALTER TABLE kit_updated_at_backup_20260818 ADD PRIMARY KEY (kit_id);

\echo ''
\echo '=== BACKUP CONTENTS ==='
SELECT source, count(*) AS kits,
       min(new_updated_at) AS earliest_restored, max(new_updated_at) AS latest_restored
  FROM kit_updated_at_backup_20260818
 GROUP BY source ORDER BY 2 DESC;

\echo ''
\echo '=== SANITY: nothing may move FORWARD, and nothing may go null ==='
SELECT count(*) FILTER (WHERE new_updated_at > old_updated_at) AS moves_forward_must_be_0,
       count(*) FILTER (WHERE new_updated_at IS NULL)          AS null_target_must_be_0,
       count(*)                                                AS rows_to_update
  FROM kit_updated_at_backup_20260818;

\echo ''
\echo '=== HOW FAR BACK EACH KIT MOVES ==='
SELECT CASE WHEN d < 1 THEN 'under a day'
            WHEN d < 7 THEN '1-6 days'
            WHEN d < 31 THEN '1 week - 1 month'
            WHEN d < 183 THEN '1-6 months'
            ELSE 'over 6 months' END AS drift,
       count(*) AS kits, min(d) AS min_days, max(d) AS max_days
  FROM (SELECT round(extract(epoch FROM (old_updated_at - new_updated_at)) / 86400.0) AS d
          FROM kit_updated_at_backup_20260818) x
 GROUP BY 1 ORDER BY min(d);

-- ---------------------------------------------------------------------------------------
-- APPLY
-- ---------------------------------------------------------------------------------------
\echo ''
\echo '=== APPLY ==='
BEGIN;

DO $$
DECLARE
    n bigint;
BEGIN
    SELECT count(*) INTO n FROM kit_updated_at_backup_20260818;
    IF n = 0 THEN
        RAISE EXCEPTION 'ABORT: backup table is empty, refusing to update';
    END IF;
    IF EXISTS (SELECT 1 FROM kit_updated_at_backup_20260818 WHERE new_updated_at IS NULL) THEN
        RAISE EXCEPTION 'ABORT: backup holds a null replacement value';
    END IF;
    IF EXISTS (SELECT 1 FROM kit_updated_at_backup_20260818 WHERE new_updated_at > old_updated_at) THEN
        RAISE EXCEPTION 'ABORT: a replacement value moves a kit FORWARD in time';
    END IF;
    RAISE NOTICE 'updating % kits', n;
END $$;

UPDATE kits k
   SET updated_at = b.new_updated_at
  FROM kit_updated_at_backup_20260818 b
 WHERE b.kit_id = k.id
   AND k.updated_at = b.old_updated_at;   -- no-op if someone edited the kit since the backup

COMMIT;

\echo ''
\echo '=== VERIFY: every backed-up kit now holds its corrected value ==='
SELECT count(*) FILTER (WHERE k.updated_at = b.new_updated_at) AS corrected,
       count(*) FILTER (WHERE k.updated_at = b.old_updated_at) AS still_polluted_must_be_0,
       count(*)                                                AS total
  FROM kit_updated_at_backup_20260818 b JOIN kits k ON k.id = b.kit_id;

\echo ''
\echo '=== VERIFY: the device-index "recently updated" view is no longer inflated ==='
SELECT count(*) FILTER (WHERE updated_at >= CURRENT_DATE - 7)  AS kits_updated_last_7_days,
       count(*) FILTER (WHERE updated_at >= CURRENT_DATE - 30) AS kits_updated_last_30_days
  FROM kits;
