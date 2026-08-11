package cta.app.services

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.sql.Timestamp
import java.time.Instant

@Service
class GdprDonorCleanupService(
    private val jdbcTemplate: JdbcTemplate,
) {
    /**
     * Runs the same retention routine the pg_cron job `gdpr-weekly-cleanup` invokes.
     *
     * ANONYMISES; it does not delete. gdpr.performgdprcleanup() overwrites donor/audit-trail
     * PII, blanks device-request details/client_ref/collection_contact_name (live and audit
     * trail), scrubs device_requests_notes.content, and scrubs referring_organisation_contacts
     * (live and audit trail) — see V26.08.11.1400__extend_gdpr_retention_scope.sql for the
     * full scope. Donor rows survive, so kits.donor_id — and with it every kit's donation
     * provenance — stays intact.
     *
     * NOT YET RUNNABLE — GdprDonorCleanup keeps this behind a flag that is seeded off.
     * gdpr.performgdprcleanup() is SECURITY INVOKER and owned by techaid_admin in UAT/prod;
     * the app roles have neither EXECUTE on it nor USAGE on the gdpr schema there. See issue
     * #62.
     *
     * Returns the routine's own summary string.
     */
    fun runRetentionCleanup(): String = jdbcTemplate.queryForObject("SELECT gdpr.performgdprcleanup()", String::class.java) ?: ""

    /**
     * When gdpr.performgdprcleanup() last recorded a run in gdpr_cleanup_runs, regardless of
     * whether pg_cron or this service triggered it. Null if the table is empty (no run has
     * ever been recorded) or absent (V26.08.11.1600 has not been applied yet).
     */
    fun lastRunAt(): Instant? = jdbcTemplate.queryForObject("SELECT max(ran_at) FROM gdpr_cleanup_runs", Timestamp::class.java)?.toInstant()
}
