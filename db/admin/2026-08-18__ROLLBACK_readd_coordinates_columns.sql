-- EMERGENCY ROLLBACK for #161 — only needed if production is re-pinned to an image built
-- BEFORE the coordinates removal, after V26.08.18.1700 has dropped the columns.
--
-- WHY THIS EXISTS. #161 was designed as two deploys precisely so that a rollback would never
-- need this. Tony chose a single combined promote on 2026-08-18, which is a defensible trade —
-- one evening instead of two — but it means the emergency path is now TWO steps rather than
-- one. This is the second step. Have it open before starting the promote.
--
-- THE FAILURE IT FIXES. Production runs ddl-auto=none, so an older image does not fail at boot
-- on a missing column; it fails at RUNTIME, when Hibernate builds a SELECT naming
-- donors.coordinates or kits.coordinates. That is every donor read and every kit read — the
-- device index, the donor index, every detail page. It looks like a total outage of the
-- dashboard's main screens rather than a schema problem, so recognise it by the SQL error
-- ("column k1_0.coordinates does not exist") in the container logs, not by the symptom.
--
-- ORDER: re-pin the image FIRST, then run this. The app tolerates the column being absent for
-- the seconds in between only if it is not serving; do not wait for it to be healthy first.
--
-- WHAT IS NOT RESTORED. The data. 16 production donor rows held a real payload before the drop;
-- this brings the columns back EMPTY. That is acceptable because the whole point of #161 was
-- that the values are personal data with no consumer — the old code reads NULL and works. If
-- the values themselves are ever wanted back, they are only in a pre-drop dump or PITR.
--
-- SAFE TO RUN TWICE. IF NOT EXISTS on both.
--
-- AFTERWARDS: this leaves the database ahead of Flyway's history (the columns exist, but
-- V26.08.18.1700 is recorded as applied). That is fine for a rollback and self-corrects when
-- you roll forward again — 1700 is guarded with IF EXISTS, so re-running the newer image simply
-- drops them once more. Do NOT delete the flyway_schema_history row.

\pset pager off
\set ON_ERROR_STOP on

\echo '=== GUARD ==='
DO $$ BEGIN
    IF current_database() NOT IN ('techaid_prod', 'techaid_uat') THEN
        RAISE EXCEPTION 'ABORT: connected to %', current_database();
    END IF;
    RAISE NOTICE 'database: %, user: %', current_database(), current_user;
END $$;

\echo '=== BEFORE ==='
SELECT table_name, column_name FROM information_schema.columns
 WHERE column_name = 'coordinates' AND table_schema = 'public' ORDER BY table_name;

\echo '=== RE-ADD (empty) ==='
ALTER TABLE donors ADD COLUMN IF NOT EXISTS coordinates jsonb;
ALTER TABLE kits   ADD COLUMN IF NOT EXISTS coordinates jsonb;

\echo '=== AFTER: both must be present ==='
SELECT table_name, column_name, data_type, is_nullable
  FROM information_schema.columns
 WHERE column_name = 'coordinates' AND table_schema = 'public' ORDER BY table_name;

-- The GDPR routine is a separate concern. The rolled-back image does not run it; the Friday
-- job does, and by then you will either have rolled forward or replaced the function. The
-- coordinates-scrubbing version is in
-- db/admin/2026-08-13__admin_apply_referee_retention_activity_scope.sql if it is ever needed
-- back, but with the columns empty there is nothing for it to scrub.
