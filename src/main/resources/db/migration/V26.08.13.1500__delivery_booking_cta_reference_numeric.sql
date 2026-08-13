-- =====================================================================================
-- delivery_bookings.cta_reference: varchar(255) -> bigint
--
-- WHY
-- ---
-- cta_reference has always *meant* a device_requests.id — the public booking form asks for
-- "Your Community TechAid ID reference number" and offers "e.g. 4298" — but it was stored as
-- unconstrained free text. Every consumer therefore had to parse it defensively
-- (`.toLongOrNull()`) and silently do nothing when the parse failed, so a mistyped reference
-- produced a booking that linked to no request, with no error and no log line. Issue #133.
--
-- WHY IT IS SAFE TO DO THIS NOW
-- -----------------------------
-- Measured 2026-08-13 (db/admin/delivery_cta_reference_measurement.sql):
--   * techaid_prod: 0 rows in delivery_bookings. The public booking route is gated by the
--     'delivery-booking' feature flag, which is still false and has never been toggled
--     (updated_at is unchanged since the flag was seeded on 2026-07-27), so the form has
--     never been reachable in production. There is no production data to convert.
--   * techaid_uat:  15 rows, of which 13 are numeric and 2 are hand-typed test junk.
--
-- The DELETE below therefore removes nothing in production and two test rows in UAT. It is
-- written as a general predicate rather than hard-coded ids so it is correct in whatever
-- state a given environment is in, including a developer's local database.
--
-- The length guard keeps a pathologically long digit string (the old column allowed 255
-- characters) from overflowing bigint and failing the ALTER.
-- =====================================================================================

DELETE FROM delivery_bookings
 WHERE cta_reference IS NULL
    OR btrim(cta_reference) !~ '^[0-9]+$'
    OR length(btrim(cta_reference)) > 18;

ALTER TABLE delivery_bookings
    ALTER COLUMN cta_reference TYPE bigint USING btrim(cta_reference)::bigint;
