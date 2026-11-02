-- Synthetic data for local development and UAT. This folder is on the Flyway path for the `local`
-- profile alone (application-local.yml), so Flyway applies it only locally. UAT gets it by hand with
-- psql, after db/admin/2026-11-03__uat_reseed_cleanup.sql. Production never sees it.
--
-- Every record is invented: example.org addresses, Ofcom drama-range phone numbers (07700 900xxx),
-- "Test ..." names. Never replace this with a production or UAT dump - those contain personal data.
--
-- Repeatable migration: it re-runs whenever this file changes, so every insert is idempotent
-- (`on conflict ... do nothing`). IDs start at 900001 to stay clear of the sequences the application
-- allocates from. It is plain SQL (no Flyway placeholders) so it can also be run by hand with psql
-- against a database that already holds other rows; existing rows are never touched.
--
-- Coverage
--   36 rows each : referring_organisations, referring_organisation_contacts, device_requests,
--                  device_requests_notes, donor_parents, donors, kits, note
--   10 rows each : delivery_bookings, delivery_booking_overrides, delivery_blocked_dates,
--                  delivery_day_boroughs, posts, referring_organisations_notes,
--                  referring_organisation_contacts_notes, referrer_limit_exceptions,
--                  custom_rev_info and the Envers audit tables for the first 10 entities
--   Every KitStatus, KitType, DeviceRequestStatus, CollectionMethod, MembershipTier and
--   DonorParentType value appears at least once, and 'Y'/'N' archived flags are mixed.
--
-- NOT seeded (configuration or logs that a versioned migration or the app already owns):
--   feature_flags, delivery_config, borough_groups, borough_group_boroughs,
--   borough_availability, delivery_windows (V26.07.08.1000 seeds windows 1 and 2, which the
--   bookings below use), gdpr_cleanup_runs, flyway_schema_history, kit_updated_at_backup_20260818.
--
-- GDPR retention (see gdpr.performgdprcleanup): details blank 26 weeks after updated_at, client refs,
-- contact names and request notes after 52 weeks; donors and referees after 12 months of inactivity.
-- Most rows are created across the last ~2 years but kept recently touched (updated_at) so they stay
-- inside retention. Deliberately OUTSIDE retention, so the job has something to erase:
--   device_requests / device_requests_notes / note 900012, 900024, 900036 (updated_at > 1 year ago)
--   donors 900012, 900024, 900036 and their kits (nothing newer than 12 months)
--   referring_organisation_contacts 900012, 900024, 900036 (no activity in 12 months)
--
-- Hand-run caveat: an id from 900001 that already exists is skipped, so a clean database is expected.

-- Admin config singleton (id 1). No migration creates it and AdminConfigRepository.getAdminConfig()
-- cannot return null, so without it the public request form's adminConfig query fails locally.
-- Values mirror UAT on 2026-11-02. UAT and production already have their row, which this never touches.
insert into admin_config (id, can_public_request_laptop, can_public_request_phone, can_public_requestsimcard,
                          can_public_request_tablet, can_public_request_desktop, can_public_request_broadband_hub,
                          membership_over_limit_mode, membership_grace_requests, created_at, updated_at)
values (1, true, true, true, true, true, false, 'GRACE', 2, now(), now())
on conflict (id) do nothing;

-- Donor parents: BUSINESS on even ids, DROPPOINT on odd; every 8th archived.
insert into donor_parents (id, name, type, address, website, archived, created_at, updated_at)
select 900000 + n,
       'Test Donor Parent ' || lpad(n::text, 2, '0'),
       case when n % 2 = 0 then 'BUSINESS' else 'DROPPOINT' end,
       n || ' Test Street, London',
       'https://parent' || lpad(n::text, 2, '0') || '.example.org',
       case when n % 8 = 0 then 'Y' else 'N' end,
       now() - (15 + n * 19) * interval '1 day',
       greatest(now() - (15 + n * 19) * interval '1 day', now() - (n * 4) * interval '1 day')
from generate_series(1, 36) as n
on conflict (id) do nothing;

-- Donors: every 3rd has no parent, every 9th archived, every 12th is outside the 12-month retention.
insert into donors (id, name, email, phone_number, post_code, referral, consent, donor_parent_id,
                    is_lead_contact, archived, created_at, updated_at)
select 900000 + n,
       'Test Donor ' || lpad(n::text, 2, '0'),
       'donor' || lpad(n::text, 2, '0') || '@example.org',
       '07700 900' || lpad(n::text, 3, '0'),
       (array ['SW1A 1AA', 'E1 6AN', 'N1 9GU', 'SE1 7PB', 'SE5 8UH', 'E14 5AB', 'SW9 0AA', 'SE11 5DJ'])[(n - 1) % 8 + 1],
       (array ['Website', 'Friend', 'Social media', 'Poster', 'Workplace', 'Local news'])[(n - 1) % 6 + 1],
       n % 7 <> 0,
       case when n % 3 = 0 then null else 900000 + n end,
       n % 6 = 1,
       case when n % 9 = 0 then 'Y' else 'N' end,
       now() - (case when n % 12 = 0 then 400 + n * 10 else 10 + n * 9 end) * interval '1 day',
       case when n % 12 = 0
                then now() - (400 + n * 10) * interval '1 day'
            else greatest(now() - (10 + n * 9) * interval '1 day', now() - (n % 40) * interval '1 day') end
from generate_series(1, 36) as n
on conflict (id) do nothing;

-- Referring organisations: tiers cycle FREE, TIER_1, TIER_2, TIER_3. FREE uses the 1 February default
-- year and no end date; paid tiers carry start + end dates, every 3rd already lapsed. Every 9th archived.
insert into referring_organisations (id, name, phone_number, website, archived, membership_tier,
                                     membership_start_date, membership_end_date, created_at, updated_at)
select 900000 + n,
       'Test Organisation ' || lpad(n::text, 2, '0'),
       '07700 900' || lpad((100 + n)::text, 3, '0'),
       case when n % 5 = 0 then null else 'https://org' || lpad(n::text, 2, '0') || '.example.org' end,
       case when n % 9 = 0 then 'Y' else 'N' end,
       tier,
       start_date,
       case when tier = 'FREE' then null else (start_date + interval '1 year' - interval '1 day')::date end,
       now() - (60 + n * 18) * interval '1 day',
       greatest(now() - (60 + n * 18) * interval '1 day', now() - ((n * 5) % 200) * interval '1 day')
from (select n,
             (array ['FREE', 'TIER_1', 'TIER_2', 'TIER_3'])[(n - 1) % 4 + 1] as tier,
             case when (n - 1) % 4 = 0
                      then make_date(extract(year from (now() at time zone 'Europe/London'))::int
                                         - case when extract(month from (now() at time zone 'Europe/London')) < 2 then 1 else 0 end,
                                     2, 1)
                  when n % 3 = 0 then (current_date - 500)
                  else (current_date - ((n * 11) % 250)) end as start_date
      from generate_series(1, 36) as n) s
on conflict (id) do nothing;

-- Referring organisation contacts (referees): organisations 31-36 have none, 1-6 have two. Every 8th
-- archived, every 5th is a membership contact, every 12th is outside retention.
insert into referring_organisation_contacts (id, referring_organisation_id, full_name, email, phone_number, address,
                                             archived, is_membership_contact, created_at, updated_at)
select 900000 + n,
       900000 + (n - 1) % 30 + 1,
       'Test Referrer ' || lpad(n::text, 2, '0'),
       'referrer' || lpad(n::text, 2, '0') || '@example.org',
       '07700 900' || lpad((200 + n)::text, 3, '0'),
       n || ' Test Lane, London',
       case when n % 8 = 0 then 'Y' else 'N' end,
       case when n % 5 = 0 then 'Y' else 'N' end,
       now() - (case when n % 12 = 0 then 400 + n * 10 else 60 + n * 17 end) * interval '1 day',
       case when n % 12 = 0
                then now() - (400 + n * 10) * interval '1 day'
            else greatest(now() - (60 + n * 17) * interval '1 day', now() - ((n * 3) % 100) * interval '1 day') end
from generate_series(1, 36) as n
on conflict (id) do nothing;

-- Device requests: statuses cycle through all 8, collection methods through COLLECTION, DELIVERY,
-- UNKNOWN (DELIVERY requests are ids 900002, 900005, ...), boroughs through Lambeth, Southwark and Tower
-- Hamlets (the boroughs in borough_group_boroughs). correlation_id stays null: a set value marks a
-- Typeform submission still pending and the 20-minute decline job would act on it. Ids 900018 and
-- 900036 carry a membership grace index. Every 12th is outside retention.
insert into device_requests (id, referring_organisation_contact_id, status, laptops, tablets, phones, desktops, all_in_ones,
                             comms_devices, broadband_hubs, other, is_prepped, is_sales, borough, client_ref,
                             collection_method, collection_date, collection_contact_name, details,
                             has_internet, has_mobility_issues, need_quick_start, membership_grace_index,
                             created_at, updated_at)
select 900000 + n,
       900000 + (n - 1) % 27 + 1,
       (array ['NEW', 'PROCESSING_EQUALITIES_DATA_COMPLETE', 'PROCESSING_COLLECTION_DELIVERY_ARRANGED', 'PROCESSING_ON_HOLD',
               'REQUEST_COMPLETED', 'REQUEST_COLLECTION_DELIVERY_FAILED', 'REQUEST_DECLINED', 'REQUEST_CANCELLED'])[(n - 1) % 8 + 1],
       1 + n % 2,
       (n % 4 = 1)::int,
       (n % 5 = 2)::int,
       (n % 7 = 3)::int,
       (n % 11 = 4)::int,
       (n % 13 = 5)::int,
       (n % 6 = 0)::int,
       0,
       n % 4 = 0,
       n % 9 = 0,
       (array ['Lambeth', 'Southwark', 'Tower Hamlets'])[((n - 1) / 3) % 3 + 1],
       'TEST-' || lpad(n::text, 3, '0'),
       (array ['COLLECTION', 'DELIVERY', 'UNKNOWN'])[(n - 1) % 3 + 1],
       case when n % 2 = 0 then now() - (case when n % 12 = 0 then 390 + n * 10 else (n * 5) % 20 * 7 end) * interval '1 day' end,
       case when n % 2 = 0 then 'Test Collector ' || lpad(n::text, 2, '0') end,
       'Synthetic request ' || n,
       n % 3 <> 0,
       n % 5 = 0,
       n % 4 = 2,
       case when n = 18 then 1 when n = 36 then 2 end,
       now() - (case when n % 12 = 0 then 400 + n * 10 else 20 + n * 15 end) * interval '1 day',
       case when n % 12 = 0
                then now() - (400 + n * 10) * interval '1 day'
            else greatest(now() - (20 + n * 15) * interval '1 day', now() - (((n * 5) % 20 + 1) * 7) * interval '1 day') end
from generate_series(1, 36) as n
on conflict (id) do nothing;

-- Kits: type cycles through all 8 KitTypes and status through all 11 KitStatuses. Every 5th has no donor
-- (the rest belong to the donor with the same number, created just after it), every 7th archived, every
-- 3rd carries a synthetic `notes` attribute. Kits at an ALLOCATION_* status or DISTRIBUTION_DELIVERED are
-- assigned to the device request with the same number. Wipe-cert guard (#68): a drive-bearing kit (laptop,
-- desktop, all-in-one) that is past PROCESSING_START or assigned to a request carries a certificate
-- reference, or the NO_DRIVE exemption on every 4th. Kits of donors 12, 24 and 36 are outside retention.
insert into kits (id, type, status, model, make, age, location, location_code, lot_id, serial_no, donor_id, device_request_id,
                  attributes, archived, storage_capacity, type_of_storage, ram_capacity, cpu_type, cpu_cores, battery_health,
                  installation_ofosfailed, wipe_failed, needs_spare_part, needs_further_investigation, locked_to_user,
                  installedosname, wipe_cert_reference, wipe_cert_exemption, created_at, updated_at, status_updated_at)
select 900000 + n,
       type,
       status,
       (array ['ThinkPad T480', 'OptiPlex 3050', 'iPad 7th gen', 'Pixel 6', 'iMac 21.5', 'Test Router X1', 'Test MiFi 8000', 'Test Webcam W1'])[(n - 1) % 8 + 1],
       (array ['Lenovo', 'Dell', 'Apple', 'Google', 'Apple', 'Example Comms', 'Example Telecom', 'Example Peripherals'])[(n - 1) % 8 + 1],
       1 + n % 8,
       (array ['Test location', 'Test store room', 'Test workshop'])[n % 3 + 1],
       'A' || (n % 4 + 1) || '-' || lpad(n::text, 2, '0'),
       'LOT-' || lpad((n / 6 + 1)::text, 3, '0'),
       'TESTSN' || lpad(n::text, 6, '0'),
       case when n % 5 = 0 then null else 900000 + n end,
       case when assigned then 900000 + n end,
       case when n % 3 = 0 then jsonb_build_object('notes', 'Synthetic kit note ' || n) else '{}'::jsonb end,
       case when n % 7 = 0 then 'Y' else 'N' end,
       (array [128, 256, 500, 1000])[(n - 1) % 4 + 1],
       (array ['HDD', 'SSD', 'HYBRID', 'UNKNOWN'])[(n - 1) % 4 + 1],
       (array [4, 8, 16])[n % 3 + 1],
       'Test CPU',
       (array [2, 4, 8])[n % 3 + 1],
       60 + n % 40,
       false,
       n % 17 = 0,
       n % 13 = 0,
       n % 11 = 0,
       false,
       case when n % 2 = 0 then 'Ubuntu 22.04' end,
       case when needs_cert and n % 4 <> 0 then 'WIPE-CERT-' || lpad(n::text, 5, '0') end,
       case when needs_cert and n % 4 = 0 then 'NO_DRIVE' end,
       created_at,
       greatest(created_at, now() - ((n * 3) % 60) * interval '1 day'),
       greatest(created_at, now() - ((n * 3) % 60) * interval '1 day')
from (select n,
             type,
             status,
             status in ('ALLOCATION_READY', 'ALLOCATION_QC_COMPLETED', 'ALLOCATION_DELIVERY_ARRANGED', 'DISTRIBUTION_DELIVERED') as assigned,
             type in ('LAPTOP', 'DESKTOP', 'ALLINONE')
                 and (status not in ('DONATION_NEW', 'PROCESSING_START')
                      or status in ('ALLOCATION_READY', 'ALLOCATION_QC_COMPLETED', 'ALLOCATION_DELIVERY_ARRANGED', 'DISTRIBUTION_DELIVERED')) as needs_cert,
             case when n % 5 = 0
                      then now() - (5 + n * 20) * interval '1 day'
                  else now() - (case when n % 12 = 0 then 400 + n * 10 else 10 + n * 9 end - n % 7) * interval '1 day' end as created_at
      from (select n,
                   (array ['LAPTOP', 'DESKTOP', 'TABLET', 'SMARTPHONE', 'ALLINONE', 'COMMSDEVICE', 'BROADBANDHUB', 'OTHER'])[(n - 1) % 8 + 1] as type,
                   (array ['DONATION_NEW', 'PROCESSING_START', 'PROCESSING_WIPED', 'PROCESSING_OS_INSTALLED', 'PROCESSING_STORED',
                           'ALLOCATION_READY', 'ALLOCATION_QC_COMPLETED', 'ALLOCATION_DELIVERY_ARRANGED', 'DISTRIBUTION_DELIVERED',
                           'DISTRIBUTION_RECYCLED', 'DISTRIBUTION_REPAIR_RETURN'])[(n - 1) % 11 + 1] as status
            from generate_series(1, 36) as n) base) s
on conflict (id) do nothing;

-- Device request notes: requests 1-30 get one each (so 900012 and 900024 hold outside-retention notes).
insert into device_requests_notes (id, device_request_id, content, volunteer, created_at, updated_at)
select 900000 + n,
       d.id,
       'Synthetic request note ' || n,
       'Test Volunteer ' || lpad((n % 5 + 1)::text, 2, '0'),
       d.created_at + interval '1 hour',
       greatest(d.created_at + interval '1 hour', d.updated_at)
from generate_series(1, 36) as n
         join device_requests d on d.id = 900000 + (n - 1) % 30 + 1
on conflict (id) do nothing;

-- Kit notes (table `note`): kits 1-30 get one each.
insert into note (id, kit_id, content, volunteer, created_at, updated_at)
select 900000 + n,
       k.id,
       'Synthetic kit note ' || n,
       'Test Volunteer ' || lpad((n % 5 + 1)::text, 2, '0'),
       k.created_at + interval '1 hour',
       greatest(k.created_at + interval '1 hour', k.updated_at)
from generate_series(1, 36) as n
         join kits k on k.id = 900000 + (n - 1) % 30 + 1
on conflict (id) do nothing;

-- Organisation notes.
insert into referring_organisations_notes (id, referring_organisation_id, content, volunteer, created_at, updated_at)
select 900000 + n, 900000 + n, 'Synthetic organisation note ' || n, 'Test Volunteer ' || lpad((n % 5 + 1)::text, 2, '0'),
       now() - (n * 12) * interval '1 day', now() - (n * 12) * interval '1 day'
from generate_series(1, 10) as n
on conflict (id) do nothing;

-- Referee notes.
insert into referring_organisation_contacts_notes (id, referring_organisation_contact_id, content, volunteer, created_at, updated_at)
select 900000 + n, 900000 + n, 'Synthetic referee note ' || n, 'Test Volunteer ' || lpad((n % 5 + 1)::text, 2, '0'),
       now() - (n * 12) * interval '1 day', now() - (n * 12) * interval '1 day'
from generate_series(1, 10) as n
on conflict (id) do nothing;

-- Delivery bookings: windows 1 and 2 come from V26.07.08.1000. cta_reference is the id of a seeded DELIVERY
-- request (900002, 900005, ...). Dates fall on Tuesdays/Thursdays, from five weeks ago to five weeks ahead.
insert into delivery_bookings (id, delivery_date, window_id, first_name, surname, email, phone, address, access_notes,
                               cta_reference, created_at)
select 900000 + k,
       date_trunc('week', current_date)::date + 1 + (k - 5) * 7 + (k % 2) * 2,
       1 + k % 2,
       'Test',
       'Booker ' || lpad(k::text, 2, '0'),
       'booker' || lpad(k::text, 2, '0') || '@example.org',
       '07700 900' || lpad((300 + k)::text, 3, '0'),
       k || ' Test Street, London',
       case when k % 3 = 0 then 'Synthetic note: ring the bell for flat 4' end,
       900000 + 3 * k - 1,
       now() - (k * 9) * interval '1 day'
from generate_series(1, 10) as k
on conflict (id) do nothing;

-- Staff overrides of the one-booking-per-reference rule; every 2nd already consumed.
insert into delivery_booking_overrides (id, cta_reference, created_at, created_by, note, consumed_at)
select 900000 + k,
       900000 + 3 * k - 1,
       now() - (k * 7) * interval '1 day',
       'test.admin@example.org',
       'Synthetic override ' || k,
       case when k % 2 = 0 then now() - (k * 7 - 1) * interval '1 day' end
from generate_series(1, 10) as k
on conflict (id) do nothing;

-- Blocked delivery dates: seven past, three far in the future (beyond the booking horizon).
insert into delivery_blocked_dates (id, blocked_date, reason)
select 900000 + k,
       case when k <= 7 then current_date - k * 21 else current_date + (k - 7) * 60 end,
       (array ['Synthetic: bank holiday', 'Synthetic: van unavailable', 'Synthetic: volunteer shortage'])[k % 3 + 1]
from generate_series(1, 10) as k
on conflict (id) do nothing;

-- Borough delivery days (only enforced when delivery_config.borough_scheduling_enabled is switched on).
insert into delivery_day_boroughs (id, day_of_week, borough)
select 900000 + k,
       (k - 1) % 5 + 1,
       (array ['Lambeth', 'Southwark', 'Tower Hamlets'])[(k - 1) % 3 + 1]
from generate_series(1, 10) as k
on conflict do nothing;

-- Posts: every 3rd unpublished, every 4th secured.
insert into posts (id, slug, title, content, published, secured, created_at, updated_at)
select 900000 + k,
       'test-post-' || lpad(k::text, 2, '0'),
       'Test Post ' || lpad(k::text, 2, '0'),
       'Synthetic post body ' || k,
       k % 3 <> 0,
       k % 4 = 0,
       now() - (k * 30) * interval '1 day',
       now() - (k * 30) * interval '1 day'
from generate_series(1, 10) as k
on conflict do nothing;

-- Per-organisation overrides of a borough group's referee limit (groups 1 and 2 from V26.08.14.2100).
insert into referrer_limit_exceptions (id, referring_organisation_id, borough_group_id, max_per_referee, created_at, updated_at)
select 900000 + k,
       900000 + (k - 1) / 2 + 1,
       (k - 1) % 2 + 1,
       4 + k % 3,
       now() - (k * 10) * interval '1 day',
       now() - (k * 10) * interval '1 day'
from generate_series(1, 10) as k
on conflict do nothing;

-- Envers audit: ten synthetic revisions, then one audit row per revision describing the seeded entity with
-- the same number (rev = entity id). Odd ids are inserts (revtype 0), even ids modifications (revtype 1).
insert into custom_rev_info (id, timestamp, custom_user)
select 900000 + k,
       (extract(epoch from now() - (k * 10) * interval '1 day') * 1000)::bigint,
       'test.admin@example.org'
from generate_series(1, 10) as k
on conflict (id) do nothing;

insert into donor_parents_audit_trail (id, rev, revtype, archived, created_at, updated_at, address, name, type, website)
select id, id, (id % 2 = 0)::int::smallint, archived, created_at, updated_at, address, name, type, website
from donor_parents where id between 900001 and 900010
on conflict (id, rev) do nothing;

insert into donors_audit_trail (id, rev, revtype, archived, is_lead_contact, created_at, updated_at, donor_parent_id, email, name,
                                phone_number, post_code, referral)
select id, id, (id % 2 = 0)::int::smallint, archived, is_lead_contact, created_at, updated_at, donor_parent_id, email, name,
       phone_number, post_code, referral
from donors where id between 900001 and 900010
on conflict (id, rev) do nothing;

insert into referring_organisations_audit_trail (id, rev, revtype, archived, created_at, updated_at, name, phone_number, website,
                                                 membership_tier, membership_start_date, membership_end_date)
select id, id, (id % 2 = 0)::int::smallint, archived, created_at, updated_at, name, phone_number, website,
       membership_tier, membership_start_date, membership_end_date
from referring_organisations where id between 900001 and 900010
on conflict (id, rev) do nothing;

insert into referring_organisation_contacts_audit_trail (id, rev, revtype, archived, created_at, updated_at, referring_organisation_id,
                                                         address, email, full_name, phone_number, is_membership_contact)
select id, id, (id % 2 = 0)::int::smallint, archived, created_at, updated_at, referring_organisation_id,
       address, email, full_name, phone_number, is_membership_contact
from referring_organisation_contacts where id between 900001 and 900010
on conflict (id, rev) do nothing;

insert into device_requests_audit_trail (id, rev, revtype, all_in_ones, broadband_hubs, comms_devices, desktops, has_internet,
                                         has_mobility_issues, is_prepped, is_sales, laptops, need_quick_start, other, phones, tablets,
                                         collection_date, created_at, updated_at, referring_organisation_contact_id, borough,
                                         client_ref, collection_contact_name, collection_method, details, status, membership_grace_index)
select id, id, (id % 2 = 0)::int::smallint, all_in_ones, broadband_hubs, comms_devices, desktops, has_internet,
       has_mobility_issues, is_prepped, is_sales, laptops, need_quick_start, other, phones, tablets,
       collection_date, created_at, updated_at, referring_organisation_contact_id, borough,
       client_ref, collection_contact_name, collection_method, details, status, membership_grace_index
from device_requests where id between 900001 and 900010
on conflict (id, rev) do nothing;

insert into kit_audit_trail (id, rev, revtype, age, archived, battery_health, cpu_cores, installation_ofosfailed, locked_to_user,
                             needs_further_investigation, needs_spare_part, ram_capacity, storage_capacity, wipe_failed, created_at,
                             device_request_id, donor_id, status_updated_at, updated_at, cpu_type, device_version, installedosname,
                             location, location_code, lot_id, make, model, network, serial_no, status, tpm_version, type,
                             type_of_storage, wipe_cert_reference, wipe_cert_exemption)
select id, id, (id % 2 = 0)::int::smallint, age, archived, battery_health, cpu_cores, installation_ofosfailed, locked_to_user,
       needs_further_investigation, needs_spare_part, ram_capacity, storage_capacity, wipe_failed, created_at,
       device_request_id, donor_id, status_updated_at, updated_at, cpu_type, device_version, installedosname,
       location, location_code, lot_id, make, model, network, serial_no, status, tpm_version, type,
       type_of_storage, wipe_cert_reference, wipe_cert_exemption
from kits where id between 900001 and 900010
on conflict (id, rev) do nothing;

insert into note_aud (id, rev, revtype, created_at, updated_at, kit_id, content, volunteer)
select id, id, (id % 2 = 0)::int::smallint, created_at, updated_at, kit_id, content, volunteer
from note where id between 900001 and 900010
on conflict (id, rev) do nothing;
