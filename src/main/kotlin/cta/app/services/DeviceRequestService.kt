package cta.app.services

import cta.app.DeviceRequestRepository
import cta.app.DeviceRequestStatus
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

    fun cancelIncompleteDeviceRequests(): Int {
        var incompleteRequests = deviceRequests.findAllByCorrelationIdIsNotNull()

        incompleteRequests.forEach { request ->
            request.status = DeviceRequestStatus.REQUEST_CANCELLED;
        }

        return deviceRequests.saveAll(incompleteRequests).count();


    }
}