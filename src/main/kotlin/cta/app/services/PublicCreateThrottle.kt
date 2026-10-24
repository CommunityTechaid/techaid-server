package cta.app.services

import cta.app.config.ClientIpResolver
import graphql.GraphQLError
import graphql.GraphqlErrorBuilder
import org.springframework.graphql.data.method.annotation.GraphQlExceptionHandler
import org.springframework.graphql.execution.ErrorType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.ControllerAdvice

/** Raised when a public create is over its per-client budget; surfaced as a BAD_REQUEST error. */
class PublicCreateRateLimitException :
    RuntimeException(
        "Too many submissions from your network. Please wait an hour and try again, " +
            "or contact distributions@communitytechaid.org.uk.",
    )

/**
 * Per-client-IP throttle for the anonymous creates on the public referral form.
 *
 * Staff create contacts (and occasionally requests) through the same mutations from the
 * dashboard, so a caller holding app:admin or write:organisations is never throttled. Any other
 * caller, including an authenticated one without those scopes, is keyed by [ClientIpResolver].
 */
@Component
class PublicCreateThrottle(
    private val clientIpResolver: ClientIpResolver,
) {
    fun acquireOrThrow(limiter: BookingRateLimiter) {
        if (isStaff()) return
        if (!limiter.tryAcquire(clientIpResolver.resolve())) throw PublicCreateRateLimitException()
    }

    private fun isStaff(): Boolean {
        val authorities = SecurityContextHolder.getContext().authentication?.authorities ?: return false
        return authorities.any { it.authority in STAFF_AUTHORITIES }
    }

    companion object {
        private val STAFF_AUTHORITIES = setOf("app:admin", "write:organisations")
    }
}

@ControllerAdvice
class PublicCreateRateLimitExceptionHandler {
    @GraphQlExceptionHandler
    fun handle(ex: PublicCreateRateLimitException): GraphQLError =
        GraphqlErrorBuilder
            .newError()
            .errorType(ErrorType.BAD_REQUEST)
            .message(ex.message)
            .build()
}
