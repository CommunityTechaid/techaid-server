package cta.app.graphql.queries

import cta.app.DeliveryWindow
import cta.app.config.ClientIpResolver
import cta.app.graphql.mutations.DeliveryBookingException
import cta.app.services.BookingRateLimiter
import cta.app.services.DeliveryService
import graphql.GraphQLError
import graphql.GraphqlErrorBuilder
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.GraphQlExceptionHandler
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.execution.ErrorType
import org.springframework.stereotype.Controller

/**
 * Public delivery availability and eligibility. No @PreAuthorize: this is the anonymous surface
 * the public booking page reads (see PublicSurfaceAuthorizationTest).
 */
@Controller
class DeliveryQueries(
    private val delivery: DeliveryService,
    private val rateLimiter: BookingRateLimiter,
    private val clientIpResolver: ClientIpResolver,
) {
    @QueryMapping
    fun deliveryAvailabilityPublic(
        @Argument ctaReference: Long?,
    ): List<DeliveryDayAvailabilityGql> =
        delivery.availability(ctaReference = ctaReference).map { day ->
            DeliveryDayAvailabilityGql(
                date = day.date.toString(),
                dayOfWeek = delivery.dayOfWeekName(day.date),
                dayLabel = delivery.dayLabel(day.date),
                windows =
                    day.windows.map { availability ->
                        DeliveryWindowAvailabilityGql(
                            window = DeliveryWindowGql.from(availability.window),
                            spotsRemaining = availability.spotsRemaining,
                        )
                    },
            )
        }

    /**
     * Lets the booking form check a CTA reference before submit rather than only finding out at
     * the end (sheet row 24). Applies the same rule as submitDeliveryBookingPublic — see
     * DeliveryService.checkBookingEligibility — so the two can never drift apart. Unauthenticated,
     * so rate-limited per client IP the same way the mutation is; no Turnstile here.
     */
    @QueryMapping
    fun deliveryBookingEligibilityPublic(
        @Argument ctaReference: Long,
    ): DeliveryBookingEligibilityGql {
        val clientIp = clientIpResolver.resolve()
        if (!rateLimiter.tryAcquire(clientIp)) {
            throw DeliveryBookingException("Too many booking attempts. Please wait a few minutes and try again.")
        }
        val eligibility = delivery.checkBookingEligibility(ctaReference)
        return DeliveryBookingEligibilityGql(eligible = eligibility.eligible, message = eligibility.message)
    }

    @GraphQlExceptionHandler
    fun handleDeliveryBookingError(ex: DeliveryBookingException): GraphQLError =
        GraphqlErrorBuilder
            .newError()
            .errorType(ErrorType.BAD_REQUEST)
            .message(ex.message)
            .build()
}

data class DeliveryBookingEligibilityGql(
    val eligible: Boolean,
    val message: String?,
)

data class DeliveryWindowGql(
    val id: String,
    val name: String,
    val startTime: String,
    val endTime: String,
    val icon: String,
) {
    companion object {
        fun from(window: DeliveryWindow): DeliveryWindowGql =
            DeliveryWindowGql(
                id = window.id.toString(),
                name = window.name,
                startTime = window.startTime,
                endTime = window.endTime,
                icon = window.icon,
            )
    }
}

data class DeliveryWindowAvailabilityGql(
    val window: DeliveryWindowGql,
    val spotsRemaining: Int,
)

data class DeliveryDayAvailabilityGql(
    val date: String,
    val dayOfWeek: String,
    val dayLabel: String,
    val windows: List<DeliveryWindowAvailabilityGql>,
)
