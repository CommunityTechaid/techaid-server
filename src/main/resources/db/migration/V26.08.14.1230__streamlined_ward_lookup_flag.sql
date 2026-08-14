-- Seed the flag row for the streamlined (in-app) ward lookup on the public device
-- request page. The row must exist for the flag to appear in the dashboard's Feature
-- Flags admin page, which lists only persisted rows.
--
-- Unlike most flags here, this one is not a feature hide — it is a two-way substitution
-- switch between two working implementations of the same step:
--
--   off (default) -> the legacy ward lookup, an iframe onto
--                    communitytechaid.github.io/ward_lookup.html
--   on            -> the streamlined postcode-first step built into the dashboard
--
-- Either can be selected at any time, in either direction, with no deploy. Off is the
-- current behaviour, which is why it is the default and the safe failure mode when the
-- flag cannot be read.
--
-- Note for whoever operates this: the legacy lookup cannot resolve a Tower Hamlets
-- postcode at any setting — its supported-borough list and its boundary data cover
-- Lambeth and Southwark only. So switching this flag off while
-- tower-hamlets-borough-support is on silently stops accepting Tower Hamlets referrals.
-- It looks like a safe rollback and is not. See CommunityTechaid/techaid-dashboard#177.

insert into feature_flags (flag_key, enabled, updated_at)
values ('streamlined-ward-lookup', false, now())
on conflict (flag_key) do nothing;
