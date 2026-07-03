-- Baseline for the schema Flyway never managed.
--
-- Most of these tables predate Flyway (or were added while ddl-auto=update was
-- creating schema on the fly) and had no CREATE TABLE migration, so fresh databases
-- only contained the Flyway-managed subset until Hibernate filled in the rest at boot.
-- This migration makes Flyway the single owner of the schema so ddl-auto can be
-- validate everywhere.
--
-- Table/sequence DDL below is copied verbatim from Hibernate 6's schema export
-- (jakarta.persistence.schema-generation create script) with IF NOT EXISTS added,
-- so it matches exactly what ddl-auto=update produced on the live databases. Every
-- statement is guarded: on databases that already have these objects (UAT,
-- production) this whole migration is a no-op.

-- ---------------------------------------------------------------------------
-- Sequences
-- ---------------------------------------------------------------------------

create sequence if not exists custom_rev_info_seq start with 1 increment by 50;
create sequence if not exists device_requests_note_sequence start with 1 increment by 1;
create sequence if not exists device_requests_sequence start with 1 increment by 1;
create sequence if not exists donor_parent_sequence start with 1 increment by 1;
create sequence if not exists note_sequence start with 1 increment by 1;
create sequence if not exists referring_organisation_contacts_note_sequence start with 1 increment by 1;
create sequence if not exists referring_organisation_contacts_sequence start with 1 increment by 1;
create sequence if not exists referring_organisation_sequence start with 1 increment by 1;
create sequence if not exists referring_organisations_note_sequence start with 1 increment by 1;

-- ---------------------------------------------------------------------------
-- Tables (one line per table, verbatim from the Hibernate schema export)
-- ---------------------------------------------------------------------------
create table if not exists admin_config (can_public_request_broadband_hub boolean not null, can_public_request_desktop boolean not null, can_public_request_laptop boolean not null, can_public_request_phone boolean not null, can_public_request_tablet boolean not null, can_public_requestsimcard boolean not null, created_at timestamp(6) with time zone, id bigint not null, updated_at timestamp(6) with time zone, primary key (id));
create table if not exists custom_rev_info (id bigint not null, timestamp bigint, custom_user varchar(255), primary key (id));
create table if not exists device_requests (all_in_ones integer, broadband_hubs integer, comms_devices integer, desktops integer, has_internet boolean, has_mobility_issues boolean, is_prepped boolean not null, is_sales boolean not null, laptops integer, need_quick_start boolean, other integer, phones integer, tablets integer, collection_date timestamp(6) with time zone, correlation_id bigint, created_at timestamp(6) with time zone, id bigint not null, referring_organisation_contact_id bigint, updated_at timestamp(6) with time zone, borough varchar(255), client_ref varchar(255), collection_contact_name varchar(255), collection_method varchar(255) check (collection_method in ('COLLECTION','DELIVERY','UNKNOWN')), details TEXT, status varchar(255) check (status in ('NEW','PROCESSING_EQUALITIES_DATA_COMPLETE','PROCESSING_COLLECTION_DELIVERY_ARRANGED','PROCESSING_ON_HOLD','REQUEST_COMPLETED','REQUEST_COLLECTION_DELIVERY_FAILED','REQUEST_DECLINED','REQUEST_CANCELLED')), primary key (id));
create table if not exists device_requests_audit_trail (all_in_ones integer, broadband_hubs integer, comms_devices integer, desktops integer, has_internet boolean, has_mobility_issues boolean, is_prepped boolean, is_sales boolean, laptops integer, need_quick_start boolean, other integer, phones integer, revtype smallint, tablets integer, collection_date timestamp(6) with time zone, created_at timestamp(6) with time zone, id bigint not null, referring_organisation_contact_id bigint, rev bigint not null, updated_at timestamp(6) with time zone, borough varchar(255), client_ref varchar(255), collection_contact_name varchar(255), collection_method varchar(255) check (collection_method in ('COLLECTION','DELIVERY','UNKNOWN')), details TEXT, status varchar(255) check (status in ('NEW','PROCESSING_EQUALITIES_DATA_COMPLETE','PROCESSING_COLLECTION_DELIVERY_ARRANGED','PROCESSING_ON_HOLD','REQUEST_COMPLETED','REQUEST_COLLECTION_DELIVERY_FAILED','REQUEST_DECLINED','REQUEST_CANCELLED')), primary key (id, rev));
create table if not exists device_requests_notes (created_at timestamp(6) with time zone, device_request_id bigint, id bigint not null, updated_at timestamp(6) with time zone, content varchar(4096), volunteer varchar(255), primary key (id));
create table if not exists donor_parents (archived char(1) not null check (archived in ('N','Y')), created_at timestamp(6) with time zone, id bigint not null, updated_at timestamp(6) with time zone, address varchar(255), name varchar(255), type varchar(255) check (type in ('BUSINESS','DROPPOINT')), website varchar(255), primary key (id));
create table if not exists donor_parents_audit_trail (archived char(1) check (archived in ('N','Y')), revtype smallint, created_at timestamp(6) with time zone, id bigint not null, rev bigint not null, updated_at timestamp(6) with time zone, address varchar(255), name varchar(255), type varchar(255) check (type in ('BUSINESS','DROPPOINT')), website varchar(255), primary key (id, rev));
create table if not exists donors_audit_trail (archived char(1) check (archived in ('N','Y')), is_lead_contact boolean, revtype smallint, created_at timestamp(6) with time zone, donor_parent_id bigint, id bigint not null, rev bigint not null, updated_at timestamp(6) with time zone, email varchar(255), name varchar(255), phone_number varchar(255), post_code varchar(255), referral varchar(255), primary key (id, rev));
create table if not exists kit_audit_trail (age integer, archived char(1) check (archived in ('N','Y')), battery_health integer, cpu_cores integer, installation_ofosfailed boolean, locked_to_user boolean, needs_further_investigation boolean, needs_spare_part boolean, ram_capacity integer, revtype smallint, storage_capacity integer, wipe_failed boolean, created_at timestamp(6) with time zone, device_request_id bigint, donor_id bigint, id bigint not null, rev bigint not null, status_updated_at timestamp(6) with time zone, updated_at timestamp(6) with time zone, cpu_type varchar(255), device_version varchar(255), installedosname varchar(255), location varchar(255), location_code varchar(255), lot_id varchar(255), make varchar(255), model varchar(255), network varchar(255), serial_no varchar(255), status varchar(255) check (status in ('DONATION_NEW','PROCESSING_START','PROCESSING_WIPED','PROCESSING_OS_INSTALLED','PROCESSING_STORED','ALLOCATION_ASSESSMENT','ALLOCATION_READY','ALLOCATION_QC_COMPLETED','ALLOCATION_DELIVERY_ARRANGED','DISTRIBUTION_DELIVERED','DISTRIBUTION_RECYCLED','DISTRIBUTION_REPAIR_RETURN')), tpm_version varchar(255), type varchar(255) check (type in ('OTHER','LAPTOP','DESKTOP','TABLET','SMARTPHONE','ALLINONE','COMMSDEVICE','BROADBANDHUB')), type_of_storage varchar(255) check (type_of_storage in ('HDD','SSD','HYBRID','UNKNOWN')), primary key (id, rev));
create table if not exists note (created_at timestamp(6) with time zone, id bigint not null, kit_id bigint, updated_at timestamp(6) with time zone, content varchar(4096), volunteer varchar(255), primary key (id));
create table if not exists note_aud (revtype smallint, created_at timestamp(6) with time zone, id bigint not null, kit_id bigint, rev bigint not null, updated_at timestamp(6) with time zone, content varchar(4096), volunteer varchar(255), primary key (id, rev));
create table if not exists referring_organisation_contacts (archived char(1) not null check (archived in ('N','Y')), created_at timestamp(6) with time zone, id bigint not null, referring_organisation_id bigint, updated_at timestamp(6) with time zone, address varchar(255), email varchar(255), full_name varchar(255), phone_number varchar(255), primary key (id));
create table if not exists referring_organisation_contacts_audit_trail (archived char(1) check (archived in ('N','Y')), revtype smallint, created_at timestamp(6) with time zone, id bigint not null, referring_organisation_id bigint, rev bigint not null, updated_at timestamp(6) with time zone, address varchar(255), email varchar(255), full_name varchar(255), phone_number varchar(255), primary key (id, rev));
create table if not exists referring_organisation_contacts_notes (created_at timestamp(6) with time zone, id bigint not null, referring_organisation_contact_id bigint, updated_at timestamp(6) with time zone, content varchar(4096), volunteer varchar(255), primary key (id));
create table if not exists referring_organisations (archived char(1) not null check (archived in ('N','Y')), created_at timestamp(6) with time zone, id bigint not null, updated_at timestamp(6) with time zone, name varchar(255), phone_number varchar(255), website varchar(255), primary key (id));
create table if not exists referring_organisations_audit_trail (archived char(1) check (archived in ('N','Y')), revtype smallint, created_at timestamp(6) with time zone, id bigint not null, rev bigint not null, updated_at timestamp(6) with time zone, name varchar(255), phone_number varchar(255), website varchar(255), primary key (id, rev));
create table if not exists referring_organisations_notes (created_at timestamp(6) with time zone, id bigint not null, referring_organisation_id bigint, updated_at timestamp(6) with time zone, content varchar(4096), volunteer varchar(255), primary key (id));

-- ---------------------------------------------------------------------------
-- Columns ddl-auto=update added to Flyway-managed tables over the years
-- (types verbatim from the Hibernate schema export; defaults added so the
-- NOT NULL columns can also be applied to a database that already has rows)
-- ---------------------------------------------------------------------------
alter table donors add column if not exists archived char(1) not null default 'N';
alter table donors add column if not exists donor_parent_id bigint;
alter table donors add column if not exists is_lead_contact boolean not null default false;

alter table kits add column if not exists battery_health integer;
alter table kits add column if not exists cpu_cores integer;
alter table kits add column if not exists cpu_type varchar(255);
alter table kits add column if not exists device_request_id bigint;
alter table kits add column if not exists device_version varchar(255);
alter table kits add column if not exists installation_ofosfailed boolean;
alter table kits add column if not exists installedosname varchar(255);
alter table kits add column if not exists locked_to_user boolean;
alter table kits add column if not exists make varchar(255);
alter table kits add column if not exists needs_further_investigation boolean;
alter table kits add column if not exists needs_spare_part boolean;
alter table kits add column if not exists network varchar(255);
alter table kits add column if not exists ram_capacity integer;
alter table kits add column if not exists serial_no varchar(255);
alter table kits add column if not exists storage_capacity integer;
alter table kits add column if not exists tpm_version varchar(255);
alter table kits add column if not exists type_of_storage varchar(255);
alter table kits add column if not exists wipe_failed boolean;

-- kits.archived: V20.08.02.1740 made it varchar(1), but the entity maps it via
-- YesNoConverter which Hibernate 6 expects as char(1) - ddl-auto=update has already
-- applied this exact alter on databases it managed; validate rejects varchar here.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'kits' AND column_name = 'archived'
          AND data_type = 'character varying'
    ) THEN
        ALTER TABLE kits ALTER COLUMN archived TYPE char(1);
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- Foreign keys (names as generated by Hibernate; Postgres has no
-- ADD CONSTRAINT IF NOT EXISTS, hence the guard)
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    fk record;
BEGIN
    FOR fk IN
        SELECT * FROM (VALUES
            ('device_requests', 'fk1g8b0qm5yfudplki3rhkujv9m', 'referring_organisation_contact_id', 'referring_organisation_contacts'),
            ('device_requests_audit_trail', 'fkgjwowxi7w1r9o6etvux4f3nhl', 'rev', 'custom_rev_info'),
            ('device_requests_notes', 'fk7re5smeigyymtm58dt010p7rn', 'device_request_id', 'device_requests'),
            ('donor_parents_audit_trail', 'fkteer6l4cq3el95e8vq8vtb2r0', 'rev', 'custom_rev_info'),
            ('donors', 'fkgvxtxr9h7x9o2isbh78o071wi', 'donor_parent_id', 'donor_parents'),
            ('donors_audit_trail', 'fk24wptiem3itqlckv0n3d7bn6b', 'rev', 'custom_rev_info'),
            ('kit_audit_trail', 'fkdlqf279dhfok6ibco3twt0vsc', 'rev', 'custom_rev_info'),
            ('kits', 'fk39v608jydx7a604cue19hjjbg', 'device_request_id', 'device_requests'),
            ('kits', 'fktx5w8x58hp99pssrocdy5d4o', 'donor_id', 'donors'),
            ('note', 'fkr4b0x928gqqb46qhobsol1ikf', 'kit_id', 'kits'),
            ('note_aud', 'fkb4jxthp3p7yjpbygk5n3fwr86', 'rev', 'custom_rev_info'),
            ('referring_organisation_contacts', 'fknua6gkg239133mjn8bhesfjtl', 'referring_organisation_id', 'referring_organisations'),
            ('referring_organisation_contacts_audit_trail', 'fklbgcdwfbqhlduxfs0f8n3o3ka', 'rev', 'custom_rev_info'),
            ('referring_organisation_contacts_notes', 'fkax250lgf96gcu45by4uwqm9j5', 'referring_organisation_contact_id', 'referring_organisation_contacts'),
            ('referring_organisations_audit_trail', 'fkjsumkn59umbimi69cu0j4e17l', 'rev', 'custom_rev_info'),
            ('referring_organisations_notes', 'fkcqeiamkjg6dm1ecgq9h1d9x0d', 'referring_organisation_id', 'referring_organisations')
        ) AS t(table_name, constraint_name, column_name, ref_table)
    LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = fk.constraint_name) THEN
            EXECUTE format(
                'ALTER TABLE %I ADD CONSTRAINT %I FOREIGN KEY (%I) REFERENCES %I',
                fk.table_name, fk.constraint_name, fk.column_name, fk.ref_table
            );
        END IF;
    END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- Re-run of V26.07.02.1000 (secondary indexes): on a fresh database that
-- migration runs before these tables exist and its guards no-op, so repeat it
-- here now that the tables are in place. Identical, idempotent statement.
-- ---------------------------------------------------------------------------
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
