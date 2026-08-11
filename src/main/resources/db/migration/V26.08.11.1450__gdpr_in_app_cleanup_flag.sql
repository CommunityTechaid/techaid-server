-- Flag row for the in-app GDPR retention cleanup (GdprDonorCleanup).
--
-- Seeded OFF and it must stay off until techaid_admin grants api_uat/api_prod EXECUTE on
-- gdpr.performgdprcleanup() and USAGE on the gdpr schema (SECURITY INVOKER; the app roles
-- currently have neither). See issue #62. While this is off, the pg_cron job
-- gdpr-weekly-cleanup (Sat 04:04 UTC) remains the only thing performing retention, and it
-- runs the OLD, narrower function body until techaid_admin also re-applies
-- V26.08.11.1400's function/view as an admin-applied statement in UAT/production. Do NOT
-- unschedule pg_cron until the in-app job has run successfully in production at least once.

insert into feature_flags (flag_key, enabled, updated_at)
values ('gdpr-in-app-cleanup', false, now())
on conflict (flag_key) do nothing;
