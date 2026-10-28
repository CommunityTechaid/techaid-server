-- Membership support (techaid-server #311): tiers, the yearly request allowance and the
-- over-limit configuration. Nothing here changes behaviour on its own — enforcement is gated by
-- the membership-support feature flag (see V26.10.28.1010).
--
-- The membership period is never stored. Only the start date is; the end is start + 1 year - 1 day
-- and the period rolls forward on read, so there is no renewal job to miss while the app is scaled
-- to zero.

-- Organisation: tier + the start date the membership year is anchored to.
alter table referring_organisations
    add column membership_tier varchar(255) not null default 'FREE'
        check (membership_tier in ('FREE', 'TIER_1', 'TIER_2', 'TIER_3'));

-- Backfilled (and defaulted for any row written outside the app) to 1 February of the membership
-- year we are currently in, evaluated in Europe/London: before 1 Feb the year began last February.
alter table referring_organisations add column membership_start_date date;

update referring_organisations
set membership_start_date = make_date(
    extract(year from (now() at time zone 'Europe/London'))::int
        - case when extract(month from (now() at time zone 'Europe/London')) < 2 then 1 else 0 end,
    2,
    1);

alter table referring_organisations alter column membership_start_date set not null;
alter table referring_organisations
    alter column membership_start_date set default make_date(
        extract(year from (now() at time zone 'Europe/London'))::int
            - case when extract(month from (now() at time zone 'Europe/London')) < 2 then 1 else 0 end,
        2,
        1);

-- Envers audit twin (nullable, like every other audit column).
alter table referring_organisations_audit_trail add column membership_tier varchar(255);
alter table referring_organisations_audit_trail add column membership_start_date date;

-- Referee: flags a membership contact. char(1) 'Y'/'N' = the YesNoConverter pattern used for
-- `archived`. An organisation may have several.
alter table referring_organisation_contacts
    add column is_membership_contact char(1) not null default 'N'
        check (is_membership_contact in ('N', 'Y'));
alter table referring_organisation_contacts_audit_trail
    add column is_membership_contact char(1) check (is_membership_contact in ('N', 'Y'));

-- Device request: set only on requests accepted past the cap in GRACE mode (1-based).
alter table device_requests add column membership_grace_index integer;
alter table device_requests_audit_trail add column membership_grace_index integer;

-- Admin config (not audited). GRACE with 2 requests is the placeholder agreed pending Cat's
-- decision; both are changeable without a deploy.
alter table admin_config
    add column membership_over_limit_mode varchar(255) not null default 'GRACE'
        check (membership_over_limit_mode in ('GRACE', 'BLOCK'));
alter table admin_config add column membership_grace_requests integer not null default 2;
