package cta.app.schedulingtasks

import cta.app.FeatureFlagRepository
import cta.app.services.GdprDonorCleanupService
import mu.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

/**
 * In-app GDPR retention cleanup, intended to eventually supersede the pg_cron job
 * `gdpr-weekly-cleanup` — server-side state that is silently lost on any DB restore.
 *
 * PARKED: the flag is seeded OFF and must stay off until the prerequisites documented on
 * [GdprDonorCleanupService.runRetentionCleanup] are resolved. While it is off, pg_cron remains
 * the only thing performing retention, which is the correct state — this must never be the
 * moment retention silently stops happening. Do not unschedule the pg_cron job until this has
 * run successfully in production at least once.
 */
@Component
class GdprDonorCleanup(
    private val gdprDonorCleanupService: GdprDonorCleanupService,
    private val featureFlags: FeatureFlagRepository,
) {
    // Must fire inside the KEDA business-hours window (Mon-Fri 08:00-20:00 London): the app
    // scales to zero outside it, so an off-hours cron would never run in production.
    @Scheduled(cron = "0 30 9 * * MON", zone = "Europe/London")
    fun runRetentionCleanup() {
        if (!isEnabled()) {
            logger.debug { "GDPR in-app cleanup is off; pg_cron gdpr-weekly-cleanup still owns retention" }
            return
        }

        logger.info("Started GDPR retention cleanup")
        try {
            val result = gdprDonorCleanupService.runRetentionCleanup()
            logger.info("GDPR retention cleanup finished: $result")
        } catch (e: Exception) {
            // Loud, and deliberately not rethrown: a scheduled retention job that fails quietly
            // is a compliance problem, but propagating only kills this one execution anyway.
            // The message names the fallback so whoever reads it knows retention is still covered.
            logger.error(e) { "GDPR retention cleanup FAILED - pg_cron gdpr-weekly-cleanup must remain scheduled" }
        }
    }

    // Fail closed: any problem reading the flag leaves retention with pg_cron.
    private fun isEnabled(): Boolean =
        try {
            featureFlags.findById(FLAG_KEY).map { it.enabled }.orElse(false)
        } catch (e: Exception) {
            logger.warn(e) { "GDPR cleanup flag read failed; treating as off" }
            false
        }

    companion object {
        const val FLAG_KEY = "gdpr-in-app-cleanup"
    }
}
