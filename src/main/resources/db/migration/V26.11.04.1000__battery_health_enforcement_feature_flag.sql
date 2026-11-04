-- Seed the flag row for the battery-health guard (#264): a laptop, tablet or smartphone
-- at 50% battery health or below (or with no reading) must not be moved into an
-- allocation status. The row must exist for the flag to appear in the Feature Flags admin
-- page, which lists only persisted rows.

-- Off by default = shadow mode: the guard evaluates and WARN-logs would-blocks but
-- allows the write. Switching it on rejects the transition with a BAD_REQUEST.
insert into feature_flags (flag_key, enabled, updated_at)
values ('battery-health-enforcement', false, now())
on conflict (flag_key) do nothing;
