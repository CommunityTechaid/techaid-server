-- Realign kit_sequence with max(kits.id) - techaid_uat ONLY.                   2026-10-07
--
-- WHY: the read-only audit (2026-10-07__readonly_sequence_vs_maxid_uat.sql) found UAT's
-- kit_sequence at last_value 9145 while max(kits.id) is 11324, with 5 existing kits at or above
-- 9146. As the sequence climbs, each of those would fail one createKit with a kits_pkey
-- duplicate-key error. The lag is old data history on UAT (141 kits sit below an older kit's
-- id), not the Boot 4.1 / Hibernate 7 upgrade: entity id mappings are unchanged since v3.3.0,
-- and IdGeneratorSequenceContractTest pins them. Production was audited the same day and
-- every sequence there matches max(id), so this script is NOT for prod.
--
-- WHAT: setval(kit_sequence, max(kits.id)) only if the sequence is behind; never moves it
-- backwards. Every other UAT sequence was already at or above its max(id)
-- (donor_sequence realigned itself when the failed insert consumed 1663).
--
-- Run as api_uat (sequence owner). Single transaction; prints before/after.

\set ON_ERROR_STOP on
BEGIN;

SELECT current_database() AS db, current_user AS run_as;
DO $$ BEGIN
    IF current_database() <> 'techaid_uat' THEN
        RAISE EXCEPTION 'refusing to run against %: this script is for techaid_uat only', current_database();
    END IF;
END $$;

SELECT 'before' AS at, last_value, is_called, (SELECT max(id) FROM kits) AS max_id FROM kit_sequence;

SELECT setval('kit_sequence', m.max_id)
FROM (SELECT max(id) AS max_id FROM kits) m, kit_sequence s
WHERE m.max_id > s.last_value;

SELECT 'after' AS at, last_value, is_called, (SELECT max(id) FROM kits) AS max_id,
       (last_value >= (SELECT max(id) FROM kits)) AS aligned
FROM kit_sequence;

COMMIT;
