package cta.app.services

import cta.app.DeviceRequestRepository
import cta.toNullable
import org.springframework.stereotype.Service

@Service
class DeviceRequestService(
    private val deviceRequests: DeviceRequestRepository
) {

    fun deleteCorrelationId(id: Long): Unit {
        val entity = deviceRequests.findByCorrelationId(id).toNullable()
        if (entity != null) {
            entity.correlationId = null;
            deviceRequests.save(entity)
        }
    }
}