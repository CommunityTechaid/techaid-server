-- Read-only export of the long-open device requests that predate the borough field.
--
-- Context: 50 requests raised between Oct 2023 and May 2025 are still sitting in
-- PROCESSING_COLLECTION_DELIVERY_ARRANGED. They matter because the per-referee cap is moving from
-- one global number to a per-borough-group count (see the borough-availability-rules flag), and a
-- request with no borough recorded stops counting once it does. Six referees are held at their cap
-- purely by these.
--
-- Note there is NO `archived` column on device_requests — status is the only lifecycle marker a
-- request has. `archived` exists on referring_organisations and referring_organisation_contacts
-- only, which is why both are reported here.
--
-- SELECT-only. Safe to run against production. Writes two CSVs to the psql client's working
-- directory via \copy.
--
-- Usage (Git Bash, from a scratch dir mounted at /w in the container):
--   docker run --rm -e PGPASSWORD="$PW" -v "$SCRATCH":/w postgres:17-alpine \
--     psql "host=techaid-pg-svr.postgres.database.azure.com dbname=techaid_prod user=api_prod sslmode=require" \
--     -v outdir=/w -f /w/stale_open_requests_export.sql

\set ON_ERROR_STOP on
\pset format aligned
\pset border 2

-- The population: open, no borough recorded, raised before the borough field existed.
CREATE TEMP VIEW stale_requests AS
SELECT dr.id,
       dr.client_ref,
       dr.status,
       dr.created_at,
       dr.updated_at,
       dr.collection_date,
       dr.collection_method,
       dr.is_prepped,
       roc.id   AS contact_id,
       roc.full_name AS referee,
       roc.email AS referee_email,
       roc.phone_number AS referee_phone,
       roc.archived AS referee_archived,
       ro.id    AS organisation_id,
       ro.name  AS organisation,
       ro.archived AS organisation_archived
FROM device_requests dr
JOIN referring_organisation_contacts roc ON roc.id = dr.referring_organisation_contact_id
JOIN referring_organisations ro ON ro.id = roc.referring_organisation_id
WHERE dr.status NOT IN ('REQUEST_COMPLETED','REQUEST_DECLINED','REQUEST_CANCELLED')
  AND (dr.borough IS NULL OR dr.borough = '');

\echo '=== 1. the requests, with kits actually attached and note activity ==='
SELECT s.id, s.client_ref, s.organisation, s.referee,
       s.created_at::date AS raised,
       s.updated_at::date AS last_touched,
       s.collection_date::date AS collection_date,

       (SELECT count(*) FROM kits k WHERE k.device_request_id = s.id) AS kits_attached,
       (SELECT count(*) FROM device_requests_notes n WHERE n.device_request_id = s.id) AS notes,
       s.referee_archived, s.organisation_archived
FROM stale_requests s
ORDER BY s.organisation, s.referee, s.created_at;

\echo '=== 2. distinct referees behind them ==='
SELECT s.organisation, s.organisation_archived, s.referee, s.referee_email,
       s.referee_archived,
       count(*) AS stale_requests,
       min(s.created_at)::date AS oldest,
       max(s.created_at)::date AS newest
FROM stale_requests s
GROUP BY 1,2,3,4,5
ORDER BY count(*) DESC, s.organisation, s.referee;

\echo '=== 3. do these requests have devices attached? ==='
SELECT CASE WHEN kits_attached > 0 THEN 'kits attached' ELSE 'no kits attached' END AS bucket,
       count(*) AS requests
FROM (
  SELECT s.id, (SELECT count(*) FROM kits k WHERE k.device_request_id = s.id) AS kits_attached
  FROM stale_requests s
) t
GROUP BY 1 ORDER BY 2 DESC;

\echo '=== 4. how long since anything happened on them ==='
SELECT date_trunc('year', updated_at)::date AS last_touched_year, count(*)
FROM stale_requests GROUP BY 1 ORDER BY 1;

\echo '=== 5. archived orgs / referees among them ==='
SELECT s.organisation_archived, s.referee_archived, count(DISTINCT s.contact_id) AS referees, count(*) AS requests
FROM stale_requests s GROUP BY 1,2 ORDER BY 1,2;

-- CSV exports -----------------------------------------------------------------------------------

\copy (SELECT s.id AS request_id, s.client_ref, s.status, s.organisation, s.organisation_id, s.organisation_archived, s.referee, s.referee_email, s.referee_phone, s.contact_id, s.referee_archived, s.created_at, s.updated_at, s.collection_date, s.collection_method, s.is_prepped, (SELECT count(*) FROM kits k WHERE k.device_request_id = s.id) AS kits_attached, (SELECT count(*) FROM device_requests_notes n WHERE n.device_request_id = s.id) AS notes FROM stale_requests s ORDER BY s.organisation, s.referee, s.created_at) TO '/w/stale_open_requests.csv' WITH CSV HEADER

\copy (SELECT s.organisation, s.organisation_id, s.organisation_archived, s.referee, s.contact_id, s.referee_email, s.referee_phone, s.referee_archived, count(*) AS stale_requests, min(s.created_at)::date AS oldest_stale, max(s.created_at)::date AS newest_stale FROM stale_requests s GROUP BY 1,2,3,4,5,6,7,8 ORDER BY count(*) DESC, s.organisation, s.referee) TO '/w/stale_open_request_referees.csv' WITH CSV HEADER

\echo '=== CSVs written ==='
