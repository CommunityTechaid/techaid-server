-- Seed the flag that decides whether the borough configuration governs the PUBLIC device
-- request journey.
--
-- The config tables and the admin screen (V26.08.14.2100) are deliberately NOT gated: staff can
-- see and edit the borough x device-type matrix, including Tower Hamlets, whatever this flag
-- says. What the flag controls is whether any of that configuration reaches a member of the
-- public:
--
--   off (default) -> RefereeRequestLimitService falls back to the pre-config behaviour (a global
--                    cap of 3 counted against the referee's total open requests), and
--                    boroughAvailabilityPublic returns an empty list, which the dashboard already
--                    treats as "no restriction recorded" and so offers every device type. This is
--                    byte-for-byte today's public behaviour.
--   on            -> borough groups drive both the per-referee cap and the device types offered.
--
-- Correcting the record: the comment on V26.08.14.2100 says max_per_referee "does not yet drive"
-- enforcement and boroughAvailability.graphqls called it "advisory". That stopped being true one
-- commit later, when the per-group limit was wired into createDeviceRequest. That earlier file
-- cannot be edited — it is already applied in UAT and Flyway would reject the changed checksum —
-- so the correction lives here.
--
-- Measured before choosing the default (prod, 2026-08-17): 220 referees hold at least one open
-- request; 29 are at or over the cap of 3; switching this on moves exactly 6 of them from
-- blocked to allowed, because they hold pre-2025 requests that predate the borough field and so
-- stop counting once counting is borough-scoped. See the staff note for who they are.

insert into feature_flags (flag_key, enabled, updated_at)
values ('borough-availability-rules', false, now())
on conflict (flag_key) do nothing;
