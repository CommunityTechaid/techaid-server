-- GDPR retention verification - the standing check.
--
-- Answers "is there any record past retention still holding personal data?" INDEPENDENTLY of
-- whether the retention job reported success. This exists because `succeeded` is not evidence:
-- the production pg_cron job reported success every Saturday for three months while 7,460
-- audit rows sat untouched (#126, #127). It earned its keep again on 2026-08-12, when the
-- first in-app production run failed on a missing grant while eleven of twelve categories
-- would have reported clean.
--
-- Read-only. Safe to run against any environment, any time. Needs SELECT on the public
-- tables only - it deliberately does NOT read gdpr.donors_to_archive, so it can be run by a
-- role that has no access to the gdpr schema, and so it checks the policy independently of
-- the view the job itself trusts.
--
-- EVERY ROW IN THE FIRST RESULT SET MUST READ 0.
-- A non-zero anywhere means retention is not doing what it claims.
--
-- ---------------------------------------------------------------------------------------
-- Thresholds and sentinels are mirrored from gdpr.performgdprcleanup() as shipped in
-- V26.08.12.1100__gdpr_retention_policy_corrections.sql, with the referee scope as corrected
-- by V26.08.13.1200__gdpr_fix_referee_retention_activity_scope.sql. Each check below is the
-- inverse of one UPDATE in that function. If you change the function, change the matching
-- check here.
--
--   donors (+ donors_audit_trail)          12 months   'Donor - Erased due to GDPR policy'
--   kits.coordinates                       12 months   NULL (jsonb - no sentinel)
--   device_requests.details                26 weeks    'RECORD DELETED BY SYSTEM - GDPR'
--   device_requests.client_ref             52 weeks    'WIPED - GDPR'
--   device_requests.collection_contact_name 52 weeks   'WIPED - GDPR'
--   device_requests_notes.content          52 weeks    'Note content deleted due to GDPR policy'
--   referring_organisation_contacts        12 months   'Contact - Erased due to GDPR policy'
--                                          measured from LAST ACTIVITY, not updated_at
--   all three audit trails                 live row past threshold OR live row already wiped
--
-- NOTE ON device_requests_notes: 52 weeks is CORRECT. The team's retention spreadsheet
-- ("GDPR data removal review 26-08-11.xlsx", sheet "Requests", row 5) says 12 months. The
-- 26-week figure written in #98, #96, #126 and PR #130 predates the spreadsheet and was
-- never reconciled to it. Do not "fix" this to 26 weeks - see note 0 of V26.08.12.1100.
--
-- NOTE ON referring contacts: the clock is LAST ACTIVITY, not the contact row's updated_at.
-- referring_organisation_contacts.updated_at only moves when the contact record itself is
-- edited, so a referee who has referred continuously for years but whose own details have
-- not been retyped looks stale on every run. V26.08.13.1200 corrected the function to take
-- greatest(c.updated_at, max(device-request activity), c.created_at) instead, mirroring
-- gdpr.donors_to_archive. Checks 11 and 12 tracked the old naive predicate until 2026-08-18
-- and reported 17 live contacts + 7 audit rows as outstanding when the function was right to
-- spare every one of them - the same 17 the 2026-08-13 restore put back. A verification
-- script that lags the function manufactures false breaches; keep them in step.
-- ---------------------------------------------------------------------------------------

\pset pager off
SET default_transaction_read_only = on;

-- Mirrors gdpr.donors_to_archive as corrected by V26.08.12.1100: no donor_parents join, no
-- lead-contact filter, no business exclusions. A donor is due once 12 months have passed
-- since their most recent kit, or since the donor row itself if they never had one.
WITH donor_backlog AS (
    SELECT d.id
      FROM donors d
      LEFT JOIN kits k ON k.donor_id = d.id
     WHERE d.name <> 'Donor - Erased due to GDPR policy'
     GROUP BY d.id, d.created_at, d.name
    HAVING COALESCE(max(k.created_at), d.created_at) <= CURRENT_DATE - INTERVAL '12 months'
),

-- Mirrors gdpr.referring_contacts_to_archive as corrected by V26.08.13.1200. Deliberately
-- recomputed from public tables rather than selecting from that view, so this script stays
-- runnable by a role with no access to the gdpr schema and stays an INDEPENDENT check of the
-- policy rather than a restatement of the view the job itself trusts.
--
-- No full_name filter here, matching the view: check 11 adds it for live rows, check 12 needs
-- the unfiltered set because the function's audit UPDATE fires on "in the view OR already
-- erased".
referee_backlog AS (
    SELECT c.id
      FROM referring_organisation_contacts c
      LEFT JOIN device_requests d ON d.referring_organisation_contact_id = c.id
     GROUP BY c.id, c.updated_at, c.created_at
    HAVING greatest(
               c.updated_at,
               COALESCE(max(greatest(d.created_at, d.updated_at)), c.created_at)
           ) <= CURRENT_DATE - INTERVAL '12 months'
)
SELECT * FROM (
    SELECT 1 AS ord, 'donors past 12m still holding personal data' AS check, count(*) AS outstanding
      FROM donor_backlog

    UNION ALL SELECT 2, 'donors_audit_trail rows behind those donors',
        (SELECT count(*) FROM donors_audit_trail a
          WHERE a.id IN (SELECT id FROM donor_backlog)
            AND a.name <> 'Donor - Erased due to GDPR policy')

    UNION ALL SELECT 3, 'kits.coordinates past 12m or on an erased donor',
        (SELECT count(*) FROM kits k
          WHERE k.coordinates IS NOT NULL
            AND (k.created_at <= CURRENT_DATE - INTERVAL '12 months'
                 OR EXISTS (SELECT 1 FROM donors d
                             WHERE d.id = k.donor_id
                               AND d.name = 'Donor - Erased due to GDPR policy')))

    UNION ALL SELECT 4, 'device_requests.details past 26 weeks',
        (SELECT count(*) FROM device_requests
          WHERE updated_at <= CURRENT_DATE - INTERVAL '26 weeks'
            AND details <> 'RECORD DELETED BY SYSTEM - GDPR')

    UNION ALL SELECT 5, 'device_requests.client_ref past 52 weeks',
        (SELECT count(*) FROM device_requests
          WHERE updated_at <= CURRENT_DATE - INTERVAL '52 weeks'
            AND client_ref <> 'WIPED - GDPR')

    UNION ALL SELECT 6, 'device_requests.collection_contact_name past 52 weeks',
        (SELECT count(*) FROM device_requests
          WHERE updated_at <= CURRENT_DATE - INTERVAL '52 weeks'
            AND collection_contact_name IS NOT NULL
            AND collection_contact_name <> 'WIPED - GDPR')

    -- The three audit checks carry the OR branch from correction 4. Gating on the live row's
    -- updated_at alone lets an audit row hide behind a request that was edited after being
    -- scrubbed - the timestamp moves back inside the window and the history becomes
    -- permanently unreachable.
    UNION ALL SELECT 7, 'AUDIT details where live row is wiped or past 26 weeks',
        (SELECT count(*) FROM device_requests_audit_trail a
           JOIN device_requests l ON l.id = a.id
          WHERE (l.updated_at <= CURRENT_DATE - INTERVAL '26 weeks'
                 OR l.details = 'RECORD DELETED BY SYSTEM - GDPR')
            AND a.details <> 'RECORD DELETED BY SYSTEM - GDPR')

    UNION ALL SELECT 8, 'AUDIT client_ref where live row is wiped or past 52 weeks',
        (SELECT count(*) FROM device_requests_audit_trail a
           JOIN device_requests l ON l.id = a.id
          WHERE (l.updated_at <= CURRENT_DATE - INTERVAL '52 weeks'
                 OR l.client_ref = 'WIPED - GDPR')
            AND a.client_ref <> 'WIPED - GDPR')

    UNION ALL SELECT 9, 'AUDIT collection_contact_name where live row is wiped or past 52 weeks',
        (SELECT count(*) FROM device_requests_audit_trail a
           JOIN device_requests l ON l.id = a.id
          WHERE (l.updated_at <= CURRENT_DATE - INTERVAL '52 weeks'
                 OR l.collection_contact_name = 'WIPED - GDPR')
            AND a.collection_contact_name IS NOT NULL
            AND a.collection_contact_name <> 'WIPED - GDPR')

    UNION ALL SELECT 10, 'device_requests_notes.content past 52 weeks',
        (SELECT count(*) FROM device_requests_notes
          WHERE updated_at <= CURRENT_DATE - INTERVAL '52 weeks'
            AND content <> 'Note content deleted due to GDPR policy')

    UNION ALL SELECT 11, 'referring contacts with no activity for 12 months',
        (SELECT count(*) FROM referring_organisation_contacts c
          WHERE c.id IN (SELECT id FROM referee_backlog)
            AND c.full_name <> 'Contact - Erased due to GDPR policy')

    UNION ALL SELECT 12, 'AUDIT referring contacts where live row is wiped or past 12 months',
        (SELECT count(*) FROM referring_organisation_contacts_audit_trail cat
           JOIN referring_organisation_contacts c ON c.id = cat.id
          WHERE (c.id IN (SELECT id FROM referee_backlog)
                 OR c.full_name = 'Contact - Erased due to GDPR policy')
            AND cat.full_name <> 'Contact - Erased due to GDPR policy')

    -- Special-category indicators surviving anywhere in scope. Indicative, not a
    -- determination - a match is a prompt to look, not proof of a breach. Thresholds match
    -- the field's own rule above.
    UNION ALL SELECT 13, 'SPECIAL CATEGORY: indicators left in device_requests.details',
        (SELECT count(*) FROM device_requests
          WHERE updated_at <= CURRENT_DATE - INTERVAL '26 weeks'
            AND details ~* '(disab|mental health|autis|adhd|asylum|refugee|nrpf|no recourse|domestic abuse|domestic violence|safeguard|probation|prison)')

    UNION ALL SELECT 14, 'SPECIAL CATEGORY: indicators left in the device request audit trail',
        (SELECT count(*) FROM device_requests_audit_trail a
           JOIN device_requests l ON l.id = a.id
          WHERE (l.updated_at <= CURRENT_DATE - INTERVAL '26 weeks'
                 OR l.details = 'RECORD DELETED BY SYSTEM - GDPR')
            AND a.details ~* '(disab|mental health|autis|adhd|asylum|refugee|nrpf|no recourse|domestic abuse|domestic violence|safeguard|probation|prison)')

    UNION ALL SELECT 15, 'SPECIAL CATEGORY: indicators left in device_requests_notes',
        (SELECT count(*) FROM device_requests_notes
          WHERE updated_at <= CURRENT_DATE - INTERVAL '52 weeks'
            AND content ~* '(disab|mental health|autis|adhd|asylum|refugee|nrpf|no recourse|domestic abuse|domestic violence|safeguard|probation|prison)')
) x ORDER BY ord;


-- ---------------------------------------------------------------------------------------
-- INFORMATIONAL - not part of the zero contract.
--
-- Every threshold above is keyed to updated_at. A row with a NULL updated_at is therefore
-- invisible to the retention job forever: no predicate can ever match it. These counts are
-- the size of that blind spot. They are reported separately because a non-zero here is a
-- coverage question for the maintainer, not a failure of the job to do what it was told.
-- ---------------------------------------------------------------------------------------
SELECT 'device_requests with NULL updated_at' AS blind_spot, count(*) AS rows
  FROM device_requests WHERE updated_at IS NULL
UNION ALL
SELECT 'device_requests_notes with NULL updated_at', count(*)
  FROM device_requests_notes WHERE updated_at IS NULL
UNION ALL
SELECT 'referring_organisation_contacts with NULL updated_at', count(*)
  FROM referring_organisation_contacts WHERE updated_at IS NULL;


-- ---------------------------------------------------------------------------------------
-- The job's own record, for cross-reference. The counts above are measured from the data;
-- these are what the job SAID it did. They should tell the same story - and when they do
-- not, the data wins.
--
-- ORDER BY ran_at, NOT id. The 2026-08-12 backfill inserted 13 historical runs (2026-05-16
-- to 2026-08-08) recovered from Azure log history, so they carry ids ABOVE the live run that
-- preceded them. Ordering by id here showed the oldest runs while claiming to show the
-- latest - caught by the first production run of this query.
--
-- THE source COLUMN MATTERS. Backfilled rows were reconstructed from the old function's
-- RAISE LOG line, which only ever reported two categories. Every other count on those rows
-- is 0 because the column is NOT NULL and the value was never recorded - NOT because
-- nothing was erased. Only details and client_ref are real on a backfilled row. Do not read
-- the zeros as measurements, and do not sum these columns across the whole table for a
-- compliance total: for backfilled rows the honest value is "unknown".
-- ---------------------------------------------------------------------------------------
SELECT id, ran_at,
       CASE WHEN summary LIKE '%backfilled from Azure PostgreSQLLogs%'
            THEN 'backfill: only details/client_ref real'
            ELSE 'live: all counts real' END       AS source,
       device_request_details_count                AS details,
       device_request_clientref_count              AS client_ref,
       donor_count                                 AS donors,
       device_request_notes_count                  AS notes,
       referring_contact_count                     AS ref_contacts,
       kit_coordinates_count                       AS kit_coords
  FROM gdpr_cleanup_runs
 ORDER BY ran_at DESC
 LIMIT 8;
