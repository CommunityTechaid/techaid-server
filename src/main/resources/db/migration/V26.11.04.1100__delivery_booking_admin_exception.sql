-- Admin exception bookings (#73). An admin (app:admin) can book any day and any window,
-- whatever its capacity, e.g. when a delivery failed and the referee calls to rebook a full day.
--
-- admin_exception marks those rows. They are NOT counted against a window's capacity, so they
-- never reduce what the public page offers (policy, Tony 2026-11-04). booked_by and
-- exception_reason record who made the exception and why, so the team can see how often it happens.
--
-- Column names/types mirror what Hibernate's implicit naming derives from DeliveryBooking in
-- DeliveryModels.kt so ddl-auto=validate passes (see SchemaValidationTest).

alter table delivery_bookings add column if not exists admin_exception boolean not null default false;
alter table delivery_bookings add column if not exists booked_by varchar(255);
alter table delivery_bookings add column if not exists exception_reason TEXT;
