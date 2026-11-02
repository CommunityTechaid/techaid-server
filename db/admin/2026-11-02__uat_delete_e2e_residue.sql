-- Delete dashboard E2E residue from techaid_uat. Re-runnable: use it after every E2E suite run.
--
-- WHY THIS EXISTS
--   The dashboard's write-flow specs create real UAT records named with the "E2E" marker
--   (e2e/helpers/sample-data.ts: "<Entity> E2E Sample <timestamp>"). Teardown can only archive them,
--   because staff tokens carry no delete:* permission, so they pile up as archived rows. The
--   dashboard's `npm run e2e:cleanup -- --delete` hits the same permission wall, and it deletes
--   referees and orgs before their requests, which the foreign keys refuse anyway.
--
-- WHAT IT DELETES (marker: 'E2E', case-sensitive, as in the dashboard tool)
--   orgs named E2E and everything under them; referees named E2E; requests with an E2E client_ref
--   or belonging to a deleted referee; kits with an E2E model or serial; donors and donor parents
--   named E2E. Also their notes, delivery bookings/overrides on the deleted requests, and their
--   Envers audit rows, plus any custom_rev_info revision left with no audit rows.
--   An unmarked kit on a deleted request is unassigned, not deleted.
--   Aborts if a marked org is one of the kept Community TechAid orgs.
--
-- WHY NOT FLYWAY: recurring test-data housekeeping for one environment; never for production.
--
-- HOW TO RUN (as api_uat, which owns every public table; no admin role needed)
--   Dry run (default, rolls back):  psql ... -X -f this.sql
--   Real run:                       psql ... -X -v commit=1 -f this.sql
--
-- APPLIED TO
--   techaid_uat - 2026-11-02 ~18:00 GMT, after the first E2E run on the synthetic data: removed
--                 2 orgs, 2 referees, 1 request, 3 kits (one from 2026-10-07 on a kept CTA request),
--                 2 donors, their audit rows and 25 orphaned revisions; residue check all 0.
--                 Later runs are routine and are not logged here.

\set ON_ERROR_STOP 1
begin;

do $$ begin
  if current_database() <> 'techaid_uat' then
    raise exception 'ABORT: this script is for techaid_uat only, connected to %', current_database();
  end if;
end $$;

create temp table m_org on commit drop as
  select id from referring_organisations where name ~ 'E2E';
do $$ begin
  if exists (select 1 from m_org where id in (119, 182, 183, 203, 227, 324)) then
    raise exception 'ABORT: a kept Community TechAid org carries the E2E marker';
  end if;
end $$;
create temp table m_contact on commit drop as
  select id from referring_organisation_contacts
  where full_name ~ 'E2E' or referring_organisation_id in (select id from m_org);
create temp table m_req on commit drop as
  select id from device_requests
  where client_ref ~ 'E2E' or referring_organisation_contact_id in (select id from m_contact);
create temp table m_kit on commit drop as
  select id from kits where model ~ 'E2E' or serial_no ~ 'E2E';
create temp table m_donor on commit drop as
  select id from donors where name ~ 'E2E';
create temp table m_parent on commit drop as
  select id from donor_parents where name ~ 'E2E';

\echo === E2E residue found
select (select count(*) from m_org) orgs, (select count(*) from m_contact) referees, (select count(*) from m_req) requests,
       (select count(*) from m_kit) kits, (select count(*) from m_donor) donors, (select count(*) from m_parent) donor_parents;

-- Deletes, children first (no FK cascades except kits.donor_id SET NULL).
delete from note where kit_id in (select id from m_kit);
delete from device_requests_notes where device_request_id in (select id from m_req);
update kits set device_request_id = null
 where device_request_id in (select id from m_req) and id not in (select id from m_kit);
delete from kits where id in (select id from m_kit);
delete from delivery_bookings where cta_reference::text in (select id::text from m_req);
delete from delivery_booking_overrides where cta_reference::text in (select id::text from m_req);
delete from device_requests where id in (select id from m_req);
delete from referring_organisation_contacts_notes where referring_organisation_contact_id in (select id from m_contact);
delete from referring_organisation_contacts where id in (select id from m_contact);
delete from referring_organisations_notes where referring_organisation_id in (select id from m_org);
delete from referrer_limit_exceptions where referring_organisation_id in (select id from m_org);
delete from referring_organisations where id in (select id from m_org);
delete from donors where id in (select id from m_donor);
update donors set donor_parent_id = null where donor_parent_id in (select id from m_parent);
delete from donor_parents where id in (select id from m_parent);

-- Audit history of what was just deleted, then revisions nothing points at any more.
delete from referring_organisations_audit_trail where id in (select id from m_org);
delete from referring_organisation_contacts_audit_trail where id in (select id from m_contact);
delete from device_requests_audit_trail where id in (select id from m_req);
delete from kit_audit_trail where id in (select id from m_kit);
delete from donors_audit_trail where id in (select id from m_donor);
delete from donor_parents_audit_trail where id in (select id from m_parent);
delete from note_aud where kit_id in (select id from m_kit);
delete from custom_rev_info r where not exists (select 1 from referring_organisations_audit_trail a where a.rev = r.id)
  and not exists (select 1 from referring_organisation_contacts_audit_trail a where a.rev = r.id)
  and not exists (select 1 from device_requests_audit_trail a where a.rev = r.id)
  and not exists (select 1 from kit_audit_trail a where a.rev = r.id)
  and not exists (select 1 from donors_audit_trail a where a.rev = r.id)
  and not exists (select 1 from donor_parents_audit_trail a where a.rev = r.id)
  and not exists (select 1 from note_aud a where a.rev = r.id);

\echo === residue left (all must be 0)
select (select count(*) from referring_organisations where name ~ 'E2E') orgs,
       (select count(*) from referring_organisation_contacts where full_name ~ 'E2E') referees,
       (select count(*) from device_requests where client_ref ~ 'E2E') requests,
       (select count(*) from kits where model ~ 'E2E' or serial_no ~ 'E2E') kits,
       (select count(*) from donors where name ~ 'E2E') donors;

\if :{?commit}
  commit;
  \echo === COMMITTED
\else
  rollback;
  \echo === DRY RUN - rolled back, nothing changed (re-run with -v commit=1 to apply)
\endif
