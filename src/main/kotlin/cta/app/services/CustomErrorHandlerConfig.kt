package cta.app.services

import graphql.GraphQLError
import graphql.GraphqlErrorBuilder
import graphql.schema.DataFetchingEnvironment
import jakarta.validation.ConstraintViolationException
import mu.KotlinLogging
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Last-resort resolver for exceptions that no controller handled.
 *
 * Spring for GraphQL consults controller-local and `@ControllerAdvice` handlers first, so this
 * only ever sees the leftovers - a Hibernate constraint violation, an `EntityNotFoundException`,
 * a stray NPE. It used to echo `ex.message` verbatim to whoever made the call, including
 * unauthenticated callers on the public booking and referral surfaces, which hands out failing
 * statement fragments, constraint and column names, and row ids to anyone who can provoke them.
 *
 * Three carve-outs, all deliberate:
 *
 * 1. **Authenticated callers still get the real message.** Staff-facing mutations lean on it
 *    ("Unable to locate a kit with id: 42", "You cannot delete other user's notes") and the
 *    dashboard displays it. Genericising those would be a regression, not a fix.
 * 2. **Bean validation survives for everyone.** A `ConstraintViolationException` message only
 *    ever describes the caller's own input ("...data.email: must be a well-formed email
 *    address"), and the public forms need that feedback to be usable.
 * 3. **Authorisation failures survive for everyone.** "Access Denied" carries no information a
 *    caller did not already have, and two things read it: the collection-booking Apps Script
 *    classifies `'access denied'` as retryable, which is the whole reason its cold-start retry
 *    works (the denial arrives as HTTP 200 with that message, so a status code will not do),
 *    and PublicSurfaceAuthorizationTest asserts on it. Genericising it silently breaks the
 *    calendar sync's retry.
 *
 * Everything else, for an anonymous caller, becomes a generic message carrying a short reference
 * that is logged here with the full exception - so support can still join a user's report to
 * the stack trace without the stack trace being published.
 *
 * The deliberate user-facing messages on the public surfaces are NOT affected, because they
 * already have dedicated exception types with local `@GraphQlExceptionHandler`s and never reach
 * this class: `DeliveryBookingException` (booking rate limit, eligibility refusals),
 * `ExceededDeviceRequestLimitException`, `WipeCertMissingException`, `BlockingFlagSetException`
 * and `DeliveryAdminException`. That is the pattern to copy for any new one.
 */
@Component
class CustomErrorHandlerConfig : DataFetcherExceptionResolverAdapter() {
    override fun resolveToSingleError(
        ex: Throwable,
        env: DataFetchingEnvironment,
    ): GraphQLError? {
        val builder =
            GraphqlErrorBuilder
                .newError()
                .path(env.getExecutionStepInfo().getPath())
                .location(env.getField().getSourceLocation())

        if (isAuthenticated() || isSafeToEcho(ex)) {
            return builder.message(ex.message).build()
        }

        val reference = UUID.randomUUID().toString().substring(0, 8)
        logger.error(
            "Unhandled error for an anonymous caller at '${env.getExecutionStepInfo().getPath()}' [ref $reference]",
            ex,
        )
        return builder.message(GENERIC_MESSAGE.format(reference)).build()
    }

    /**
     * Exception types whose message is safe for anyone: it describes either the caller's own
     * input or their own lack of permission. Whitelisted BY TYPE, never by matching on message
     * text - a string match would start echoing whatever a future exception happens to say.
     */
    private fun isSafeToEcho(ex: Throwable): Boolean =
        ex is ConstraintViolationException ||
            ex is AccessDeniedException ||
            ex is AuthenticationException

    /**
     * An absent context, an unauthenticated token and Spring's anonymous token all count as
     * anonymous. Failing towards "anonymous" is the safe direction here.
     */
    private fun isAuthenticated(): Boolean {
        val authentication = SecurityContextHolder.getContext().authentication ?: return false
        return authentication.isAuthenticated && authentication !is AnonymousAuthenticationToken
    }

    companion object {
        const val GENERIC_MESSAGE: String =
            "Something went wrong. Please try again - if it keeps happening, " +
                "contact Community TechAid quoting reference %s."
    }
}
