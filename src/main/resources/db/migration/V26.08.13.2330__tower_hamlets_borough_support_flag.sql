-- Seed the flag row for Tower Hamlets borough support, ahead of the feature work.
-- The row must exist for the flag to appear in the dashboard's Feature Flags admin
-- page, which lists only persisted rows.
--
-- Nothing reads this flag yet. It is seeded now so the toggle exists and is
-- consistent across UAT and production before the borough-support work lands —
-- switching it on before there is code behind it has no effect.

insert into feature_flags (flag_key, enabled, updated_at)
values ('tower-hamlets-borough-support', false, now())
on conflict (flag_key) do nothing;
