-- Disable the pg_cron weekly GDPR retention job in production, handing retention over to the
-- in-app scheduled job (GdprDonorCleanup) shipped in V26.08.11.1450/1600/1650 + PR #135.
--
-- !! CONNECT TO THE `postgres` DATABASE, NOT `techaid_prod` !!
--   Unlike every other file in this directory, pg_cron's catalog lives in the Azure Flexible
--   Server maintenance database. Measured 2026-08-12: `SELECT * FROM cron.job` from techaid_prod
--   fails with `relation "cron.job" does not exist`. The job's `database` column is what points
--   it at techaid_prod; the job row itself is in `postgres`.
--
-- PREREQUISITES - DO NOT RUN THIS UNTIL ALL THREE HOLD (issue #62's own guard comment)
--   1. db/admin/2026-08-13__admin_apply_gdpr_retention_scope_prod.sql has been applied, so the
--      new function/view are live and api_prod holds USAGE on gdpr + EXECUTE on
--      gdpr.performgdprcleanup(). Measured 2026-08-12: prod's gdpr schema has nspacl = NULL,
--      i.e. NO role has ever been granted anything on it.
--   2. The `gdpr-in-app-cleanup` feature flag is `true` in production.
--   3. The in-app job has run successfully in production AT LEAST ONCE **as api_prod** - not as
--      techaid_admin. A run performed by techaid_admin proves the function body works; it does
--      NOT prove the grant in (1) works, and the grant is the only thing that has never been
--      exercised in production. Confirm via a row in public.gdpr_cleanup_runs whose timing
--      matches an app-triggered run, plus the "GDPR retention cleanup finished" log line.
--
-- WHY NOT A FLYWAY MIGRATION
--   Standard reason for this directory (see README.md): the gdpr objects are owned by
--   techaid_admin and Flyway runs as api_prod. Additionally the pg_cron catalog is in a
--   different database entirely, which Flyway has no connection to at all.
--
-- REVERSIBILITY
--   alter_job(..., active := false) is used deliberately in preference to cron.unschedule(2):
--   it keeps the job definition (schedule, command, target database, owner) intact, so the
--   rollback is a single symmetric statement. cron.unschedule() would delete the row and the
--   job would have to be recreated by hand from this file's recorded values.
--
--   ROLLBACK (re-enable pg_cron, e.g. if the in-app job proves unreliable):
--       SELECT cron.alter_job(2, active := true);
--
--   Recorded job definition as measured 2026-08-12, in case it ever must be recreated:
--       jobid    = 2
--       jobname  = 'gdpr-weekly-cleanup'
--       schedule = '4 4 * * 6'          -- Saturdays 04:04 UTC
--       command  = 'SELECT gdpr.PerformGDPRCleanup()'
--       database = 'techaid_prod'
--       username = 'techaid_admin'
--       active   = true
--   Recreate with:
--       SELECT cron.schedule_in_database('gdpr-weekly-cleanup', '4 4 * * 6',
--              'SELECT gdpr.PerformGDPRCleanup()', 'techaid_prod');
--
-- WHAT CHANGES OPERATIONALLY
--   Retention stops running Saturdays 04:04 UTC and starts running on the app's own schedule:
--   Mondays 09:30 Europe/London (@Scheduled, 1-hour debounce), with an ApplicationReadyEvent
--   catch-up that fires on any boot where the last recorded run is more than 7 days old.
--   Production's KEDA cron rule holds one replica Mon-Fri 08:00-20:00 London, so the Monday
--   09:30 slot lands inside a guaranteed warm window; the catch-up is the backstop for a
--   missed slot, not the primary trigger.
--
--   KNOWN GAP, ACCEPTED: if the Monday 09:30 slot is missed (a restart at 09:31, a failed
--   deploy), the 7-day catch-up threshold means the next opportunity is the first boot after
--   the following Thursday. For a weekly retention job that is tolerable, but it is real, and
--   it is the cost of disabling pg_cron before the in-app path has accumulated history.
--
-- APPLIED TO
--   (none yet)

DO $$
BEGIN
    IF current_database() <> 'postgres' THEN
        RAISE EXCEPTION 'ABORT: run this against the `postgres` maintenance database, not %',
                        current_database();
    END IF;
END $$;

-- Refuse to touch jobid 2 unless it is still the job this file was written against. Guards
-- against the job having been unscheduled and recreated with a different id in the meantime.
DO $$
DECLARE
    j record;
BEGIN
    SELECT jobid, jobname, schedule, database, active INTO j FROM cron.job WHERE jobid = 2;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'ABORT: no pg_cron job with jobid 2 - re-measure cron.job before running';
    END IF;

    IF j.jobname <> 'gdpr-weekly-cleanup' OR j.database <> 'techaid_prod' THEN
        RAISE EXCEPTION 'ABORT: jobid 2 is now %/% - not the gdpr-weekly-cleanup/techaid_prod job this file targets',
                        j.jobname, j.database;
    END IF;

    IF NOT j.active THEN
        RAISE NOTICE 'jobid 2 (%) is already inactive - nothing to do', j.jobname;
    END IF;
END $$;

SELECT '=== BEFORE ===' AS section;
SELECT jobid, jobname, schedule, command, active, database, username FROM cron.job ORDER BY jobid;
SELECT jobid, status, start_time, return_message
  FROM cron.job_run_details WHERE jobid = 2 ORDER BY start_time DESC LIMIT 5;

SELECT cron.alter_job(2, active := false);

SELECT '=== AFTER (expect active = f) ===' AS section;
SELECT jobid, jobname, schedule, active, database FROM cron.job ORDER BY jobid;
