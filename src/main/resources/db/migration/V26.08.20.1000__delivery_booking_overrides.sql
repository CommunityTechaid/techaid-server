-- Staff-granted exemption from the one-booking-per-CTA-reference rule (UAT feedback row 7).
--
-- The duplicate check moved from "blocks only an upcoming booking" to "blocks any booking, past
-- or future, unless a staff override is unconsumed" (DeliveryMutations). This table is the
-- override: one unconsumed row lets exactly one extra booking through, then is stamped consumed
-- so the exemption cannot be reused.
--
-- Column names/types mirror what Hibernate's implicit naming derives from the entity in
-- DeliveryModels.kt so ddl-auto=validate passes (see SchemaValidationTest).

create sequence if not exists delivery_booking_overrides_sequence start with 1 increment by 1;

create table if not exists delivery_booking_overrides (
    id bigint not null,
    cta_reference bigint not null,
    created_at timestamp(6) with time zone,
    created_by varchar(255),
    note TEXT,
    consumed_at timestamp(6) with time zone,
    primary key (id)
);

create index if not exists ix_delivery_booking_overrides_cta_reference
    on delivery_booking_overrides (cta_reference);
