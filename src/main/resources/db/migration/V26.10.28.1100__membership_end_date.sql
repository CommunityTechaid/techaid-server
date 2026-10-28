-- Membership end date (techaid-server #311 follow-up). Paid tiers now carry an explicit end date;
-- a paid membership only counts between its start and end dates and lapses to FREE afterwards.
-- FREE always uses the rolling 1 February default year, so its start/end are ignored.
alter table referring_organisations add column membership_end_date date;
alter table referring_organisations_audit_trail add column membership_end_date date;

-- Existing paid memberships ran for one year from their start date.
update referring_organisations
set membership_end_date = (membership_start_date + interval '1 year' - interval '1 day')::date
where membership_tier <> 'FREE';
