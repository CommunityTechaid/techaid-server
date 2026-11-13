-- #374: gdpr.performgdprcleanup() now deletes the notes of erased referee contacts, and records
-- how many in each run, alongside the counts it already keeps.
--
-- A plain public-schema table owned by the application role (see V26.08.11.1600), so this is
-- ordinary Flyway and applies in every environment on deploy. `not null default 0`, so rows
-- written by runs before #374 read as zero and the function's old INSERT stays valid until the
-- admin script replaces it.
--
-- ORDER IN UAT AND PRODUCTION: this migration must have run (i.e. the app deployed) BEFORE
-- db/admin/2026-11-13__admin_apply_gdpr_delete_erased_referee_notes.sql replaces the function,
-- because the new body inserts into this column. The admin script checks and aborts otherwise.
-- techaid_admin's existing table-level INSERT grant covers the new column.

alter table gdpr_cleanup_runs add column if not exists referring_contact_notes_count integer not null default 0;
