package cta.app.services

import com.fasterxml.jackson.annotation.JsonProperty
import cta.app.graphql.mutations.DeliveryBookingException
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Service
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * Verifies Cloudflare Turnstile tokens supplied by the public delivery-booking form.
 *
 * Disabled by default (turnstile.enabled=false); only production sets the env vars that
 * turn it on. When disabled every call is a no-op so anonymous submits (and the existing
 * test suite) keep working.
 *
 * Failure posture:
 *  - Missing/blank token or an explicit success:false from Cloudflare → reject (fail closed).
 *  - Network error, timeout or 5xx from siteverify → WARN and return normally (fail open).
 *    Deliberate: a Cloudflare API outage must not take the booking form down. The per-IP
 *    rate limiter still stands as a second line of defence during such an outage.
 */
@Service
class TurnstileService(
    @Value("\${turnstile.enabled:false}") private val enabled: Boolean,
    @Value("\${turnstile.secret:}") private val secret: String,
    @Value("\${turnstile.url:https://challenges.cloudflare.com/turnstile/v0/siteverify}") private val url: String,
) {
    // A stalled siteverify call must not pin a request thread indefinitely: on the small
    // single-replica container a few stuck threads exhausts the Tomcat pool. Timeouts mirror
    // the precedent in LocationService.kt.
    private val restClient =
        RestClient
            .builder()
            .requestFactory(
                SimpleClientHttpRequestFactory().apply {
                    setConnectTimeout(Duration.ofSeconds(3))
                    setReadTimeout(Duration.ofSeconds(5))
                },
            ).build()

    fun verifyOrThrow(
        token: String?,
        remoteIp: String?,
    ) {
        if (!enabled) return

        if (token.isNullOrBlank()) {
            throw DeliveryBookingException("We couldn't verify your browser. Please refresh the page and try again.")
        }

        val form =
            LinkedMultiValueMap<String, String>().apply {
                add("secret", secret)
                add("response", token)
                if (!remoteIp.isNullOrBlank()) add("remoteip", remoteIp)
            }

        val response =
            try {
                restClient
                    .post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TurnstileVerifyResponse::class.java)
            } catch (e: RestClientException) {
                // Fail open: network/timeout/5xx must not block bookings. The rate limiter remains.
                logger.warn(e) { "Turnstile siteverify call failed; failing open" }
                return
            }

        if (response == null || !response.success) {
            logger.error { "Turnstile verification rejected token: error-codes=${response?.errorCodes}" }
            throw DeliveryBookingException("We couldn't verify your browser. Please refresh the page and try again.")
        }
    }

    private data class TurnstileVerifyResponse(
        val success: Boolean = false,
        @JsonProperty("error-codes")
        val errorCodes: List<String> = emptyList(),
    )
}
