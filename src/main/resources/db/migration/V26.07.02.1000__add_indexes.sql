-- Secondary indexes for foreign-key columns and frequently filtered columns.
-- Without them every FK join and every @Formula count subquery (Donor.kitCount,
-- DeviceRequest.kitCount, ReferringOrganisation*.requestCount) runs a sequential scan.
--
-- Each index is guarded by a column-existence check: most of these tables predate
-- Flyway and have no CREATE TABLE migration (in fresh databases Hibernate creates
-- them after Flyway has run), so the guards let this migration no-op where a column
-- does not exist yet. IF NOT EXISTS makes it safe to re-run.
DO $$
DECLARE
    idx record;
BEGIN
    FOR idx IN
        SELECT * FROM (VALUES
            ('kits', 'donor_id', 'ix_kits_donor_id'),
            ('kits', 'device_request_id', 'ix_kits_device_request_id'),
            ('kits', 'status', 'ix_kits_status'),
            ('kits', 'type', 'ix_kits_type'),
            ('kits', 'serial_no', 'ix_kits_serial_no'),
            ('device_requests', 'status', 'ix_device_requests_status'),
            ('device_requests', 'referring_organisation_contact_id', 'ix_device_requests_referring_organisation_contact_id'),
            ('referring_organisation_contacts', 'referring_organisation_id', 'ix_referring_organisation_contacts_referring_organisation_id'),
            ('donors', 'donor_parent_id', 'ix_donors_donor_parent_id'),
            ('device_requests_notes', 'device_request_id', 'ix_device_requests_notes_device_request_id'),
            ('referring_organisations_notes', 'referring_organisation_id', 'ix_referring_organisations_notes_referring_organisation_id'),
            ('referring_organisation_contacts_notes', 'referring_organisation_contact_id', 'ix_roc_notes_referring_organisation_contact_id'),
            ('note', 'kit_id', 'ix_note_kit_id')
        ) AS t(table_name, column_name, index_name)
    LOOP
        IF EXISTS (
            SELECT 1 FROM information_schema.columns c
            WHERE c.table_schema = current_schema()
              AND c.table_name = idx.table_name
              AND c.column_name = idx.column_name
        ) THEN
            EXECUTE format('CREATE INDEX IF NOT EXISTS %I ON %I (%I)', idx.index_name, idx.table_name, idx.column_name);
        END IF;
    END LOOP;
END $$;
