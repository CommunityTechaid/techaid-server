package cta.app.services

import cta.models.TypeFormPayload
import jdk.internal.joptsimple.internal.Messages.message
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi


private val logger = KotlinLogging.logger {}

@OptIn(ExperimentalEncodingApi::class)
@Service
class TypeformService(
    private val deviceRequestService: DeviceRequestService,
) {
    @Value("\${auth0.token-attribute}")
    private lateinit var typeformSecret: String;

    fun handleTypeformSubmission(typeFormPayload: TypeFormPayload) {

        if (typeFormPayload.formResponse.hidden.containsKey("corr_id")) {
            val correlationId = typeFormPayload.formResponse.hidden["corr_id"];

            if (correlationId != null) {
                try {
                    deviceRequestService.deleteCorrelationId(correlationId.toLong())
                    logger.info("Deleted correlation ID");
                    return
                } catch (e: NumberFormatException) {
                    logger.error("Typeform Webhook: Invalid correlation ID received :$correlationId");
                    return
                }
            }

            logger.debug("Typeform Webhook: Correlation ID was null");
        }
        logger.debug("Typeform Webhook: Correlation key was not found");
    }

    fun validateHMACSignature(receivedSignature: String, payload: TypeFormPayload): String {
        val hmacSHA256 = Mac.getInstance("HmacSHA256")
        val secretKeySpec = SecretKeySpec(payload.getBytes(), "HmacSHA256")
        hmacSHA256.init(secretKeySpec)
        val signatureBytes = hmacSHA256.doFinal(message.getBytes())
        return Base64.Default.encode(signatureBytes)
    }

}