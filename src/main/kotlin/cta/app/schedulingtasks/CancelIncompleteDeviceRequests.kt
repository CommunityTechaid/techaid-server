package cta.app.schedulingtasks

import cta.app.services.DeviceRequestService
import mu.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class CancelIncompleteDeviceRequests(private val deviceRequestService: DeviceRequestService) {
    @Scheduled(cron = "0 */20 * * * *", zone = "UTC")
    fun cancelIncompleteDeviceRequests() {
        logger.info("Started cancelling incomplete device requests")
        val count = deviceRequestService.cancelIncompleteDeviceRequests()
        logger.info("Cancelled $count requests");
    }
}
