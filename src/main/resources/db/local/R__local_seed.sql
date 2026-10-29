-- Synthetic data for local development only. This folder is on the Flyway path for the `local`
-- profile alone (application-local.yml); UAT and production never see it.
--
-- Every record is invented: example.org/.invalid addresses, Ofcom drama-range phone numbers.
-- Never replace this with a production or UAT dump - those contain personal data.
--
-- Repeatable migration: it re-runs whenever this file changes, so every insert is idempotent.
-- IDs start at 900001 to stay clear of the sequences the application allocates from.

insert into donor_parents (id, name, type, address, website, created_at, updated_at)
values
    (900001, 'Example Business Ltd', 'BUSINESS', '1 Test Street, London', 'https://example.org', now(), now()),
    (900002, 'Example Library Drop Point', 'DROPPOINT', '2 Test Road, London', null, now(), now())
on conflict (id) do nothing;

insert into donors (id, name, email, phone_number, post_code, referral, consent, donor_parent_id, is_lead_contact, created_at, updated_at)
values
    (900001, 'Test Donor One', 'donor1@example.org', '07700 900001', 'SW1A 1AA', 'Website', true, 900001, true, now(), now()),
    (900002, 'Test Donor Two', 'donor2@example.org', '07700 900002', 'E1 6AN', 'Friend', true, 900002, false, now(), now()),
    (900003, 'Test Donor Three', 'donor3@example.org', '07700 900003', 'N1 9GU', 'Website', true, null, false, now(), now())
on conflict (id) do nothing;

insert into referring_organisations (id, name, phone_number, website, created_at, updated_at)
values
    (900001, 'Example Community Centre', '07700 900101', 'https://example.org', now(), now()),
    (900002, 'Example Housing Association', '07700 900102', null, now(), now())
on conflict (id) do nothing;

insert into referring_organisation_contacts (id, referring_organisation_id, full_name, email, phone_number, address, created_at, updated_at)
values
    (900001, 900001, 'Test Referrer One', 'referrer1@example.org', '07700 900201', '10 Test Lane, London', now(), now()),
    (900002, 900002, 'Test Referrer Two', 'referrer2@example.org', '07700 900202', '20 Test Lane, London', now(), now())
on conflict (id) do nothing;

insert into device_requests (id, referring_organisation_contact_id, status, laptops, tablets, phones, desktops, all_in_ones,
                             comms_devices, broadband_hubs, other, is_prepped, is_sales, borough, client_ref,
                             collection_method, details, created_at, updated_at)
values
    (900001, 900001, 'NEW', 1, 0, 0, 0, 0, 0, 0, 0, false, false, 'Lambeth', 'TEST-001', 'DELIVERY', 'Synthetic request', now(), now()),
    (900002, 900002, 'PROCESSING_ON_HOLD', 0, 1, 1, 0, 0, 0, 0, 0, false, false, 'Southwark', 'TEST-002', 'COLLECTION', 'Synthetic request', now(), now())
on conflict (id) do nothing;

insert into kits (id, type, status, model, make, age, location, donor_id, attributes, created_at, updated_at)
values
    (900001, 'LAPTOP', 'DONATION_NEW', 'ThinkPad T480', 'Lenovo', 5, 'Test location', 900001, '{}'::jsonb, now(), now()),
    (900002, 'LAPTOP', 'PROCESSING_WIPED', 'Latitude 7490', 'Dell', 6, 'Test location', 900001, '{}'::jsonb, now(), now()),
    (900003, 'TABLET', 'ALLOCATION_READY', 'iPad 7th gen', 'Apple', 4, 'Test location', 900002, '{}'::jsonb, now(), now()),
    (900004, 'SMARTPHONE', 'DONATION_NEW', 'Pixel 6', 'Google', 3, 'Test location', 900003, '{}'::jsonb, now(), now())
on conflict (id) do nothing;
