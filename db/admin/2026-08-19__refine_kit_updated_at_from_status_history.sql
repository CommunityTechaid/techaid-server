-- Refines kits.updated_at for the devices that 2026-08-18__correct_kit_updated_at_collateral.sql
-- had to fall back to created_at, using the one source of truth that #148 never touched:
-- the kit's own status history.
--
-- RUN AS: api_prod against techaid_prod (or api_uat/techaid_uat - the guard accepts either).
-- Read the SUMMARY section first; the UPDATE is at the bottom and is guarded by the backup
-- table existing.
--
-- ---------------------------------------------------------------------------------------
-- WHY THIS EXISTS
--
-- The 18 August script replaced a #148 burst timestamp with the updated_at recorded at the
-- kit's most recent ATTRIBUTABLE real change. For 654 production kits the whole audit trail
-- was burst-shaped, so there was no attributable change to point at and they fell back to
-- created_at. That was deliberate and it deliberately UNDERSTATED: those devices demonstrably
-- moved through the pipeline, the edits just were not captured.
--
-- Those devices left one piece of evidence behind that the bug could not corrupt.
-- status_updated_at is a plain column, written only by the application when the status
-- actually changes (KitInputs.kt / KitMutations.kt: `if (status has changed) statusUpdatedAt =
-- Instant.now()`). #148 was a dirty-check defect: a spurious rewrite wrote the SAME
-- status_updated_at value back. Only updated_at (@UpdateTimestamp) moved. So where
-- status_updated_at is LATER than the value we assigned, we know for a fact that the device
-- changed at that moment, and updated_at must be at least that recent.
--
-- ---------------------------------------------------------------------------------------
-- SCOPE, AND THE 12 THIS DELIBERATELY DOES NOT TOUCH
--
-- Measured in production 2026-08-19: 666 of the 2,116 corrected kits have status_updated_at
-- later than their updated_at. They split perfectly along the source recorded on 18 August:
--
--   654  source = 'created_at ...'                 EVERY one confirmed by an explicit status
--                                                  transition in kit_audit_trail carrying
--                                                  exactly that timestamp.        -> IN SCOPE
--    12  source = 'last non-collateral revision'   NONE confirmed that way.    -> OUT OF SCOPE
--
-- The 12 are left alone on purpose. They already rest on a real, non-collateral revision, so
-- they are not the problem this script exists to solve; their gaps are 0-36 days; and the
-- audit trail does not pin the transition the way it does for the 654. Weaker evidence for a
-- smaller gain on rows that are already defensible is not a trade worth making. They are
-- listed in SUMMARY 4 so the decision stays visible rather than silent.
--
-- THE GATE IS THE AUDIT TRAIL, NOT JUST THE COLUMN COMPARISON. A row qualifies only when
-- kit_audit_trail contains a revision for that kit where the status differs from the previous
-- revision AND status_updated_at equals the live value. Comparing the two columns alone would
-- have swept in the 12.
--
-- ---------------------------------------------------------------------------------------
-- THIS SCRIPT MOVES updated_at FORWARD. THAT IS THE OPPOSITE OF 18 AUGUST, AND INTENDED.
--
-- The 18 August script asserted "updated_at must not claim a change that never happened" and
-- so could only move values BACK; its sanity check failed the run if anything moved forward.
-- This script asserts the mirror image: "updated_at must not deny a change that provably did
-- happen." Every row here moves forward to a timestamp at which a status transition is
-- recorded in the audit trail. Do not copy the 18 August sanity check into this file.
--
-- WHERE THE VALUES LAND. All 654 land in 2021-2025 (range 2021-11-23 to 2025-11-28); not one
-- lands in 2026. Verified 2026-08-19. So this changes NO 2026 reporting figure on any basis.
-- The month-level deltas are printed in SUMMARY 3 before anything is written.
--
-- A SHARED TIMESTAMP HERE IS NOT A #148 BURST. 68 of the 654 share 2023-07-19 13:31:00. All
-- 68 were created that same day, all are ALLOCATION_READY, and each has a real status
-- transition at that revision - a genuine bulk intake. No kit anywhere carries an updated_at
-- in that second, so the bug is not involved. A shared status_updated_at means the status
-- really changed for all of them at once; a shared updated_at was the bug. Different animals.
--
-- WHY PLAIN SQL. Doing this through the application would audit updated_at and create 654
-- more revisions - fixing the symptom by adding to the cause. Direct SQL bypasses Envers.
-- There is no database trigger on kits.updated_at; @UpdateTimestamp is application-side.
--
-- IDEMPOTENT. After a successful run, status_updated_at <= updated_at for every row it
-- touched, so the scope is empty and a second run is a no-op. Verified in POST-CHECK 3.
--
-- ROLLBACK. The backup table holds the previous value for every row touched:
--   UPDATE kits k SET updated_at = b.old_updated_at
--     FROM kit_updated_at_refine_backup_20260819 b WHERE b.kit_id = k.id;
-- That restores the 18 August values. To go all the way back to the pre-correction values,
-- roll this back FIRST, then apply the 18 August rollback from
-- kit_updated_at_backup_20260818. Rolling back in the other order loses the intermediate
-- state and leaves rows the 18 August table cannot restore.
-- ---------------------------------------------------------------------------------------

\pset pager off
\set ON_ERROR_STOP on
SET statement_timeout = '15min';

\echo '=== GUARD ==='
DO $$ BEGIN
    IF current_database() NOT IN ('techaid_prod', 'techaid_uat') THEN
        RAISE EXCEPTION 'ABORT: connected to %', current_database();
    END IF;
    IF to_regclass('public.kit_updated_at_backup_20260818') IS NULL THEN
        RAISE EXCEPTION 'ABORT: kit_updated_at_backup_20260818 is missing - the 18 August correction has not been applied to this database, so there is nothing to refine';
    END IF;
    RAISE NOTICE 'database: %, user: %', current_database(), current_user;
END $$;

-- One shared definition, materialised so the summaries, the backup and the UPDATE cannot
-- disagree with each other. Dropped at the end of the session automatically.
CREATE TEMP TABLE refine AS
WITH candidate AS (
    SELECT k.id,
           k.updated_at         AS old_updated_at,
           k.status_updated_at  AS new_updated_at,
           k.status,
           b.source             AS source_18aug
      FROM kit_updated_at_backup_20260818 b
      JOIN kits k ON k.id = b.kit_id
     WHERE k.status_updated_at IS NOT NULL
       AND k.status_updated_at > k.updated_at
),
trail AS (
    SELECT a.id, a.rev, a.status, a.status_updated_at,
           lag(a.status) OVER (PARTITION BY a.id ORDER BY a.rev) AS prev_status
      FROM kit_audit_trail a
     WHERE a.id IN (SELECT id FROM candidate)
)
SELECT c.*,
       EXISTS (
           SELECT 1 FROM trail t
            WHERE t.id = c.id
              AND t.status IS DISTINCT FROM t.prev_status
              AND t.status_updated_at = c.new_updated_at
       ) AS transition_confirmed
  FROM candidate c;

CREATE INDEX ON refine (id);
ANALYZE refine;

\echo ''
\echo '=== SUMMARY 1: candidates, split by the source recorded on 18 August ==='
SELECT source_18aug,
       count(*)                                          AS candidates,
       count(*) FILTER (WHERE transition_confirmed)       AS confirmed_in_scope,
       count(*) FILTER (WHERE NOT transition_confirmed)   AS unconfirmed_left_alone
  FROM refine GROUP BY 1 ORDER BY 2 DESC;

\echo ''
\echo '=== SUMMARY 2: how far forward each in-scope kit moves ==='
SELECT CASE WHEN d < 1 THEN 'under a day'
            WHEN d < 31 THEN 'under a month'
            WHEN d < 183 THEN '1-6 months'
            WHEN d < 366 THEN '6-12 months'
            ELSE 'over a year' END AS gain,
       count(*) AS kits, min(d) AS min_days, max(d) AS max_days
  FROM (SELECT round(extract(epoch FROM (new_updated_at - old_updated_at)) / 86400.0) AS d
          FROM refine WHERE transition_confirmed) x
 GROUP BY 1 ORDER BY min(d);

\echo ''
\echo '=== SUMMARY 3: reporting delta by month (nothing here may land in 2026) ==='
WITH mv AS (
    SELECT date_trunc('month', old_updated_at) m, -1 AS d FROM refine WHERE transition_confirmed
    UNION ALL
    SELECT date_trunc('month', new_updated_at) m,  1     FROM refine WHERE transition_confirmed
)
SELECT to_char(m, 'YYYY-MM') AS month, sum(d) AS net_change
  FROM mv GROUP BY 1 HAVING sum(d) <> 0 ORDER BY 1;

\echo ''
\echo '=== SUMMARY 4: the candidates this script deliberately leaves alone ==='
SELECT id, status, old_updated_at, new_updated_at,
       round(extract(epoch FROM (new_updated_at - old_updated_at)) / 86400.0) AS days_gap
  FROM refine WHERE NOT transition_confirmed ORDER BY id;

-- ---------------------------------------------------------------------------------------
-- BACKUP. A real table, not a psql \copy: it survives the session, it is what the rollback
-- statement reads, and it records the reasoning next to each value.
-- ---------------------------------------------------------------------------------------
\echo ''
\echo '=== BACKUP: building kit_updated_at_refine_backup_20260819 ==='
DROP TABLE IF EXISTS kit_updated_at_refine_backup_20260819;

CREATE TABLE kit_updated_at_refine_backup_20260819 AS
SELECT id                AS kit_id,
       old_updated_at,
       new_updated_at,
       'status_updated_at, confirmed by a status transition in kit_audit_trail' AS source,
       source_18aug,
       now()             AS backed_up_at
  FROM refine
 WHERE transition_confirmed;

ALTER TABLE kit_updated_at_refine_backup_20260819 ADD PRIMARY KEY (kit_id);

\echo ''
\echo '=== SANITY: everything must move FORWARD, stay in the past, and stay non-null ==='
\echo '(the 18 August script required the opposite direction - see the header)'
SELECT count(*)                                                    AS rows_to_update,
       count(*) FILTER (WHERE new_updated_at <= old_updated_at)    AS moves_backward_must_be_0,
       count(*) FILTER (WHERE new_updated_at IS NULL)              AS null_target_must_be_0,
       count(*) FILTER (WHERE new_updated_at > now())              AS in_the_future_must_be_0,
       count(*) FILTER (WHERE new_updated_at >= date '2026-01-01') AS lands_in_2026_must_be_0
  FROM kit_updated_at_refine_backup_20260819;

-- ---------------------------------------------------------------------------------------
-- APPLY
-- ---------------------------------------------------------------------------------------
\echo ''
\echo '=== APPLY ==='
BEGIN;

DO $$
DECLARE
    n bigint;
    bad bigint;
BEGIN
    SELECT count(*) INTO n FROM kit_updated_at_refine_backup_20260819;
    IF n = 0 THEN
        RAISE EXCEPTION 'ABORT: backup table is empty - refusing to run an unrecoverable update';
    END IF;

    SELECT count(*) INTO bad FROM kit_updated_at_refine_backup_20260819
     WHERE new_updated_at IS NULL
        OR new_updated_at <= old_updated_at
        OR new_updated_at > now();
    IF bad > 0 THEN
        RAISE EXCEPTION 'ABORT: % backup rows fail the sanity check', bad;
    END IF;

    RAISE NOTICE 'applying % rows', n;
END $$;

UPDATE kits k
   SET updated_at = b.new_updated_at
  FROM kit_updated_at_refine_backup_20260819 b
 WHERE b.kit_id = k.id
   AND k.updated_at = b.old_updated_at;   -- refuse to overwrite a value that moved since the backup

COMMIT;

-- ---------------------------------------------------------------------------------------
-- POST-CHECKS
-- ---------------------------------------------------------------------------------------
\echo ''
\echo '=== POST-CHECK 1: every backed-up row now holds its new value ==='
SELECT count(*)                                          AS backed_up,
       count(*) FILTER (WHERE k.updated_at = b.new_updated_at) AS applied_must_match,
       count(*) FILTER (WHERE k.updated_at <> b.new_updated_at) AS mismatched_must_be_0
  FROM kit_updated_at_refine_backup_20260819 b JOIN kits k ON k.id = b.kit_id;

\echo ''
\echo '=== POST-CHECK 2: no kit is left resting on the created_at fallback ==='
\echo '(this is the point of the exercise - it must be 0)'
SELECT count(*) AS still_on_created_at_with_better_evidence_available
  FROM kit_updated_at_backup_20260818 b
  JOIN kits k ON k.id = b.kit_id
 WHERE b.source LIKE 'created_at%'
   AND k.status_updated_at > k.updated_at;

\echo ''
\echo '=== POST-CHECK 3: idempotency - re-running would now select nothing in scope ==='
SELECT count(*) AS remaining_candidates,
       count(*) FILTER (WHERE b.source LIKE 'created_at%') AS of_which_created_at_fallback
  FROM kit_updated_at_backup_20260818 b
  JOIN kits k ON k.id = b.kit_id
 WHERE k.status_updated_at > k.updated_at;

\echo ''
\echo '=== POST-CHECK 4: nothing outside the intended set moved ==='
SELECT count(*) AS kits_updated_today_not_in_backup
  FROM kits k
 WHERE k.updated_at::date = CURRENT_DATE
   AND NOT EXISTS (SELECT 1 FROM kit_updated_at_refine_backup_20260819 b WHERE b.kit_id = k.id);
