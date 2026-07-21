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
        val cutoff = Instant.now().minus(20, ChronoUnit.MINUTES)
        val staleRequests =
            deviceRequests
                .findAllByCorrelationIdIsNotNull()
                .filter { cutoff.isAfter(it.createdAt) }

        staleRequests.forEach { request ->
            request.status = DeviceRequestStatus.REQUEST_DECLINED
            request.correlationId = null
        }

        // Persist the declines BEFORE notifying. Emailing first meant any failure while
        // building or sending a message aborted the sweep before saveAll, leaving every
        // request in the batch still pending - so the ones already emailed were emailed
        // again on every subsequent sweep.
        val declined = deviceRequests.saveAll(staleRequests)

        declined.forEach { request -> notifyDeclinedRequest(request) }

        return declined.count()
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

        try {
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

            mailService.sendMessage(msg)
        } catch (e: Exception) {
            logger.error("Failed to send acknowledgement email for device request ${request.id}", e)
        }
    }

    fun notifyDeclinedRequest(
        @Argument request: DeviceRequest,
    ) {
        if (!mailService.emailEnabled) {
            return
        }

        // The whole body is guarded, not just the send: a malformed recipient address or a
        // template failure must not escape and abort the caller's sweep over other requests.
        try {
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

            mailService.sendMessage(msg)
        } catch (e: Exception) {
            logger.error("Failed to send declined-request email for device request ${request.id}", e)
        }
    }
}
