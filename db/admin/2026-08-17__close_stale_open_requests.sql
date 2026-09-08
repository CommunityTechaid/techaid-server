-- ============================================================================================
-- STATUS: NOT APPLIED ANYWHERE. Recorded here 2026-09-08 per db/admin/README.md rule 1
-- ("record every admin-applied statement here before running it"), so that the analysis below
-- survives and so any future run has a reviewed script to run rather than a fresh improvisation.
--
-- Applied to:
--   techaid_prod   -- not applied
--   techaid_uat    -- not applied
--
-- BLOCKED ON A DECISION, not on engineering. The REQUEST_COMPLETED vs REQUEST_CANCELLED choice
-- below is a distribution-reporting decision (see "THE DECISION THIS SCRIPT DOES NOT MAKE FOR
-- YOU"). Do not run this until whoever owns that reporting has made the call.
--
-- ALSO STALE: the measurements are from 2026-08-17. Re-run the read-only companion
-- db/admin/stale_open_requests_export.sql first and confirm the population ON REQUEST IDS, not
-- on its headline count -- the export's filter is deliberately BROADER than this script's
-- (it takes any status NOT IN (COMPLETED, DECLINED, CANCELLED) with no borough, where this
-- script additionally requires PROCESSING_COLLECTION_DELIVERY_ARRANGED and
-- created_at < 2025-06-01), so the two totals are not expected to match.
--
-- As of 2026-09-08 the wider context is deferred to routine staff admin cleanup; the borough
-- scheduling feature this unblocks ships disabled by default.
-- ============================================================================================

-- PROPOSAL — NOT YET RUN. Close the 50 long-open device requests that predate the borough field.
--
-- Read db/admin/README.md first. Run the read-only companion
-- (db/admin/stale_open_requests_export.sql) immediately before this to confirm the population
-- still matches what was measured on 2026-08-17.
--
-- ============================================================================================
-- WHAT THIS IS FOR
-- ============================================================================================
--
-- 50 device requests raised between 2023-10-10 and 2025-05-29 are still sitting in
-- PROCESSING_COLLECTION_DELIVERY_ARRANGED. Measured evidence that they are finished work, not
-- live demand:
--
--   * every one has a collection_date set, the latest of which was 2025-12-09;
--   * 48 of 50 have kits attached, 52 of those 53 kits are already DISTRIBUTION_DELIVERED;
--   * no human has touched them since — the only updated_at values are April 2026, which is the
--     GDPR retention scrub rewriting client_ref to 'WIPED - GDPR' (33 of the 50 carry that value);
--   * 28 of the 44 referees involved have since been archived.
--
-- So the devices went out and nobody came back to mark the request complete.
--
-- ============================================================================================
-- WHY IT MATTERS NOW
-- ============================================================================================
--
-- The per-referee cap is moving from one global number to a per-borough-group count (feature flag
-- borough-availability-rules). These requests have no borough recorded — the field did not exist
-- when they were raised — so they stop counting the moment that flag goes on. Six referees are
-- held at their cap purely by these, and would silently gain capacity. Closing them first means
-- the numbers move because we moved them.
--
-- ============================================================================================
-- THE DECISION THIS SCRIPT DOES NOT MAKE FOR YOU
-- ============================================================================================
--
-- There are two defensible statuses and they mean different things to anyone reading the history
-- later:
--
--   REQUEST_COMPLETED  — "this was fulfilled". True for the 48 with delivered kits. It is also
--                        what the dashboard would have set at the time.
--   REQUEST_CANCELLED  — "this was abandoned". Truthful about our own record-keeping, but it
--                        misreports 48 deliveries that actually happened, and would understate
--                        devices distributed in any report that counts completed requests.
--
-- This script uses REQUEST_COMPLETED for the 48 with delivered kits and REQUEST_CANCELLED for the
-- 2 with none (ids 4475 and 4377 as at 2026-08-17 — both have zero kits and no delivery to
-- claim). Change it if the team disagrees; do not run it while the team disagrees.
--
-- ============================================================================================
-- SIDE EFFECT YOU MUST DECIDE ABOUT
-- ============================================================================================
--
-- Doing this through the dashboard instead of SQL would ALSO mutate the attached kits:
-- updateDeviceRequest, on a transition into REQUEST_COMPLETED, sets every non-completed kit to
-- DISTRIBUTION_DELIVERED and archived = true (DeviceRequestMutations.kt). Against this population
-- that would flip 1 kit from ALLOCATION_DELIVERY_ARRANGED to DISTRIBUTION_DELIVERED and set
-- archived on 24 already-delivered kits.
--
-- This script deliberately does NOT touch kits, because closing a stale record is bookkeeping and
-- silently archiving 24 devices is not. If the team wants the kits archived too, do it as a
-- separate, separately-reviewed statement rather than as a side effect of this one.
--
-- Neither guard blocks the change either way: measured 0 of the 53 kits carry a blocking
-- sub-status flag, and both wipe-cert-enforcement and blocking-flag-enforcement are off in
-- production regardless.
--
-- ============================================================================================
-- AUDIT
-- ============================================================================================
--
-- device_requests is Envers-audited, so each UPDATE writes a revision to the audit trail. Those
-- revisions will carry no user attribution because this runs as api_prod rather than through the
-- app. That is the main argument for doing it from the dashboard instead if someone is willing to
-- click 50 times — it is 50 rows, not 50,000. This script exists for the case where they are not.

\set ON_ERROR_STOP on

BEGIN;

DO $$ BEGIN
  IF current_database() <> 'techaid_prod' THEN
    RAISE EXCEPTION 'ABORT: expected techaid_prod, got %', current_database();
  END IF;
END $$;

-- Freeze the population inside the transaction so the counts below describe exactly the rows
-- being changed, not a set that could drift between statements.
CREATE TEMP TABLE to_close ON COMMIT DROP AS
SELECT dr.id,
       (SELECT count(*) FROM kits k WHERE k.device_request_id = dr.id) AS kits_attached
FROM device_requests dr
WHERE dr.status = 'PROCESSING_COLLECTION_DELIVERY_ARRANGED'
  AND (dr.borough IS NULL OR dr.borough = '')
  AND dr.created_at < TIMESTAMPTZ '2025-06-01';

-- Guard against an unexpectedly large population: if this is not roughly the 50 rows measured on
-- 2026-08-17, something has changed and a human should look before anything is written.
DO $$
DECLARE n integer;
BEGIN
  SELECT count(*) INTO n FROM to_close;
  RAISE NOTICE 'rows in scope: %', n;
  IF n > 60 THEN
    RAISE EXCEPTION 'ABORT: % rows in scope, expected ~50. Re-run the export and re-read this script.', n;
  END IF;
END $$;

UPDATE device_requests dr
SET status = 'REQUEST_COMPLETED', updated_at = now()
FROM to_close t
WHERE dr.id = t.id AND t.kits_attached > 0;

UPDATE device_requests dr
SET status = 'REQUEST_CANCELLED', updated_at = now()
FROM to_close t
WHERE dr.id = t.id AND t.kits_attached = 0;

-- Verify before committing. Expect: 0 rows still open in scope.
SELECT count(*) AS still_open_in_scope
FROM device_requests dr
JOIN to_close t ON t.id = dr.id
WHERE dr.status NOT IN ('REQUEST_COMPLETED','REQUEST_DECLINED','REQUEST_CANCELLED');

-- Verify the six referees came back under the cap.
SELECT ro.name AS organisation, roc.full_name AS referee, roc.archived AS referee_archived,
       count(*) AS still_open
FROM device_requests dr
JOIN referring_organisation_contacts roc ON roc.id = dr.referring_organisation_contact_id
JOIN referring_organisations ro ON ro.id = roc.referring_organisation_id
WHERE dr.status NOT IN ('REQUEST_COMPLETED','REQUEST_DECLINED','REQUEST_CANCELLED')
GROUP BY 1,2,3 HAVING count(*) >= 3
ORDER BY count(*) DESC, 1;

-- Inspect the two results above, THEN choose one:
-- COMMIT;
ROLLBACK;
