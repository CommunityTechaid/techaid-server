package cta.app.schedulingtasks

import cta.app.services.GdprDonorCleanupService
import mu.KotlinLogging
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * In-app GDPR retention cleanup. It superseded the pg_cron job `gdpr-weekly-cleanup` — server-side
 * state that is silently lost on any DB restore — at the 2026-08-12 cutover.
 *
 * THIS IS NOW THE ONLY THING PERFORMING RETENTION. The switchover was formalised on 2026-08-18:
 * the `gdpr-in-app-cleanup` feature flag was removed (V26.08.18.1500 deletes the row) and the
 * pg_cron job was deleted outright, so neither the fallback nor the runtime off switch exists any
 * more. Stopping retention now takes a code change and a deploy — deliberately, because a
 * compliance control that can be switched off from a web page had been switched off by nobody,
 * for nobody, in every environment where the row happened to be missing.
 *
 * It still calls the same `gdpr.performgdprcleanup()` the pg_cron job called; retiring pg_cron
 * retired the *schedule*, not the routine. Dropping that function stops retention dead.
 *
 * TWO TRIGGERS, ONE REASON: UAT scales to zero on plain on-demand HTTP traffic, with no
 * KEDA business-hours warm window like production has. A fixed weekly cron can therefore miss
 * its slot entirely for weeks if nothing happens to be warming the container at that minute —
 * and Spring's scheduler does not queue or backfill missed firings; a missed slot is just
 * silently gone. [catchUpOnStartup] closes that gap by checking on every app start whether a
 * run is overdue and, if so, running immediately.
 *
 * NOT ACTIVE UNDER THE `test` PROFILE. The embedded test database has a real
 * `gdpr.performgdprcleanup()`, and with no flag left to hold it back this would erase fixture
 * data on every Spring context start. That exclusion is a test-isolation measure, not a kill
 * switch: UAT and production never run the `test` profile, so it can never disable retention
 * where it matters. `GdprDonorCleanupInertUnderTestProfileTest` pins it.
 */
@Component
@Profile("!test")
class GdprDonorCleanup(
    private val gdprDonorCleanupService: GdprDonorCleanupService,
) {
    // Must fire inside the KEDA business-hours window (Mon-Fri 08:00-20:00 London): the app
    // scales to zero outside it, so an off-hours cron would never run in production. Friday
    // 18:00 is the last workable slot of the week — retention runs after the week's edits are
    // in, and still two hours clear of the 20:00 scale-down.
    @Scheduled(cron = "0 0 18 * * FRI", zone = "Europe/London")
    fun runRetentionCleanup() = runIfOverdue(CRON_DEBOUNCE)

    // Catches the case where the container was at zero replicas through the entire weekly
    // cron window. A short debounce on the cron path (above) stops this and the cron
    // double-running if a cold start happens to land right before the slot.
    @EventListener(ApplicationReadyEvent::class)
    fun catchUpOnStartup() = runIfOverdue(CATCH_UP_THRESHOLD)

    private fun runIfOverdue(threshold: Duration) {
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
            // Nothing else performs retention since the 2026-08-18 switchover, so a run that
            // keeps failing means retention has stopped — treat this line as an incident, not a
            // warning, and note the next scheduled attempt is a week away.
            logger.error(e) { "GDPR retention cleanup FAILED - nothing else performs retention; next attempt is Friday 18:00 London" }
        }
    }

    // Fails OPEN (missing data => overdue => run): silently skipping a compliance-relevant
    // cleanup because a lookup failed is worse than an extra, idempotent run.
    private fun isOverdue(threshold: Duration): Boolean =
        try {
            val lastRun: Instant? = gdprDonorCleanupService.lastRunAt()
            lastRun == null || Duration.between(lastRun, Instant.now()) > threshold
        } catch (e: Exception) {
            logger.warn(e) { "GDPR cleanup last-run lookup failed; treating as overdue" }
            true
        }

    companion object {
        val CRON_DEBOUNCE: Duration = Duration.ofHours(1)
        val CATCH_UP_THRESHOLD: Duration = Duration.ofDays(7)
    }
}
