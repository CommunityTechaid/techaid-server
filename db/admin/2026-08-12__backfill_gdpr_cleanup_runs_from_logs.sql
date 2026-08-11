-- One-time backfill of public.gdpr_cleanup_runs (V26.08.11.1600) from Azure Monitor's
-- exported Postgres server logs, covering every 'GDPR Cleanup: Archived' RAISE LOG line
-- that was still retrievable when this was written.
--
-- WHY THIS IS NOT A FLYWAY MIGRATION
--   This is a one-time, production-only historical backfill, not a repeatable forward
--   change - the exact same reasoning as db/admin/2026-08-11__gdpr_historical_scrub.sql.
--   A Flyway migration would also fire on UAT and every fresh/test database, where it
--   would be flatly wrong: UAT has never had a pg_cron gdpr-weekly-cleanup job (issue #92),
--   so backfilling production's log history there would fabricate runs that never happened.
--
-- OWNERSHIP - NO techaid_admin CREDENTIAL NEEDED
--   gdpr_cleanup_runs is a plain public-schema table owned by api_prod (V26.08.11.1600 is
--   an ordinary, non-self-gated Flyway migration), unlike everything else under gdpr/. This
--   runs as the ordinary api_prod application role - same as the read-only measurements in
--   issue #93 (`az containerapp secret show -g tada-2026 -n api-production --secret-name
--   datasource-password`).
--
-- SOURCE DATA
--   Query run against Log Analytics workspace workspace-tada2026ubat on 2026-08-12:
--     AzureDiagnostics
--     | where Category == 'PostgreSQLLogs'
--     | where Message contains 'GDPR Cleanup'
--     | project TimeGenerated, Message
--     | order by TimeGenerated desc
--
--   13 rows recovered, 2026-05-16 through 2026-08-08 (weekly Saturday 04:04 UTC runs, no
--   gaps in that window). RAISE LOG always reaches the server log regardless of verbosity
--   settings, which is why the function used it. PostgreSQLLogs retention on this workspace
--   is 90 days (confirmed 2026-08-12), so 2026-05-16 is the oldest row that will EVER be
--   recoverable this way - anything from before then, and however long the job ran before
--   that, is gone permanently. This is a partial history by nature, not a gap to close.
--
--   Every recovered line predates V26.08.11.1400/1650 (deployed 2026-08-11), so only the
--   original two counters existed: donors archived and audit-trail donor records. The
--   newer columns (audit-trail parity, collection_contact_name, notes,
--   referring_organisation_contacts) are correctly 0 for every backfilled row - those
--   retention rules did not exist yet when these ran, so there is nothing they could have
--   archived.
--
-- APPLIED TO
--   (none yet - gdpr_cleanup_runs does not exist in techaid_prod until this branch is
--   promoted to production; run once after that, then update this line with the date.)

DO $$
BEGIN
    IF current_database() <> 'techaid_prod' THEN
        RAISE EXCEPTION 'ABORT: expected techaid_prod, got %', current_database();
    END IF;
END $$;

-- Fail loudly rather than silently double-inserting if this is run twice.
DO $$
DECLARE
    n bigint;
BEGIN
    SELECT count(*) INTO n FROM public.gdpr_cleanup_runs WHERE ran_at < '2026-08-09';
    IF n <> 0 THEN
        RAISE EXCEPTION 'ABORT: gdpr_cleanup_runs already has % row(s) before 2026-08-09 - already backfilled?', n;
    END IF;
END $$;

BEGIN;

INSERT INTO public.gdpr_cleanup_runs
    (ran_at, donor_count, donor_audit_count, device_request_details_count, device_request_clientref_count,
     device_request_contact_name_count, device_request_details_audit_count, device_request_clientref_audit_count,
     device_request_contact_name_audit_count, device_request_notes_count, referring_contact_count,
     referring_contact_audit_count, summary)
VALUES
    ('2026-05-16T04:04:02Z', 0, 0, 11, 24, 0, 0, 0, 0, 0, 0, 0,
     'GDPR Cleanup: Archived 0 inactive donors, 0 audit trail donor records, 11 device request details, and 24 device request client refs (backfilled from Azure PostgreSQLLogs, 2026-08-12)'),
    ('2026-05-23T04:04:01Z', 1, 0, 61, 23, 0, 0, 0, 0, 0, 0, 0,
     'GDPR Cleanup: Archived 1 inactive donors, 0 audit trail donor records, 61 device request details, and 23 device request client refs (backfilled from Azure PostgreSQLLogs, 2026-08-12)'),
    ('2026-05-30T04:04:02Z', 0, 0, 38, 20, 0, 0, 0, 0, 0, 0, 0,
     'GDPR Cleanup: Archived 0 inactive donors, 0 audit trail donor records, 38 device request details, and 20 device request client refs (backfilled from Azure PostgreSQLLogs, 2026-08-12)'),
    ('2026-06-06T04:04:02Z', 0, 0, 48, 22, 0, 0, 0, 0, 0, 0, 0,
     'GDPR Cleanup: Archived 0 inactive donors, 0 audit trail donor records, 48 device request details, and 22 device request client refs (backfilled from Azure PostgreSQLLogs, 2026-08-12)'),
    ('2026-06-13T04:04:02Z', 0, 0, 69, 30, 0, 0, 0, 0, 0, 0, 0,
     'GDPR Cleanup: Archived 0 inactive donors, 0 audit trail donor records, 69 device request details, and 30 device request client refs (backfilled from Azure PostgreSQLLogs, 2026-08-12)'),
    ('2026-06-20T04:04:02Z', 0, 0, 55, 13, 0, 0, 0, 0, 0, 0, 0,
     'GDPR Cleanup: Archived 0 inactive donors, 0 audit trail donor records, 55 device request details, and 13 device request client refs (backfilled from Azure PostgreSQLLogs, 2026-08-12)'),
    ('2026-06-27T04:04:02Z', 0, 0, 22, 17, 0, 0, 0, 0, 0, 0, 0,
     'GDPR Cleanup: Archived 0 inactive donors, 0 audit trail donor records, 22 device request details, and 17 device request client refs (backfilled from Azure PostgreSQLLogs, 2026-08-12)'),
    ('2026-07-04T04:04:02Z', 0, 0, 0, 33, 0, 0, 0, 0, 0, 0, 0,
     'GDPR Cleanup: Archived 0 inactive donors, 0 audit trail donor records, 0 device request details, and 33 device request client refs (backfilled from Azure PostgreSQLLogs, 2026-08-12)'),
    ('2026-07-11T04:04:02Z', 0, 0, 5, 26, 0, 0, 0, 0, 0, 0, 0,
     'GDPR Cleanup: Archived 0 inactive donors, 0 audit trail donor records, 5 device request details, and 26 device request client refs (backfilled from Azure PostgreSQLLogs, 2026-08-12)'),
    ('2026-07-18T04:04:03Z', 0, 0, 80, 34, 0, 0, 0, 0, 0, 0, 0,
     'GDPR Cleanup: Archived 0 inactive donors, 0 audit trail donor records, 80 device request details, and 34 device request client refs (backfilled from Azure PostgreSQLLogs, 2026-08-12)'),
    ('2026-07-25T04:04:03Z', 0, 0, 40, 23, 0, 0, 0, 0, 0, 0, 0,
     'GDPR Cleanup: Archived 0 inactive donors, 0 audit trail donor records, 40 device request details, and 23 device request client refs (backfilled from Azure PostgreSQLLogs, 2026-08-12)'),
    ('2026-08-01T04:04:00Z', 0, 0, 20, 21, 0, 0, 0, 0, 0, 0, 0,
     'GDPR Cleanup: Archived 0 inactive donors, 0 audit trail donor records, 20 device request details, and 21 device request client refs (backfilled from Azure PostgreSQLLogs, 2026-08-12)'),
    ('2026-08-08T04:04:01Z', 0, 0, 19, 26, 0, 0, 0, 0, 0, 0, 0,
     'GDPR Cleanup: Archived 0 inactive donors, 0 audit trail donor records, 19 device request details, and 26 device request client refs (backfilled from Azure PostgreSQLLogs, 2026-08-12)');

COMMIT;
