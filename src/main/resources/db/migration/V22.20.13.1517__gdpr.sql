---- GDPR data removal/archiving


-- Remove records with personally identifiable information after 12 months: donors
-- and volunteers. For donors, it is only individuals and not businesses that need
-- removal.

-- The idea is to set things up so that when a record is removed, on delete
-- constraints handle foreign key cleanup, and triggers do any archiving, etc.
-- required (e.g., see donors)

-- Use a schema to keep this all tidy
create schema if not exists gdpr;

--- Donors

--  A donor record is due for GDPR removal 12 months after the last time the donor
--  filled in the form (a donor can fill the form in more than once and all of
--  their kit donations in these forms are linked to the same donor entry). The
--  UPDATED_AT column in DONORS does not necessarily mark this (it can be changed
--  for other reasons, such as correcting an email address), so we take the most
--  recent device offer as the date for removal calculations.

--   This involves using a slightly complex query a couple of times, so I've made
--   it into a view, which simplifies things a lot.

--   The main complication is coming up with a value for KITS_MAX_CREATED_AT,
--   which is the creation date of the most recent kit from a donor. When not
--   NULL, this is the date used to calculate a GDPR-compliant retention period;
--   when NULL (i.e., no kits associated with the donor), use creation date of the
--   donor record.

drop view if exists gdpr.donors_to_delete;

create view gdpr.donors_to_delete as 
  select donors.id, donors.name, donors.created_at,
         coalesce(max(kits.created_at), donors.created_at) as kits_max_created_at
    from kits right join donors on donor_id = donors.id
  -- TODO: change below when we have donor types in the table
   where donors.name not similar to '%#(business|droppoint)%'
   group by donor_id, donors.id
  having coalesce(max(kits.created_at), donors.created_at)
         <= (current_date - INTERVAL '12 months');
       
-- Autoremove donor_id in kits on deletion of a donor
alter table kits drop constraint "fktx5w8x58hp99pssrocdy5d4o";                
alter table kits add constraint "fktx5w8x58hp99pssrocdy5d4o"
  foreign key (donor_id) references donors(id) on delete set null;             

--  Table to capture historical traces of removed donor records (for analysis).
--  This contains no personally identifiable information:
--  
--    donor ID, created_at, kits_max_created_at, deletion_date, referral
--  
--    REFERRAL is what the donor has filled in in answer to "How did you hear
--    about us?"

create table if not exists gdpr.donors_archive (
  donor_id bigint primary key,
  created_at timestamp without time zone,
  -- UPDATED_AT here is KITS_MAX_CREATED_AT from the gdpr.donors_to_delete view
  updated_at timestamp without time zone,
  deleted_at timestamp without time zone default now(),
  referral text);


create or replace function gdpr.archive_donor_info ()
  returns trigger
as
$$
  BEGIN
    insert into gdpr.donors_archive(donor_id, created_at, updated_at, referral)
    values(OLD.id,
           OLD.created_at,
           (select kits_max_created_at from gdpr.donors_to_delete
             where id = OLD.id),
             OLD.referral);
    return OLD;
  END;
$$ language plpgsql;

drop trigger if exists gdpr_donors_trigger on donors;

-- NB trigger BEFORE delete so we can use the view to get updated_at; not sure if
-- this is necessary, but it can't do any harm.
create trigger gdpr_donors_trigger before delete on donors
  for each row execute procedure gdpr.archive_donor_info();

--- Volunteers

-- Deletion of volunteers is done manually currently, so much simpler to set up:

-- Set appropriate constraints on these foreign keys:
-- TABLE "kit_volunteers" CONSTRAINT "fka45nb0ccimms00ruh56bi8a31" FOREIGN KEY (volunteer_id) REFERENCES volunteers(id)
-- TABLE "organisations" CONSTRAINT "fkgk1igqvcvj38qbu8tr81196mi" FOREIGN KEY (volunteer_id) REFERENCES volunteers(id)
--
--   for kit_volunteers, on delete cascade (remove row on delete)
--   for organisations, on delete set null 

alter table kit_volunteers drop constraint "fka45nb0ccimms00ruh56bi8a31";
alter table kit_volunteers add constraint "fka45nb0ccimms00ruh56bi8a31"
  foreign key (volunteer_id) references volunteers(id) on delete cascade;

alter table organisations drop constraint "fkgk1igqvcvj38qbu8tr81196mi";
alter table organisations add constraint "fkgk1igqvcvj38qbu8tr81196mi"
  foreign key (volunteer_id) references volunteers(id) on delete set null;
