package cta.app.schedulingtasks

import cta.app.FeatureFlagRepository
import cta.app.services.GdprDonorCleanupService
import mu.KotlinLogging
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

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
 *
 * TWO TRIGGERS, ONE REASON: UAT scales to zero on plain on-demand HTTP traffic, with no
 * KEDA business-hours warm window like production has. A fixed Monday-morning cron can
 * therefore miss its slot entirely for weeks if nothing happens to be warming the container
 * at 09:30 London that minute — and Spring's scheduler does not queue or backfill missed
 * firings; a missed slot is just silently gone. [catchUpOnStartup] closes that gap by
 * checking on every app start whether a run is overdue and, if so, running immediately.
 */
@Component
class GdprDonorCleanup(
    private val gdprDonorCleanupService: GdprDonorCleanupService,
    private val featureFlags: FeatureFlagRepository,
) {
    // Must fire inside the KEDA business-hours window (Mon-Fri 08:00-20:00 London): the app
    // scales to zero outside it, so an off-hours cron would never run in production.
    @Scheduled(cron = "0 30 9 * * MON", zone = "Europe/London")
    fun runRetentionCleanup() = runIfOverdue(CRON_DEBOUNCE)

    // Catches the case where the container was at zero replicas through the entire Monday
    // cron window. A short debounce on the cron path (above) stops this and the cron
    // double-running if a cold start happens to land right before 09:30.
    @EventListener(ApplicationReadyEvent::class)
    fun catchUpOnStartup() = runIfOverdue(CATCH_UP_THRESHOLD)

    private fun runIfOverdue(threshold: Duration) {
        if (!isEnabled()) {
            logger.debug { "GDPR in-app cleanup is off; pg_cron gdpr-weekly-cleanup still owns retention" }
            return
        }

        if (!isOverdue(threshold)) {
            logger.debug { "GDPR in-app cleanup ran within the last $threshold; nothing to do" }
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

    // Fails OPEN (missing data => overdue => run), the opposite of isEnabled()'s fail-closed:
    // silently skipping a compliance-relevant cleanup because a lookup failed is worse than
    // an extra, idempotent run.
    private fun isOverdue(threshold: Duration): Boolean =
        try {
            val lastRun: Instant? = gdprDonorCleanupService.lastRunAt()
            lastRun == null || Duration.between(lastRun, Instant.now()) > threshold
        } catch (e: Exception) {
            logger.warn(e) { "GDPR cleanup last-run lookup failed; treating as overdue" }
            true
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
        val CRON_DEBOUNCE: Duration = Duration.ofHours(1)
        val CATCH_UP_THRESHOLD: Duration = Duration.ofDays(7)
    }
}
