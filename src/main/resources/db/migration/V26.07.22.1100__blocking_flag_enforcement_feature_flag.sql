-- Seed the flag row for the blocking-flag sub-status guard (#90): a kit carrying
-- wipeFailed / installationOfOSFailed / needsFurtherInvestigation / needsSparePart /
-- lockedToUser must not be advanced into an allocation or delivered status. The row
-- must exist for the flag to appear in the Feature Flags admin page, which lists only
-- persisted rows.

-- Off by default = shadow mode: the guard evaluates and WARN-logs would-blocks but
-- allows the write. Switching it on rejects the transition with a BAD_REQUEST.
insert into feature_flags (flag_key, enabled, updated_at)
values ('blocking-flag-enforcement', false, now())
on conflict (flag_key) do nothing;
