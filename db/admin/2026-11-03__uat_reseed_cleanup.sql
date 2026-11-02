-- UAT reseed, step 1 of 2: remove the production-derived data from techaid_uat.
--
-- WHAT IT DOES
--   UAT was loaded from a production backup in 2025. This keeps the six Community TechAid
--   organisations (119, 182, 183, 203, 227, 324) with their referees, device requests and the
--   devices on those requests, and deletes everything else in the person-bearing tables:
--     - every other referring organisation, referee, request, request note, referee note, org note,
--       referrer limit exception
--     - every device not on a kept request (incl. the ~4,700 never assigned), and their notes
--     - every donor / donor parent not attached to a kept device
--     - every delivery booking (names, addresses, phones; no FK to requests, so nothing else
--       would remove them)
--     - every Envers audit row (*_audit_trail, note_aud) and custom_rev_info: they hold copies of
--       deleted records and have no FK to the live rows
--     - the leftover kit_updated_at_backup_20260818 table (created by an admin script, not Flyway)
--   and scrubs personal data on the rows it keeps:
--     - requests: client_ref, details, collection_contact_name
--     - request notes, device notes, referee notes: content
--     - kit attributes: notes, credentials, pickupAvailability
--     - donors / donor parents still attached to kept devices: replaced with synthetic values
--   Kept as-is: the 4 CTA staff referees (Stephanie Charbine, Cat Smith, Steve Woolnough,
--   Tony Anzelmo - agreed 2026-11-02) and the 21 referees already erased by GDPR retention; the
--   `volunteer` author field on notes (CTA staff/volunteers). Config tables are not touched.
--
--   Step 2 is the synthetic seed: src/main/resources/db/local/R__local_seed.sql, run with psql.
--
-- LEFTOVER CHECK (the proof for the GDPR claim)
--   Before deleting, it collects every real email, phone, name, postcode and address (donors,
--   donor parents, non-CTA referees, delivery bookings, collection contacts, client refs) into a
--   temp table. After the deletes and scrubs it searches every text/varchar/jsonb column in
--   public for any of them. A single hit raises, and the whole transaction rolls back.
--   The temp table never leaves the transaction.
--
-- gdpr SCHEMA
--   Deleting donors fires gdpr_donors_trigger, which writes donor_id, dates and `referral` into
--   gdpr.donors_archive. Those columns carry no personal data, so nothing is needed there.
--
-- WHY NOT FLYWAY
--   One-off data surgery on one environment. Must never run against production.
--
-- HOW TO RUN (as api_uat; the app role owns every public table)
--   Dry run (default - everything rolls back):   psql ... -v ON_ERROR_STOP=1 -X -f this.sql
--   Real run:                                    psql ... -v ON_ERROR_STOP=1 -X -v commit=1 -f this.sql
--   Take `pg_dump -Fc -n public` of techaid_uat first.
--
-- APPLIED TO
--   techaid_uat - not yet. Dry runs on 2026-11-02 (rolled back):
--     kept 6 orgs / 25 referees / 69 requests / 48 devices; after: 10 donors, 9 donor parents,
--     53 device notes, 30 request notes, all audit tables 0, delivery_bookings 0.
--     Leftover check PASSED (766 real values searched). Negative control - the same script with
--     the client_ref scrub removed - FAILED as it should: device_requests.client_ref=5.
--     The 10 donors on kept devices were already blanked by GDPR retention.

\set ON_ERROR_STOP 1
begin;

do $$ begin
  if current_database() <> 'techaid_uat' then
    raise exception 'ABORT: this script is for techaid_uat only, connected to %', current_database();
  end if;
end $$;

\echo === BEFORE
select relname as table_name, (xpath('/row/c/text()', query_to_xml(format('select count(*) as c from public.%I', relname), false, true, '')))[1]::text::int as rows
from pg_class where relnamespace = 'public'::regnamespace and relkind = 'r' order by 1;

-- ---------------------------------------------------------------- what we keep
create temp table keep_org on commit drop as
  select id from referring_organisations where id in (119, 182, 183, 203, 227, 324);
do $$ begin
  if (select count(*) from keep_org) <> 6 then raise exception 'ABORT: expected 6 CTA orgs, found %', (select count(*) from keep_org); end if;
end $$;
create temp table keep_contact on commit drop as
  select id from referring_organisation_contacts where referring_organisation_id in (select id from keep_org);
create temp table keep_req on commit drop as
  select id from device_requests where referring_organisation_contact_id in (select id from keep_contact);
create temp table keep_kit on commit drop as
  select id from kits where device_request_id in (select id from keep_req);

\echo === kept: orgs / referees / requests / devices
select (select count(*) from keep_org) orgs, (select count(*) from keep_contact) referees,
       (select count(*) from keep_req) requests, (select count(*) from keep_kit) devices;

-- ---------------------------------------------------------------- real values, for the leftover check
create temp table pii (val text) on commit drop;
insert into pii
select distinct lower(trim(v)) from (
  select unnest(array[name, email, phone_number, post_code]) v from donors
  union all select unnest(array[name, address]) from donor_parents
  union all select unnest(array[full_name, email, phone_number, address]) from referring_organisation_contacts
            where id not in (select id from keep_contact)
  union all select unnest(array[first_name || ' ' || surname, email, phone, address]) from delivery_bookings
  union all select unnest(array[collection_contact_name, client_ref]) from device_requests
) s
where v is not null and length(trim(v)) >= 6
  and v !~* '(erased|gdpr|anonymi[sz]ed|redacted)';
-- Not personal data, and each produced a false hit in the 2026-11-02 dry run:
--   bare domains (communitytechaid.org.uk, lambeth.gov.uk) typed into address/name fields;
--   CTA's own name and office address (kit locations); placeholder phone numbers.
delete from pii where val ~ '^[a-z0-9.-]+\.[a-z]{2,}$'
                   or val ~* '(community ?tech ?aid|canterbury court|sw9 6de)'
                   or val ~ '^[0 ]*1234567890?$';
-- values that legitimately stay: the kept staff referees (also as substrings of their fields)
delete from pii p where exists (
  select 1 from referring_organisation_contacts c, unnest(array[c.full_name, c.email, c.phone_number, c.address]) f
  where c.id in (select id from keep_contact) and f is not null and position(p.val in lower(f)) > 0);
\echo === real values captured for the leftover check
select count(*) from pii;

-- ---------------------------------------------------------------- deletes (no FK cascades except kits.donor_id SET NULL)
delete from delivery_bookings;
delete from note where kit_id is null or kit_id not in (select id from keep_kit);
delete from kits where id not in (select id from keep_kit);
delete from device_requests_notes where device_request_id not in (select id from keep_req);
delete from device_requests where id not in (select id from keep_req);
delete from referring_organisation_contacts_notes where referring_organisation_contact_id not in (select id from keep_contact);
delete from referring_organisation_contacts where id not in (select id from keep_contact);
delete from referring_organisations_notes where referring_organisation_id not in (select id from keep_org);
delete from referrer_limit_exceptions where referring_organisation_id not in (select id from keep_org);
delete from referring_organisations where id not in (select id from keep_org);
delete from donors where id not in (select donor_id from kits where donor_id is not null);
delete from donor_parents where id not in (select donor_parent_id from donors where donor_parent_id is not null);

delete from device_requests_audit_trail;
delete from donor_parents_audit_trail;
delete from donors_audit_trail;
delete from kit_audit_trail;
delete from note_aud;
delete from referring_organisation_contacts_audit_trail;
delete from referring_organisations_audit_trail;
delete from custom_rev_info;

drop table if exists kit_updated_at_backup_20260818;

-- ---------------------------------------------------------------- scrubs on what we keep
update device_requests
   set client_ref = 'SCRUBBED-' || id,
       details = 'Synthetic details (scrubbed from UAT 2026-11-03)',
       collection_contact_name = case when collection_contact_name is not null then 'Test Collector' end;
update device_requests_notes set content = 'Scrubbed note';
update note set content = 'Scrubbed note';
update referring_organisation_contacts_notes set content = 'Scrubbed note';
update kits
   set attributes = (select coalesce(jsonb_object_agg(key, case when key in ('notes', 'credentials', 'pickupAvailability')
                                                               then to_jsonb('Scrubbed'::text) else value end), '{}'::jsonb)
                     from jsonb_each(attributes))
 where attributes is not null and jsonb_typeof(attributes) = 'object';
update donors
   set name = 'Scrubbed Donor ' || id,
       email = 'donor' || id || '@example.org',
       phone_number = '07700 900' || lpad((id % 1000)::text, 3, '0'),
       post_code = 'SW1A 1AA';
update donor_parents
   set name = 'Scrubbed Donor Group ' || id,
       address = 'Scrubbed address ' || id,
       website = null;

-- ---------------------------------------------------------------- leftover check
do $$
declare
  c record; hits bigint; report text := '';
begin
  for c in
    select col.table_name, col.column_name from information_schema.columns col
    join information_schema.tables t on t.table_schema = col.table_schema and t.table_name = col.table_name
    where col.table_schema = 'public' and t.table_type = 'BASE TABLE'
      -- posts = the public website's About/FAQ pages, which name partner organisations
      and col.table_name not in ('flyway_schema_history', 'posts')
      and col.data_type in ('character varying', 'text', 'character', 'jsonb')
  loop
    execute format(
      'select count(*) from public.%I x where exists (select 1 from pii p where lower(x.%I::text) = p.val
         or (length(p.val) >= 8 and position(p.val in lower(x.%I::text)) > 0))',
      c.table_name, c.column_name, c.column_name) into hits;
    if hits > 0 then report := report || format('%s.%s=%s ', c.table_name, c.column_name, hits); end if;
  end loop;
  if report <> '' then
    raise exception 'LEFTOVER CHECK FAILED - real values remain: %', report;
  end if;
  raise notice 'LEFTOVER CHECK PASSED - 0 real values remain in public';
end $$;

\echo === AFTER
select relname as table_name, (xpath('/row/c/text()', query_to_xml(format('select count(*) as c from public.%I', relname), false, true, '')))[1]::text::int as rows
from pg_class where relnamespace = 'public'::regnamespace and relkind = 'r' order by 1;

\if :{?commit}
  commit;
  \echo === COMMITTED
\else
  rollback;
  \echo === DRY RUN - rolled back, nothing changed (re-run with -v commit=1 to apply)
\endif
