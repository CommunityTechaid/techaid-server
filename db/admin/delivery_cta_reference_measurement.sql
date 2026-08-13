-- =====================================================================================
-- Measure what people actually type into the public delivery booking's ctaReference.
--
-- WHY THIS EXISTS
-- ---------------
-- Issue #133 proposes making ctaReference numeric end-to-end, with a "minimal first
-- step" of hard-rejecting non-numeric input at the mutation layer. That is only low
-- risk if bookers are in fact typing a bare DeviceRequest.id today. Two hints they may
-- not be: DeliveryBookingRepository.existsUpcomingByNormalizedCtaReference normalises
-- with lower(trim(...)) — case-insensitivity only matters if letters were expected —
-- and the field was specced as 64 characters of free text.
--
-- If referrers type "CTA-1234" or similar, a hard reject converts a silent mislink
-- (issue #133's complaint) into a hard failure on a public-facing form, which is worse.
-- This script settles that with data instead of a guess.
--
-- It also measures the failure rate issue #155 item 4 proposes to log: how many
-- bookings carry a reference that does not resolve to a live device request.
--
-- READ-ONLY. No DDL, no DML. Safe to run against production.
--
-- Values are masked (digits -> 9, letters -> A) so the output shows the *shape* of what
-- people type without dumping free-text that may contain names or other personal data.
--
-- HOW TO RUN (Git Bash, from the repo root; see techaid-database-operations skill)
-- ---------------------------------------------------------------------------------
--   export MSYS_NO_PATHCONV=1
--   PW=$(az containerapp secret show -g tada-2026 -n api-production \
--          --secret-name datasource-password --query value -o tsv)
--   docker run --rm -e PGPASSWORD="$PW" -v "$(pwd)/db/admin":/w postgres:17-alpine \
--     psql "host=techaid-pg-svr.postgres.database.azure.com dbname=techaid_prod \
--           user=api_prod sslmode=require" -f /w/delivery_cta_reference_measurement.sql
-- =====================================================================================

\echo '=== 0. Which database am I actually on? ==='
SELECT current_database(), current_user, now();

\echo ''
\echo '=== 1. Does the table exist, and is the feature live here at all? ==='
-- A zero row count means "the public form was never used in this environment", NOT
-- "no bad references exist". Distinguish the two before drawing any conclusion.
SELECT
    count(*)                                   AS total_bookings,
    min(created_at)::date                      AS first_booking,
    max(created_at)::date                      AS last_booking,
    count(*) FILTER (WHERE created_at > now() - interval '30 days') AS last_30_days
FROM delivery_bookings;

\echo ''
\echo '=== 2. Numeric vs non-numeric (the #133 decision) ==='
SELECT
    count(*)                                                              AS total,
    count(*) FILTER (WHERE trim(cta_reference) ~ '^[0-9]+$')              AS digits_only,
    count(*) FILTER (WHERE trim(cta_reference) !~ '^[0-9]+$')             AS would_be_rejected,
    round(
        100.0 * count(*) FILTER (WHERE trim(cta_reference) !~ '^[0-9]+$')
        / nullif(count(*), 0),
    1)                                                                    AS pct_rejected
FROM delivery_bookings;

\echo ''
\echo '=== 3. Shape of what people type (masked: digit->9, letter->A) ==='
SELECT
    regexp_replace(
        regexp_replace(trim(cta_reference), '[0-9]', '9', 'g'),
        '[A-Za-z]', 'A', 'g'
    )                                          AS masked_shape,
    length(trim(cta_reference))                AS len,
    count(*)                                   AS n
FROM delivery_bookings
GROUP BY 1, 2
ORDER BY n DESC, len;

\echo ''
\echo '=== 4. Do the numeric ones actually resolve to a device request? (the #155 rate) ==='
-- This is the number that DeliveryService.markCollectionDeliveryArranged silently
-- swallows today via its two `?: return` exits.
SELECT
    count(*)                                                       AS numeric_refs,
    count(dr.id)                                                   AS resolved_to_request,
    count(*) - count(dr.id)                                        AS numeric_but_no_such_request
FROM delivery_bookings b
LEFT JOIN device_requests dr
       ON dr.id = trim(b.cta_reference)::bigint
WHERE trim(b.cta_reference) ~ '^[0-9]+$'
  -- guard against a pathologically long digit string overflowing bigint
  AND length(trim(b.cta_reference)) <= 18;

\echo ''
\echo '=== 5. For refs that DID resolve: what status is the request in now? ==='
-- PROCESSING_COLLECTION_DELIVERY_ARRANGED means the #132 forwarding worked (or staff
-- set it by hand). Anything else on a booked request is worth an eyebrow.
SELECT
    dr.status,
    count(*)                                                       AS n,
    count(*) FILTER (WHERE dr.collection_method IS DISTINCT FROM 'DELIVERY') AS method_not_delivery,
    count(*) FILTER (WHERE dr.collection_date IS NULL)             AS collection_date_null
FROM delivery_bookings b
JOIN device_requests dr
  ON dr.id = trim(b.cta_reference)::bigint
WHERE trim(b.cta_reference) ~ '^[0-9]+$'
  AND length(trim(b.cta_reference)) <= 18
GROUP BY dr.status
ORDER BY n DESC;

\echo ''
\echo '=== 6. Sanity check: what range are real device request ids in? ==='
-- If bookers are typing ids, the numeric values in section 3 should land in this range.
-- Numeric-but-out-of-range values mean they are typing some *other* number.
SELECT min(id) AS min_id, max(id) AS max_id, count(*) AS total_requests FROM device_requests;
