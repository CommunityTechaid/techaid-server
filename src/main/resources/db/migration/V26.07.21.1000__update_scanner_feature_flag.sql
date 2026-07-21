-- Seed the flag row for the dashboard's "Update Scanner" page (barcode-driven
-- device status updates). The row must exist for the flag to appear in the
-- Feature Flags admin page, which lists only persisted rows.

-- Off by default: while off, the dashboard shows the page to holders of
-- 'app:bulkedit' only; switching it on opens it to all staff.
insert into feature_flags (flag_key, enabled, updated_at)
values ('update-scanner', false, now())
on conflict (flag_key) do nothing;
