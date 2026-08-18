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
     * Runs the retention routine the deleted pg_cron job `gdpr-weekly-cleanup` used to invoke.
     *
     * ANONYMISES; it does not delete. gdpr.performgdprcleanup() overwrites donor/audit-trail
     * PII, blanks device-request details/client_ref/collection_contact_name (live and audit
     * trail), scrubs device_requests_notes.content, and scrubs referring_organisation_contacts
     * (live and audit trail) — see V26.08.11.1400__extend_gdpr_retention_scope.sql for the
     * full scope. Donor rows survive, so kits.donor_id — and with it every kit's donation
     * provenance — stays intact.
     *
     * RUNNABLE AND RUNNING. techaid_admin granted api_uat/api_prod USAGE on the gdpr schema
     * and EXECUTE on gdpr.performgdprcleanup() (plus SELECT on the views its SECURITY INVOKER
     * body reads) at the 2026-08-12 cutover, which closed issue #62. Since 2026-08-18 this is
     * the only retention path there is: the feature flag is gone and the pg_cron job is
     * deleted.
     *
     * Returns the routine's own summary string.
     */
    fun runRetentionCleanup(): String = jdbcTemplate.queryForObject("SELECT gdpr.performgdprcleanup()", String::class.java) ?: ""

    /**
     * When gdpr.performgdprcleanup() last recorded a run in gdpr_cleanup_runs. Rows before
     * 2026-08-12 came from pg_cron or the one-off backfill; everything since is this service.
     * Null if the table is empty (no run has ever been recorded) or absent (V26.08.11.1600 has
     * not been applied yet).
     */
    fun lastRunAt(): Instant? = jdbcTemplate.queryForObject("SELECT max(ran_at) FROM gdpr_cleanup_runs", Timestamp::class.java)?.toInstant()
}
