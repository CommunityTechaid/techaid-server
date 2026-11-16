-- Seed the flag for the native equalities form, which replaces the equalities Typeform (f93D0s88)
-- on the public request form. Off = the dashboard keeps embedding Typeform; on = it shows the native
-- form, which submits through submitEqualities. Staff can switch back and forth while both paths exist.
insert into feature_flags (flag_key, enabled, updated_at)
values ('native-equalities-form', false, now())
on conflict (flag_key) do nothing;

-- Record the flag's creation in the admin-settings history (#328), as V26.11.04.1000 does.
insert into admin_setting_history (source, setting_type, setting_key, subject_label, field, change_type, old_value, new_value)
select 'MIGRATION', 'FEATURE_FLAG', f.flag_key, f.flag_key, 'enabled', 'CREATED', null,
       case when f.enabled then 'true' else 'false' end
from feature_flags f
where f.flag_key = 'native-equalities-form'
  and not exists (
    select 1 from admin_setting_history h
    where h.setting_type = 'FEATURE_FLAG' and h.setting_key = f.flag_key
);
