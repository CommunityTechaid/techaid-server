-- kits.archived is character(1) holding 'Y'/'N' (YesNoConverter on Kit.archived), but its
-- column default is the boolean `false`, in both UAT and production. Postgres renders that as
-- the text 'false', which does not fit character(1), so any INSERT that omits `archived` fails
-- with "value too long for type character(1)". The application always sets the column, so only
-- raw SQL (seeds, ops scripts) hits it. Noted and left out of scope in
-- V26.07.22.1300__converge_uat_prod_schema_drift.sql; this gives kits the same 'N' default
-- every other `archived` column has.
--
-- Default only: no existing row is read or changed. Idempotent, so safe to re-run.

alter table kits alter column archived set default 'N';
