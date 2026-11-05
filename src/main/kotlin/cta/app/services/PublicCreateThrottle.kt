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

    /**
     * The anonymous organisation lookup (#352). Any staff read or write scope that reaches the
     * dashboard's organisation picker is exempt; everyone else spends the per-IP budget.
     */
    fun acquireLookupOrThrow(limiter: BookingRateLimiter) {
        if (isStaffReader()) return
        if (!limiter.tryAcquire(clientIpResolver.resolve())) throw PublicLookupRateLimitException()
    }

    fun isStaffReader(): Boolean = hasAny(STAFF_READ_AUTHORITIES)

    private fun isStaff(): Boolean = hasAny(STAFF_AUTHORITIES)

    private fun hasAny(scopes: Set<String>): Boolean {
        val authorities = SecurityContextHolder.getContext().authentication?.authorities ?: return false
        return authorities.any { it.authority in scopes }
    }

    companion object {
        private val STAFF_AUTHORITIES = setOf("app:admin", "write:organisations", "write:requests")
        private val STAFF_READ_AUTHORITIES =
            STAFF_AUTHORITIES + setOf("read:organisations", "read:requests", "read:referees", "write:referees")
    }
}

/** Raised when an anonymous organisation lookup is over its per-client budget (#352). */
class PublicLookupRateLimitException : RuntimeException("Too many searches from your network. Please wait a few minutes and try again.")

@ControllerAdvice
class PublicCreateRateLimitExceptionHandler {
    @GraphQlExceptionHandler
    fun handle(ex: PublicCreateRateLimitException): GraphQLError =
        GraphqlErrorBuilder
            .newError()
            .errorType(ErrorType.BAD_REQUEST)
            .message(ex.message)
            .build()

    @GraphQlExceptionHandler
    fun handle(ex: PublicLookupRateLimitException): GraphQLError =
        GraphqlErrorBuilder
            .newError()
            .errorType(ErrorType.BAD_REQUEST)
            .message(ex.message)
            .build()
}
