package cta.app.services

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

@Service
class GdprDonorCleanupService(
    private val jdbcTemplate: JdbcTemplate,
) {
    /**
     * Runs the same retention routine the pg_cron job `gdpr-weekly-cleanup` invokes.
     *
     * ANONYMISES; it does not delete. gdpr.performgdprcleanup() overwrites name/email/phone/
     * post code/coordinates/referral on expired donors and their audit rows, and blanks the
     * corresponding device-request fields. The donor row survives, so kits.donor_id — and with
     * it every kit's donation provenance — stays intact.
     *
     * An earlier draft of this service hard-deleted instead
     * (DELETE FROM donors WHERE id IN (SELECT id FROM gdpr.donors_to_delete)). That would have
     * been a change of retention policy, not a port of it: kits.donor_id is ON DELETE SET NULL,
     * so every historical kit from an expired donor would have lost its link permanently, and
     * gdpr.donors_archive keeps only donor_id/timestamps/referral, so it could not be
     * reconstructed from the kit side.
     *
     * NOT YET RUNNABLE — GdprDonorCleanup keeps this behind a flag that is seeded off. Two
     * prerequisites, both discovered 2026-07-21:
     *   1. gdpr.performgdprcleanup() exists only in techaid_uat and techaid_prod. It is in no
     *      migration in this repo, so it is absent from every fresh and test database.
     *   2. It is SECURITY INVOKER and owned by techaid_admin, and the application roles have
     *      neither EXECUTE on it nor USAGE on the gdpr schema.
     *
     * Returns the routine's own summary string.
     */
    fun runRetentionCleanup(): String = jdbcTemplate.queryForObject("SELECT gdpr.performgdprcleanup()", String::class.java) ?: ""
}
