package cta.app.services

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

@Service
class GdprDonorCleanupService(
    private val jdbcTemplate: JdbcTemplate,
) {
    // The retention policy lives in the Flyway-managed gdpr schema (V22.20.13.1517__gdpr.sql):
    // the view selects eligible donors, a BEFORE DELETE trigger archives a PII-free trace, and
    // FK ON DELETE rules detach kits — so the cleanup action is just the delete itself.
    fun deleteExpiredDonors(): Int = jdbcTemplate.update("DELETE FROM donors WHERE id IN (SELECT id FROM gdpr.donors_to_delete)")
}
