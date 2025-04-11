package cta.controllers

import cta.app.services.TypeformService
import mu.KotlinLogging
import org.springframework.http.ResponseEntity
import org.springframework.http.ResponseEntity.ok
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader

private val logger = KotlinLogging.logger {}
@Controller
class TypeformWebhookController(
    private val typeformService: TypeformService
) {
    @PostMapping("/typeform/hook")

    fun typeFormWebhook(
        @RequestBody payload: String,
        @RequestHeader("Typeform-Signature") signature: String
    ): ResponseEntity<Unit> {


        if (typeformService.validateHMACSignature(signature, payload)) {
            logger.debug("Signatures match");
            typeformService.handleTypeformSubmission(payload);
            return ok().build();
        }
        logger.debug("Signatures don't match");
        return ResponseEntity.badRequest().build();
    }
}
