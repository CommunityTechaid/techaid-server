package cta.app.services

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import cta.models.TypeFormPayload
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.ExperimentalEncodingApi


private val logger = KotlinLogging.logger {}

@OptIn(ExperimentalEncodingApi::class)
@Service
class TypeformService(
    private val deviceRequestService: DeviceRequestService,
    private val objectMapper: ObjectMapper
) {
    @Value("\${typeform.key}")
    private lateinit var typeformSecret: String;

    fun handleTypeformSubmission(payload: String) {

        val typeFormPayload = objectMapper.readValue<TypeFormPayload>(payload);
        if (typeFormPayload.formResponse.hidden.containsKey("corr_id")) {
            val correlationId = typeFormPayload.formResponse.hidden["corr_id"];

            if (correlationId != null) {
                try {
                    val savedRequest = deviceRequestService.deleteCorrelationId(correlationId.toLong())
                    if (savedRequest != null) {
                        logger.info("Deleted correlation ID");

                        //Send email confirmation
                        deviceRequestService.acknowledgeSubmission(savedRequest);
                    }
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

    fun validateHMACSignature(receivedSignature: String, payload: String): Boolean {
        val cleanReceivedSignature = receivedSignature.removePrefix("sha256=")
        val hmacSHA256 = Mac.getInstance("HmacSHA256")
        val secretKeySpec = SecretKeySpec(typeformSecret.toByteArray(Charsets.UTF_8), "HmacSHA256")
        hmacSHA256.init(secretKeySpec)


        val signatureBytes = hmacSHA256.doFinal(payload.toByteArray(Charsets.UTF_8))

        return Base64.getEncoder().encodeToString(signatureBytes) == cleanReceivedSignature
    }

}