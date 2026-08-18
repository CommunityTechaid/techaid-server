-- Deletes the pg_cron job `gdpr-weekly-cleanup` (jobid 2) outright, completing the retention
-- switchover that began at the 2026-08-12 cutover.
--
-- RUN AS: techaid_admin, connected to the `postgres` DATABASE (not techaid_prod). pg_cron jobs
-- live in the database the extension is installed in; the job's own `database` column is what
-- points it at techaid_prod. Connecting to the wrong database makes cron.job look empty and is
-- indistinguishable from "already gone" — see the role trap below.
--
-- HISTORY. 2026-08-13 disabled the job (`active = f`) via
-- 2026-08-13__disable_pg_cron_gdpr_weekly_prod.sql, deliberately keeping it as the rollback
-- path while the in-app job proved itself. It has since run on schedule from the application
-- (gdpr_cleanup_runs id 19, Fri 2026-08-14 18:00 London), and no Saturday row has appeared,
-- confirming the disabled job was not firing. Tony took the decision on 2026-08-18 to stop
-- carrying the fallback and make the in-app job the single retention path.
--
-- WHAT THIS DOES NOT TOUCH. gdpr.performgdprcleanup() and the gdpr.* views stay exactly where
-- they are — the application calls the same routine this job called. Retiring pg_cron retires
-- the SCHEDULE, not the retention logic. Dropping that function would stop retention dead.
--
-- AFTER THIS, THERE IS NO DATABASE-SIDE FALLBACK. If the in-app job ever has to be stopped or
-- replaced, retention is re-established by deploying code, not by re-enabling anything here.
-- Re-creating the job, should it ever be wanted, is:
--
--     SELECT cron.schedule('gdpr-weekly-cleanup', '4 4 * * 6',
--                          'SELECT gdpr.PerformGDPRCleanup()');
--     UPDATE cron.job SET database = 'techaid_prod' WHERE jobname = 'gdpr-weekly-cleanup';
--
-- ...but note the job would then run the function as techaid_admin on a Saturday, an hour when
-- nothing is watching, which is most of why it was replaced.
--
-- THE ROLE TRAP. cron.job shows a non-superuser only its OWN jobs, so running any of this as
-- api_prod returns zero rows whether the job is healthy or absent. "Empty" is only evidence
-- when you are techaid_admin. Its password is in Bitwarden, not in any Container App secret.

\pset pager off

\echo '=== GUARD: must be the postgres database ==='
DO $$ BEGIN
    IF current_database() <> 'postgres' THEN
        RAISE EXCEPTION 'ABORT: connected to %, expected postgres', current_database();
    END IF;
END $$;

\echo '=== BEFORE: the job as it stands ==='
SELECT jobid, jobname, schedule, command, database, username, active
  FROM cron.job
 ORDER BY jobid;

\echo '=== BEFORE: its run history, the only record of the pg_cron era ==='
-- gdpr_cleanup_runs already carries the counts (13 rows backfilled from Azure PostgreSQLLogs
-- on 2026-08-12, 2026-05-16 through 2026-08-08). This is the scheduler-side view of the same
-- runs; printed for the record because unscheduling may take the detail rows with it.
SELECT jobid, status, start_time, end_time, return_message
  FROM cron.job_run_details
 WHERE jobid = 2
 ORDER BY start_time DESC
 LIMIT 20;

\echo '=== DELETE ==='
SELECT cron.unschedule('gdpr-weekly-cleanup');

\echo '=== AFTER: gdpr-weekly-cleanup must be absent ==='
SELECT jobid, jobname, schedule, database, active
  FROM cron.job
 ORDER BY jobid;

SELECT count(*) AS gdpr_jobs_remaining
  FROM cron.job
 WHERE jobname = 'gdpr-weekly-cleanup';
