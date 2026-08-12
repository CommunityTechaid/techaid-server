-- Adds a counter column for the kits.coordinates scrub introduced in V26.08.12.1100.
--
-- Plain public-schema migration, not self-gated, for the same reason as V26.08.11.1600:
-- gdpr_cleanup_runs is owned by the app role and must exist identically in every
-- environment. It holds counts and a summary string only, no PII.
--
-- `default 0` is required, not cosmetic: the column is NOT NULL and UAT already has run
-- rows from before the kits scrub existed. Those historical runs genuinely cleared zero
-- kit coordinates, so 0 is the honest backfill value rather than a placeholder.

alter table gdpr_cleanup_runs
    add column if not exists kit_coordinates_count integer not null default 0;
