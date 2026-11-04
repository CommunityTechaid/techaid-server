-- Seed the flag row for the battery-health guard (#264): a laptop, tablet or smartphone
-- at 50% battery health or below (or with no reading) must not be moved into an
-- allocation status. The row must exist for the flag to appear in the Feature Flags admin
-- page, which lists only persisted rows.

-- Off by default = shadow mode: the guard evaluates and WARN-logs would-blocks but
-- allows the write. Switching it on rejects the transition with a BAD_REQUEST.
insert into feature_flags (flag_key, enabled, updated_at)
values ('battery-health-enforcement', false, now())
on conflict (flag_key) do nothing;

-- Record the flag's creation in the admin-settings history (#328), the same way
-- V26.11.03.1000 backfilled every flag that existed before it. Without this the flag
-- would show an empty history. Guarded so a re-run, or a flag created some other way, adds nothing.
insert into admin_setting_history (source, setting_type, setting_key, subject_label, field, change_type, old_value, new_value)
select 'MIGRATION', 'FEATURE_FLAG', f.flag_key, f.flag_key, 'enabled', 'CREATED', null,
       case when f.enabled then 'true' else 'false' end
from feature_flags f
where f.flag_key = 'battery-health-enforcement'
  and not exists (
    select 1 from admin_setting_history h
    where h.setting_type = 'FEATURE_FLAG' and h.setting_key = f.flag_key
);
