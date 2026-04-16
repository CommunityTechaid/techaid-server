package cta.app.services

import cta.app.DeviceRequest
import cta.app.DeviceRequestItems
import cta.app.DeviceRequestRepository
import cta.app.DeviceRequestStatus
import cta.toNullable
import jakarta.mail.internet.InternetAddress
import mu.KotlinLogging
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.stereotype.Service
import org.thymeleaf.TemplateEngine
import org.thymeleaf.context.Context
import java.time.Instant
import java.time.temporal.ChronoUnit

private val logger = KotlinLogging.logger {}

@Service
class DeviceRequestService(
    private val deviceRequests: DeviceRequestRepository,
    private val mailService: MailService,
    private val templateEngine: TemplateEngine,
) {
    fun markRequestStepsCompleted(id: Long): DeviceRequest? {
        val entity = deviceRequests.findByCorrelationId(id).toNullable()
        if (entity != null) {
            entity.status = DeviceRequestStatus.PROCESSING_EQUALITIES_DATA_COMPLETE
            entity.correlationId = null
            return deviceRequests.save(entity)
        }

        return null
    }

    fun declineIncompleteDeviceRequests(): Int {
        val incompleteRequests = deviceRequests.findAllByCorrelationIdIsNotNull()

        incompleteRequests.forEach { request ->
            if (Instant.now().minus(20, ChronoUnit.MINUTES).isAfter(request.createdAt)) {
                request.status = DeviceRequestStatus.REQUEST_DECLINED
                request.correlationId = null
                notifyDeclinedRequest(request)
            }
        }

        return deviceRequests.saveAll(incompleteRequests).count()
    }

    fun formatDeviceRequests(
        @Argument items: DeviceRequestItems,
    ): String {
        var deviceRequest = ""
        if (items.phones ?: 0 > 0) deviceRequest += "Phones: ${items.phones}<br>\n"
        if (items.tablets ?: 0 > 0) deviceRequest += "Tablets: ${items.tablets}<br>\n"
        if (items.laptops ?: 0 > 0) deviceRequest += "Laptops: ${items.laptops}<br>\n"
        if (items.allInOnes ?: 0 > 0) deviceRequest += "All-in-ones: ${items.allInOnes}<br>\n"
        if (items.desktops ?: 0 > 0) deviceRequest += "Desktops: ${items.desktops}<br>\n"
        if (items.other ?: 0 > 0) deviceRequest += "Other: ${items.other}<br>\n"
        if ((items.commsDevices ?: 0) >
            0
        ) {
            deviceRequest += "SIM card (6 months, 20GB data, unlimited UK calls): ${items.commsDevices}<br>\n"
        }
        if (items.broadbandHubs ?: 0 > 0) deviceRequest += "Broadband Hubs: ${items.broadbandHubs}<br>\n"
        return deviceRequest
    }

    fun acknowledgeSubmission(
        @Argument request: DeviceRequest,
    ) {
        if (!mailService.emailEnabled) {
            return
        }

        val context =
            Context().apply {
                setVariable("contactName", request.referringOrganisationContact.fullName)
                setVariable("orgName", request.referringOrganisationContact.referringOrganisation.name)
                setVariable("requestId", request.id)
                setVariable("clientRef", request.clientRef)
                setVariable("deviceItems", formatDeviceRequests(request.deviceRequestItems))
            }

        val msg =
            createEmail(
                to = request.referringOrganisationContact.email,
                from = mailService.address,
                subject = "Community TechAid: Device Request Acknowledged",
                bodyText = templateEngine.process("email/device-request-acknowledged", context),
                mimeType = "html",
                charset = "UTF-8",
            )

        if (!mailService.bccAddress.isNullOrEmpty()) {
            msg.addRecipient(
                jakarta.mail.Message.RecipientType.BCC,
                InternetAddress(mailService.bccAddress),
            )
        }

        try {
            mailService.sendMessage(msg)
        } catch (e: Exception) {
            logger.error("Failed to send email", e)
        }
    }

    fun notifyDeclinedRequest(
        @Argument request: DeviceRequest,
    ) {
        if (!mailService.emailEnabled) {
            return
        }

        val context =
            Context().apply {
                setVariable("contactName", request.referringOrganisationContact.fullName)
                setVariable("orgName", request.referringOrganisationContact.referringOrganisation.name)
                setVariable("requestId", request.id)
                setVariable("clientRef", request.clientRef)
                setVariable("deviceItems", formatDeviceRequests(request.deviceRequestItems))
            }

        val msg =
            createEmail(
                to = request.referringOrganisationContact.email,
                from = mailService.address,
                subject = "Community TechAid: Device Request Declined",
                bodyText = templateEngine.process("email/device-request-declined", context),
                mimeType = "html",
                charset = "UTF-8",
            )

        if (!mailService.bccAddress.isNullOrEmpty()) {
            msg.addRecipient(
                jakarta.mail.Message.RecipientType.BCC,
                InternetAddress(mailService.bccAddress),
            )
        }

        try {
            mailService.sendMessage(msg)
        } catch (e: Exception) {
            logger.error("Failed to send email", e)
        }
    }
}
