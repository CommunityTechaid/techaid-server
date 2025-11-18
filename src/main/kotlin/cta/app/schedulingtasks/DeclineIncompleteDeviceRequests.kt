package cta.app.schedulingtasks

import cta.app.services.DeviceRequestService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class DeclineIncompleteDeviceRequests(private val deviceRequestService: DeviceRequestService) {
    @Scheduled(cron = "0 */20 * * * *", zone = "UTC")
    fun declineIncompleteDeviceRequests() {
        logger.info("Started declining incomplete device requests")
        val count = deviceRequestService.declineIncompleteDeviceRequests()
        logger.info("Declined $count requests");
    }
}
