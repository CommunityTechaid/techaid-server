-- Borough-group x device-type availability, plus per-referee request limits (dashboard #179).
--
-- The unit of configuration is a GROUP of boroughs, not a borough. Lambeth and Southwark have
-- always behaved as one record and must keep doing so, while Tower Hamlets launches as a pilot
-- with its own narrower offer and its own request limit. Modelling one borough per row would
-- have forced every Lambeth change to be made twice and stayed silently out of step the first
-- time someone forgot.
--
-- Column names/types mirror what Hibernate's implicit naming derives from the entities in
-- BoroughAvailabilityModels.kt so ddl-auto=validate passes (see SchemaValidationTest).

create sequence if not exists borough_groups_sequence start with 1 increment by 1;
create sequence if not exists borough_availability_sequence start with 1 increment by 1;
create sequence if not exists referrer_limit_exceptions_sequence start with 1 increment by 1;

create table if not exists borough_groups (
    id bigint not null,
    name varchar(255) not null,
    status varchar(255) not null,
    max_per_referee integer not null,
    created_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    primary key (id)
);

-- One row per borough in a group. A borough may belong to at most one group: the unique index
-- is what makes "which group governs this postcode's borough?" a question with one answer, and
-- it is enforced here rather than in application code because the split/merge operations in the
-- admin UI are exactly where an overlap would otherwise creep in.
create table if not exists borough_group_boroughs (
    borough_group_id bigint not null,
    borough varchar(255) not null
);

create unique index if not exists ux_borough_group_boroughs_borough
    on borough_group_boroughs (borough);

alter table if exists borough_group_boroughs
    add constraint fk_borough_group_boroughs_group
    foreign key (borough_group_id) references borough_groups;

-- Three states, not a boolean. ON and OFF are manual; AUTO means "derived from stock", which is
-- NOT implemented — see the comment on AvailabilityMode in BoroughAvailabilityModels.kt. AUTO is
-- accepted and stored so the admin screen can offer it, and resolves to closed at read time.
create table if not exists borough_availability (
    id bigint not null,
    borough_group_id bigint not null,
    device_type varchar(255) not null,
    mode varchar(255) not null,
    primary key (id)
);

create unique index if not exists ux_borough_availability_group_device
    on borough_availability (borough_group_id, device_type);

alter table if exists borough_availability
    add constraint fk_borough_availability_group
    foreign key (borough_group_id) references borough_groups;

-- Organisation-level overrides of a group's max_per_referee. Exceptions only — an organisation
-- with no row here simply gets the group value, so this table stays short and readable rather
-- than holding a row per organisation per group.
create table if not exists referrer_limit_exceptions (
    id bigint not null,
    referring_organisation_id bigint not null,
    borough_group_id bigint not null,
    max_per_referee integer not null,
    created_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    primary key (id)
);

create unique index if not exists ux_referrer_limit_exceptions_org_group
    on referrer_limit_exceptions (referring_organisation_id, borough_group_id);

alter table if exists referrer_limit_exceptions
    add constraint fk_referrer_limit_exceptions_org
    foreign key (referring_organisation_id) references referring_organisations;

alter table if exists referrer_limit_exceptions
    add constraint fk_referrer_limit_exceptions_group
    foreign key (borough_group_id) references borough_groups;

-- Seed today's behaviour exactly, so deploying this changes nothing for anyone.
--
-- Lambeth & Southwark: one LIVE group offering everything, because that is what the public form
-- offers today (it is gated only by the global admin config, which still applies on top). Its
-- max_per_referee is 3, matching DEVICE_REQUEST_LIMIT in DeviceRequestMutations.kt — that
-- constant remains the enforced cap for now; this column does not yet drive it.
insert into borough_groups (id, name, status, max_per_referee, created_at, updated_at)
values (1, 'Lambeth & Southwark', 'LIVE', 3, now(), now())
on conflict do nothing;

insert into borough_group_boroughs (borough_group_id, borough)
values (1, 'Lambeth'), (1, 'Southwark')
on conflict do nothing;

insert into borough_availability (id, borough_group_id, device_type, mode) values
    (1, 1, 'laptops', 'ON'),
    (2, 1, 'desktops', 'ON'),
    (3, 1, 'tablets', 'ON'),
    (4, 1, 'phones', 'ON'),
    (5, 1, 'allInOnes', 'ON'),
    (6, 1, 'commsDevices', 'ON'),
    (7, 1, 'broadbandHubs', 'ON'),
    (8, 1, 'other', 'ON')
on conflict do nothing;

-- Tower Hamlets: PILOT, laptops only. This reproduces the placeholder constant the dashboard
-- shipped in #178 (borough-device-availability.ts), which this table replaces — the pilot's
-- offer must not change just because it moved from code to config.
insert into borough_groups (id, name, status, max_per_referee, created_at, updated_at)
values (2, 'Tower Hamlets', 'PILOT', 1, now(), now())
on conflict do nothing;

insert into borough_group_boroughs (borough_group_id, borough)
values (2, 'Tower Hamlets')
on conflict do nothing;

insert into borough_availability (id, borough_group_id, device_type, mode) values
    (9, 2, 'laptops', 'ON'),
    (10, 2, 'desktops', 'OFF'),
    (11, 2, 'tablets', 'OFF'),
    (12, 2, 'phones', 'OFF'),
    (13, 2, 'allInOnes', 'OFF'),
    (14, 2, 'commsDevices', 'OFF'),
    (15, 2, 'broadbandHubs', 'OFF'),
    (16, 2, 'other', 'OFF')
on conflict do nothing;

-- Keep the sequences ahead of the seeded rows so generated ids don't collide.
select setval('borough_groups_sequence', 2, true);
select setval('borough_availability_sequence', 16, true);
