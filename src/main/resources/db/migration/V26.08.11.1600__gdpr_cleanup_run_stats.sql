-- A structured, queryable record of each GDPR retention run: what ran, when, and how many
-- rows of each type were touched. Two purposes:
--   1. Compliance-relevant statistics ("how many of each entity type have we cleared over
--      time") without parsing log text, which has limited retention (App Insights traces
--      are ~90 days) and isn't structured.
--   2. The "last successful run" marker the in-app scheduled job (GdprDonorCleanup) checks
--      on startup, so it can catch up on a scale-to-zero environment (UAT: plain on-demand
--      HTTP scaling, no guaranteed warm window) that missed its Monday cron slot entirely.
--
-- Deliberately a PLAIN public-schema table, not under gdpr/techaid_admin ownership, and NOT
-- self-gated like the gdpr-schema migrations: it must exist in every environment immediately
-- (UAT/production included, on ordinary Flyway) so the in-app job's startup catch-up check
-- can query it even before the gdpr-schema grant work (issue #62) lands. It holds no PII,
-- only counts and a summary string, so no elevated ownership is warranted.
--
-- gdpr.performgdprcleanup() (V26.08.11.1650) writes to this table directly, so a run is
-- recorded whether it was triggered by pg_cron (still the only thing running retention in
-- production today) or the in-app job once enabled - the two never need to agree on how to
-- record a run.

create table if not exists gdpr_cleanup_runs (
    id bigserial primary key,
    ran_at timestamp(6) with time zone not null default now(),
    donor_count integer not null,
    donor_audit_count integer not null,
    device_request_details_count integer not null,
    device_request_clientref_count integer not null,
    device_request_contact_name_count integer not null,
    device_request_details_audit_count integer not null,
    device_request_clientref_audit_count integer not null,
    device_request_contact_name_audit_count integer not null,
    device_request_notes_count integer not null,
    referring_contact_count integer not null,
    referring_contact_audit_count integer not null,
    summary text not null
);

create index if not exists ix_gdpr_cleanup_runs_ran_at on gdpr_cleanup_runs (ran_at);

-- techaid_admin runs gdpr.performgdprcleanup() today via pg_cron in production (and will
-- keep doing so until the in-app job is enabled), so it needs to be able to write its own
-- run record here. This role does not exist in fresh/test databases, hence the guard - the
-- table owner (the migrating role, api_uat/api_prod in UAT/production) already has full
-- rights on a table it just created, so this grant is additive, not a prerequisite.
DO $mig$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'techaid_admin') THEN
        GRANT INSERT, SELECT ON gdpr_cleanup_runs TO techaid_admin;
        GRANT USAGE, SELECT ON SEQUENCE gdpr_cleanup_runs_id_seq TO techaid_admin;
    END IF;
END $mig$;
