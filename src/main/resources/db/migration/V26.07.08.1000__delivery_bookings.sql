-- Public delivery-booking flow: config, windows, blocked dates and submitted bookings.
-- Column names/types mirror what Hibernate's implicit naming derives from the entities in
-- DeliveryModels.kt so ddl-auto=validate passes (see SchemaValidationTest).

create sequence if not exists delivery_windows_sequence start with 1 increment by 1;
create sequence if not exists delivery_blocked_dates_sequence start with 1 increment by 1;
create sequence if not exists delivery_bookings_sequence start with 1 increment by 1;

create table if not exists delivery_config (
    id bigint not null,
    enabled boolean not null,
    days_of_week varchar(255),
    lead_time_days integer not null,
    advance_days integer not null,
    created_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    primary key (id)
);

create table if not exists delivery_windows (
    id bigint not null,
    name varchar(255),
    start_time varchar(255),
    end_time varchar(255),
    icon varchar(255),
    capacity integer not null,
    sort_order integer not null,
    active boolean not null,
    primary key (id)
);

create table if not exists delivery_blocked_dates (
    id bigint not null,
    blocked_date date,
    reason varchar(255),
    primary key (id)
);

create table if not exists delivery_bookings (
    id bigint not null,
    delivery_date date,
    window_id bigint,
    first_name varchar(255),
    surname varchar(255),
    email varchar(255),
    phone varchar(255),
    address TEXT,
    access_notes TEXT,
    cta_reference varchar(255),
    created_at timestamp(6) with time zone,
    primary key (id)
);

alter table if exists delivery_bookings
    add constraint fk_delivery_bookings_window
    foreign key (window_id) references delivery_windows;

-- Seed the config + windows with the values the original front-end mock hard-coded.
insert into delivery_config (id, enabled, days_of_week, lead_time_days, advance_days, created_at, updated_at)
values (1, true, '2,4', 1, 4, now(), now());

insert into delivery_windows (id, name, start_time, end_time, icon, capacity, sort_order, active) values
    (1, 'Morning window', '10:00am', '1:00pm', '☀️', 4, 1, true),
    (2, 'Afternoon window', '2:00pm', '5:00pm', '🌤️', 4, 2, true);

-- Keep the sequence ahead of the seeded rows so generated ids don't collide.
select setval('delivery_windows_sequence', 2, true);
