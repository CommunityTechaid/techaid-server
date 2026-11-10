-- Delivery configuration joins the admin-setting history: delivery config, windows, blocked dates
-- and per-weekday borough restrictions. Additive: widens the setting_type check so the new enum
-- values (SettingType in AdminSettingHistoryModels.kt) can be stored. Existing rows are untouched.
alter table admin_setting_history drop constraint if exists admin_setting_history_setting_type_check;
alter table admin_setting_history add constraint admin_setting_history_setting_type_check
    check (setting_type in (
        'FEATURE_FLAG', 'BOROUGH_GROUP', 'BOROUGH_AVAILABILITY', 'LIMIT_EXCEPTION', 'ADMIN_CONFIG',
        'DELIVERY_CONFIG', 'DELIVERY_WINDOW', 'DELIVERY_BLOCKED_DATE', 'DELIVERY_DAY_BOROUGH'
    ));
