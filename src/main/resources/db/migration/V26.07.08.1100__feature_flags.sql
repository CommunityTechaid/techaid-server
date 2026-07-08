-- Named feature flags, toggled from the dashboard's Feature Flags admin page.
-- Column names/types mirror Hibernate's implicit naming for FeatureFlag so
-- ddl-auto=validate passes (see SchemaValidationTest).

create table if not exists feature_flags (
    flag_key varchar(255) not null,
    enabled boolean not null,
    updated_at timestamp(6) with time zone,
    primary key (flag_key)
);

-- The delivery-booking pages are hidden on production until this is switched on;
-- non-production (UAT) shows them regardless. Off by default.
insert into feature_flags (flag_key, enabled, updated_at)
values ('delivery-booking', false, now());
