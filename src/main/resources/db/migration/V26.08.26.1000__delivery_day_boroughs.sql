-- Borough-specific delivery days (sheet row 23), e.g. Southwark bookings on Tuesday, Lambeth on
-- Wednesday.
--
-- Held separately from borough_groups/borough_availability (BoroughAvailabilityModels.kt): those
-- govern which DEVICE TYPES a borough may ask for, this governs which WEEKDAY a borough may
-- book a delivery on. A weekday with no rows here is open to every borough (fails open) — see
-- DeliveryService.checkBoroughDaySchedule. Gated by delivery_config.borough_scheduling_enabled,
-- off by default and independent of the borough-availability-rules feature flag.
--
-- Column names/types mirror what Hibernate's implicit naming derives from the entities in
-- DeliveryModels.kt so ddl-auto=validate passes (see SchemaValidationTest).

create sequence if not exists delivery_day_boroughs_sequence start with 1 increment by 1;

create table if not exists delivery_day_boroughs (
    id bigint not null,
    day_of_week integer not null,
    borough varchar(255) not null,
    primary key (id)
);

create unique index if not exists ux_delivery_day_boroughs_day_borough
    on delivery_day_boroughs (day_of_week, borough);

alter table if exists delivery_config
    add column if not exists borough_scheduling_enabled boolean not null default false;
