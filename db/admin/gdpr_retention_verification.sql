-- GDPR retention verification — the standing check.
--
-- Answers "is there any record past retention still holding personal data?" INDEPENDENTLY of
-- whether the retention job reported success. This exists because `succeeded` is not evidence:
-- the production job reported success every Saturday for three months while 7,460 audit rows
-- sat untouched (#126, #127).
--
-- Read-only. Safe to run against any environment, any time.
-- Every row should read 0. A non-zero anywhere means retention is not doing what it claims.
--
-- Thresholds are the ones decided on 2026-08-11 (#98):
--   donors                             12 months since the most recent associated device
--   device_requests.details            26 weeks
--   device_requests.client_ref         52 weeks
--   device_requests.collection_contact 52 weeks
--   device_requests_notes.content      26 weeks
--   audit trails                       match their live row

\pset pager off
SET default_transaction_read_only = on;

WITH donor_backlog AS (
    SELECT d.id
    FROM donors d
    LEFT JOIN kits k ON k.donor_id = d.id
    WHERE d.name <> 'Donor - Erased due to GDPR policy'
    GROUP BY d.id, d.created_at, d.name
    HAVING COALESCE(max(k.created_at), d.created_at) <= CURRENT_DATE - INTERVAL '1 year'
)
SELECT * FROM (
    SELECT 1 AS ord, 'donors past 12m still holding personal data' AS check, count(*) AS outstanding FROM donor_backlog

    UNION ALL SELECT 2, 'donors_audit_trail rows for those donors',
        (SELECT count(*) FROM donors_audit_trail a
          WHERE a.id IN (SELECT id FROM donor_backlog)
            AND a.name <> 'Donor - Erased due to GDPR policy')

    UNION ALL SELECT 3, 'device_requests.details past 26 weeks',
        (SELECT count(*) FROM device_requests
          WHERE updated_at <= CURRENT_DATE - INTERVAL '26 weeks'
            AND details IS NOT NULL AND details <> ''
            AND details <> 'RECORD DELETED BY SYSTEM - GDPR')

    UNION ALL SELECT 4, 'device_requests.client_ref past 52 weeks',
        (SELECT count(*) FROM device_requests
          WHERE updated_at <= CURRENT_DATE - INTERVAL '52 weeks'
            AND client_ref IS NOT NULL AND client_ref <> ''
            AND client_ref <> 'WIPED - GDPR')

    UNION ALL SELECT 5, 'device_requests.collection_contact_name past 52 weeks',
        (SELECT count(*) FROM device_requests
          WHERE updated_at <= CURRENT_DATE - INTERVAL '52 weeks'
            AND collection_contact_name IS NOT NULL AND collection_contact_name <> '')

    UNION ALL SELECT 6, 'device_requests_notes.content past 26 weeks',
        (SELECT count(*) FROM device_requests_notes
          WHERE COALESCE(updated_at, created_at) <= CURRENT_DATE - INTERVAL '26 weeks'
            AND content IS NOT NULL AND content <> ''
            AND content <> 'RECORD DELETED BY SYSTEM - GDPR')

    UNION ALL SELECT 7, 'AUDIT: details retained where live row is wiped or past 26 weeks',
        (SELECT count(*) FROM device_requests_audit_trail a
           JOIN device_requests l ON l.id = a.id
          WHERE (l.details = 'RECORD DELETED BY SYSTEM - GDPR'
                 OR l.updated_at <= CURRENT_DATE - INTERVAL '26 weeks')
            AND a.details IS NOT NULL AND a.details <> ''
            AND a.details <> 'RECORD DELETED BY SYSTEM - GDPR')

    UNION ALL SELECT 8, 'AUDIT: client_ref retained where live row is wiped or past 52 weeks',
        (SELECT count(*) FROM device_requests_audit_trail a
           JOIN device_requests l ON l.id = a.id
          WHERE (l.client_ref = 'WIPED - GDPR'
                 OR l.updated_at <= CURRENT_DATE - INTERVAL '52 weeks')
            AND a.client_ref IS NOT NULL AND a.client_ref <> ''
            AND a.client_ref <> 'WIPED - GDPR')

    UNION ALL SELECT 9, 'AUDIT: collection_contact_name retained past 52 weeks',
        (SELECT count(*) FROM device_requests_audit_trail a
           JOIN device_requests l ON l.id = a.id
          WHERE l.updated_at <= CURRENT_DATE - INTERVAL '52 weeks'
            AND a.collection_contact_name IS NOT NULL AND a.collection_contact_name <> '')

    -- Special-category indicators surviving anywhere in scope. Indicative, not a determination.
    UNION ALL SELECT 10, 'SPECIAL CATEGORY: indicators left in device_requests.details',
        (SELECT count(*) FROM device_requests
          WHERE updated_at <= CURRENT_DATE - INTERVAL '26 weeks'
            AND details ~* '(disab|mental health|autis|adhd|asylum|refugee|nrpf|no recourse|domestic abuse|domestic violence|safeguard|probation|prison)')

    UNION ALL SELECT 11, 'SPECIAL CATEGORY: indicators left in the audit trail',
        (SELECT count(*) FROM device_requests_audit_trail a
           JOIN device_requests l ON l.id = a.id
          WHERE (l.details = 'RECORD DELETED BY SYSTEM - GDPR'
                 OR l.updated_at <= CURRENT_DATE - INTERVAL '26 weeks')
            AND a.details ~* '(disab|mental health|autis|adhd|asylum|refugee|nrpf|no recourse|domestic abuse|domestic violence|safeguard|probation|prison)')

    UNION ALL SELECT 12, 'SPECIAL CATEGORY: indicators left in device_requests_notes',
        (SELECT count(*) FROM device_requests_notes
          WHERE COALESCE(updated_at, created_at) <= CURRENT_DATE - INTERVAL '26 weeks'
            AND content ~* '(disab|mental health|autis|adhd|asylum|refugee|nrpf|no recourse|domestic abuse|domestic violence|safeguard|probation|prison)')
) x ORDER BY ord;
