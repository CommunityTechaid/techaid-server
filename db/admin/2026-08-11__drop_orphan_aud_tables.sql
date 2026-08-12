-- Issue #92: drop the three orphaned Envers `_aud` tables left behind by the October 2024
-- auditing-configuration change.
--
-- APPLIED TO
--   techaid_prod  2026-08-11  (the only database that had them)
--   techaid_uat   n/a         - none of the three exist there, verified 2026-08-11
--
-- WHY THIS IS NOT A FLYWAY MIGRATION
--   No migration in this repo creates these tables. They were produced by ddl-auto=update
--   against the live database before Flyway owned the schema, and the baseline migration
--   V26.07.03.0900 deliberately does not include them (it creates note_aud, which IS still
--   live, but not these three). A fresh or test database has therefore never had them, so a
--   Flyway DROP would be a permanent no-op everywhere except production. Dropping them here
--   makes production match the repo rather than diverge from it.
--
-- WHAT THESE TABLES WERE
--   Envers history tables written by the previous auditing configuration, which used the
--   default `_AUD` suffix. On 2024-10-31 the audited entities were changed to carry an
--   explicit @AuditTable(...) naming a `_audit_trail` table instead. The changeover is exact:
--   device_requests_notes_aud stops on 2024-10-31 and device_requests_audit_trail starts the
--   same day. The old tables were simply left in place.
--
-- EVIDENCE GATHERED BEFORE DROPPING (all measured 2026-08-11 against techaid_prod)
--   * device_requests_notes_aud: 572 rows (553 with text, 19 revtype=2 deletes), covering 490
--     device requests, all written between 2024-07-24 and 2024-10-31. Nothing since.
--   * 546 of the 553 text rows (98.7%) are byte-identical to text still present in the live
--     device_requests_notes table. 7 rows hold text found nowhere else, all short operational
--     jottings ("Declined", "05/09 delivery", "No response for collection booking sent on 18/09").
--   * Zero matches for email, UK phone or UK postcode patterns across all 553 rows.
--   * referring_organisation_contacts_notes_aud and referring_organisations_notes_aud: 0 rows.
--     Created by the same configuration and never written to.
--   * No entity maps to any of them. DeviceRequestNote (DeviceRequestModels.kt:178) is a plain
--     @Entity with no @Audited annotation, so Envers neither reads nor writes these tables.
--     Every audited entity in the codebase carries an explicit @AuditTable naming a
--     `_audit_trail` table.
--   * No foreign key, view or constraint anywhere in the database references them. Their own
--     outbound FKs point at custom_rev_info and are dropped with the table.
--   * ddl-auto is `none` in production and `validate` elsewhere, so nothing recreates them.
--
-- BACKUP TAKEN BEFORE RUNNING
--   C:\Users\tonya\Desktop\gdpr-orphan-table-backup-2026-08-11\
--     device_requests_notes_aud.csv   572 rows, all 8 columns
--                                     sha256 5ab7221698aeb3e98b5b65ee458e845bac36d465fb767daa4b68e0767d6cc076
--     referring_organisation_contacts_notes_aud.csv  (header only, table empty)
--     referring_organisations_notes_aud.csv          (header only, table empty)
--     schema_ddl.sql                  CREATE TABLE + constraints for all three
--     orphan_aud_tables.dump          pg_dump -Fc, schema + data, second restore path
--   Production also has point-in-time restore covering this date.
--
-- OWNERSHIP
--   All three are owned by api_prod, not techaid_admin, so this runs as the ordinary
--   application role. No admin credential is needed and none of the gdpr-schema ownership
--   traps apply.

DO $$
BEGIN
    IF current_database() <> 'techaid_prod' THEN
        RAISE EXCEPTION 'ABORT: expected techaid_prod, got %', current_database();
    END IF;
END $$;

BEGIN;

-- Fail loudly rather than silently dropping something that has started being used again.
DO $$
DECLARE
    n bigint;
BEGIN
    SELECT count(*) INTO n FROM public.device_requests_notes_aud;
    IF n <> 572 THEN
        RAISE EXCEPTION 'ABORT: device_requests_notes_aud has % rows, expected 572 - re-measure before dropping', n;
    END IF;

    SELECT count(*) INTO n FROM public.referring_organisation_contacts_notes_aud;
    IF n <> 0 THEN
        RAISE EXCEPTION 'ABORT: referring_organisation_contacts_notes_aud is no longer empty (% rows)', n;
    END IF;

    SELECT count(*) INTO n FROM public.referring_organisations_notes_aud;
    IF n <> 0 THEN
        RAISE EXCEPTION 'ABORT: referring_organisations_notes_aud is no longer empty (% rows)', n;
    END IF;

    SELECT count(*) INTO n
      FROM pg_constraint con
      JOIN pg_class tgt ON tgt.oid = con.confrelid
     WHERE tgt.relname IN ('device_requests_notes_aud',
                           'referring_organisation_contacts_notes_aud',
                           'referring_organisations_notes_aud');
    IF n <> 0 THEN
        RAISE EXCEPTION 'ABORT: % foreign keys now reference these tables', n;
    END IF;
END $$;

-- RESTRICT, not CASCADE: if anything has come to depend on these since the checks above were
-- written, the drop must fail rather than quietly take the dependent object with it.
DROP TABLE public.device_requests_notes_aud RESTRICT;
DROP TABLE public.referring_organisation_contacts_notes_aud RESTRICT;
DROP TABLE public.referring_organisations_notes_aud RESTRICT;

COMMIT;
