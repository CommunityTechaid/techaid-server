package cta.controllers

import cta.app.services.TypeformService
import cta.models.TypeFormPayload
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus

@Controller
class TypeformWebhookController(
    private val typeformService: TypeformService
) {
    @PostMapping("/typeform/hook")
    @ResponseStatus(HttpStatus.OK)
    fun typeFormWebhook(@RequestBody payload: TypeFormPayload ): Unit  {

        typeformService.handleTypeformSubmission(payload);
        return;
    }
}
