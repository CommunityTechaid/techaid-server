-- Change history for admin-panel settings (#327): feature flags, borough groups/availability/limit
-- exceptions and admin_config. Append-only, written by the application (source USER) in the same
-- transaction as the change, and by any migration that changes a flag (source MIGRATION).
-- Not Envers: migrations change flags with raw SQL, which Envers never sees.
--
-- id defaults to the sequence so a migration can insert with plain SQL. Column names/types mirror
-- AdminSettingHistoryModels.kt so ddl-auto=validate passes (see SchemaValidationTest).

create sequence if not exists admin_setting_history_sequence start with 1 increment by 1;

create table if not exists admin_setting_history (
    id bigint not null default nextval('admin_setting_history_sequence'),
    changed_at timestamp(6) with time zone not null default now(),
    source varchar(255) not null
        check (source in ('USER', 'MIGRATION', 'SYSTEM')),
    changed_by_email varchar(255),
    changed_by_name varchar(255),
    setting_type varchar(255) not null
        check (setting_type in ('FEATURE_FLAG', 'BOROUGH_GROUP', 'BOROUGH_AVAILABILITY', 'LIMIT_EXCEPTION', 'ADMIN_CONFIG')),
    setting_key varchar(255) not null,
    subject_id bigint,
    subject_label varchar(255),
    field varchar(255) not null,
    change_type varchar(255) not null
        check (change_type in ('CREATED', 'UPDATED', 'DELETED')),
    old_value text,
    new_value text,
    primary key (id)
);

create index if not exists ix_admin_setting_history_type_key_changed_at
    on admin_setting_history (setting_type, setting_key, changed_at desc);
create index if not exists ix_admin_setting_history_changed_at
    on admin_setting_history (changed_at desc);

-- Backfill: one initial row per existing flag so no current flag has an empty history.
insert into admin_setting_history (source, setting_type, setting_key, subject_label, field, change_type, old_value, new_value)
select 'MIGRATION', 'FEATURE_FLAG', f.flag_key, f.flag_key, 'enabled', 'CREATED', null,
       case when f.enabled then 'true' else 'false' end
from feature_flags f
where not exists (
    select 1 from admin_setting_history h
    where h.setting_type = 'FEATURE_FLAG' and h.setting_key = f.flag_key
)
order by f.flag_key;
