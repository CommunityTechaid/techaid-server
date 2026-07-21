-- Flag row for the in-app GDPR retention cleanup (GdprDonorCleanup).
--
-- Seeded OFF and it must stay off for now. Two prerequisites are outstanding:
--   1. gdpr.performgdprcleanup() exists only in techaid_uat and techaid_prod - it is in no
--      migration in this repo, so it is absent from every fresh and test database.
--   2. It is SECURITY INVOKER and owned by techaid_admin; api_uat / api_prod have neither
--      EXECUTE on it nor USAGE on the gdpr schema.
--
-- While this is off, the pg_cron job gdpr-weekly-cleanup (Sat 04:04 UTC) remains the only
-- thing performing retention. That is the correct state: do NOT unschedule pg_cron until the
-- in-app job has run successfully in production at least once.

insert into feature_flags (flag_key, enabled, updated_at)
values ('gdpr-in-app-cleanup', false, now())
on conflict (flag_key) do nothing;
