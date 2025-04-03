package cta.app.services

import cta.models.TypeFormPayload
import mu.KotlinLogging
import org.springframework.stereotype.Service


private val logger = KotlinLogging.logger {}

@Service
class TypeformService(
    private val deviceRequestService: DeviceRequestService,
) {
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


}