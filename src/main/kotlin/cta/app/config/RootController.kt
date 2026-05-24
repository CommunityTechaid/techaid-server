package cta.app.config

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * Stops incidental browser visits to the API root from dispatching to
 * Spring's default /error handler (which logged as GET /error 404).
 */
@RestController
class RootController {
    @GetMapping("/")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun root() {}
}
