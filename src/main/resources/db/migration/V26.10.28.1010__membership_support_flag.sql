-- Seed the flag row for membership support (techaid-server #311). The row must exist for the flag
-- to appear in the dashboard's Feature Flags admin page, which lists only persisted rows.
--
-- Default OFF in every environment. While off:
--   * the dashboard requests none of the membership fields, and
--   * createDeviceRequest does NOT enforce the membership allowance. Every existing organisation
--     was just defaulted to the FREE tier (cap 10 a year), so enforcing before staff have set
--     tiers would start refusing or "grace"-marking established organisations the moment this
--     deploys.
-- Staff switch it on once tiers have been reviewed.

insert into feature_flags (flag_key, enabled, updated_at)
values ('membership-support', false, now())
on conflict (flag_key) do nothing;
