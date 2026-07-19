package cta.app.schedulingtasks

import cta.app.services.GdprDonorCleanupService
import mu.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class GdprDonorCleanup(
    private val gdprDonorCleanupService: GdprDonorCleanupService,
) {
    // Must fire inside the KEDA business-hours window (Mon-Fri 08:00-20:00 London): the app
    // scales to zero outside it, so an off-hours cron would never run in production.
    // Runs in parallel with the pg_cron job gdpr-weekly-cleanup (Sat 04:04 UTC) until that
    // job is confirmed superseded and unscheduled.
    @Scheduled(cron = "0 30 9 * * MON", zone = "Europe/London")
    fun deleteExpiredDonors() {
        logger.info("Started GDPR donor cleanup")
        val count = gdprDonorCleanupService.deleteExpiredDonors()
        logger.info("GDPR donor cleanup removed $count donors")
    }
}
