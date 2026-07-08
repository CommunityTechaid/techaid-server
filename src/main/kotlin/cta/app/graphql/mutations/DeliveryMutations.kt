package cta.app.graphql.mutations

import cta.app.DeliveryBooking
import cta.app.DeliveryBookingRepository
import cta.app.DeliveryWindowRepository
import cta.app.graphql.queries.DeliveryWindowGql
import cta.app.services.DeliveryService
import cta.toNullable
import graphql.GraphQLError
import graphql.GraphqlErrorBuilder
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.GraphQlExceptionHandler
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.execution.ErrorType
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** Raised when a booking can't be accepted; surfaced to the client as a BAD_REQUEST error. */
class DeliveryBookingException(
    message: String,
) : RuntimeException(message)

/**
 * Public delivery booking. No @PreAuthorize: members of the public book without a login
 * (see PublicSurfaceAuthorizationTest). Capacity is re-checked here so a stale availability
 * read can't oversell a window.
 */
@Controller
@Validated
@Transactional
class DeliveryMutations(
    private val windows: DeliveryWindowRepository,
    private val bookings: DeliveryBookingRepository,
    private val delivery: DeliveryService,
) {
    @MutationMapping
    fun submitDeliveryBookingPublic(
        @Argument @Valid input: DeliveryBookingInput,
    ): DeliveryBookingConfirmationGql {
        val date =
            try {
                LocalDate.parse(input.date)
            } catch (e: DateTimeParseException) {
                throw DeliveryBookingException("Invalid delivery date: ${input.date}")
            }

        val windowId = input.windowId.toLongOrNull() ?: throw DeliveryBookingException("Unknown delivery window")
        val window =
            windows.findById(windowId).toNullable()
                ?: throw DeliveryBookingException("Unknown delivery window")
        if (!window.active) throw DeliveryBookingException("That delivery window is no longer available")
        if (!delivery.isBookableDay(date)) throw DeliveryBookingException("Deliveries aren't available on that date")

        val booked = bookings.countByDeliveryDateAndWindowId(date, window.id)
        if (booked >= window.capacity) throw DeliveryBookingException("That delivery window is fully booked")

        val saved =
            bookings.save(
                DeliveryBooking(
                    deliveryDate = date,
                    window = window,
                    firstName = input.firstName,
                    surname = input.surname,
                    email = input.email,
                    phone = input.phone,
                    address = input.address,
                    accessNotes = input.accessNotes,
                    ctaReference = input.ctaReference,
                ),
            )

        delivery.sendConfirmationEmail(saved, window, date)

        return DeliveryBookingConfirmationGql(
            id = saved.id.toString(),
            date = input.date,
            dayLabel = delivery.dayLabel(date),
            window = DeliveryWindowGql.from(window),
            address = saved.address,
            ctaReference = saved.ctaReference,
            confirmationSentTo = saved.email,
        )
    }

    @GraphQlExceptionHandler
    fun handleDeliveryBookingError(ex: DeliveryBookingException): GraphQLError =
        GraphqlErrorBuilder
            .newError()
            .errorType(ErrorType.BAD_REQUEST)
            .message(ex.message)
            .build()
}

data class DeliveryBookingInput(
    @get:NotBlank var date: String = "",
    @get:NotBlank var windowId: String = "",
    @get:NotBlank var firstName: String = "",
    @get:NotBlank var surname: String = "",
    @get:NotBlank var email: String = "",
    @get:NotBlank var phone: String = "",
    @get:NotBlank var address: String = "",
    var accessNotes: String? = null,
    @get:NotBlank var ctaReference: String = "",
)

data class DeliveryBookingConfirmationGql(
    val id: String,
    val date: String,
    val dayLabel: String,
    val window: DeliveryWindowGql,
    val address: String,
    val ctaReference: String,
    val confirmationSentTo: String,
)
