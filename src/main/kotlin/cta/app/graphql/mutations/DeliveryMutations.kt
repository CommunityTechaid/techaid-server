package cta.app.graphql.mutations

import cta.app.DeliveryBooking
import cta.app.DeliveryBookingOverrideRepository
import cta.app.DeliveryBookingRepository
import cta.app.DeliveryWindowRepository
import cta.app.DeviceRequestRepository
import cta.app.FeatureFlagRepository
import cta.app.config.ClientIpResolver
import cta.app.graphql.queries.DeliveryWindowGql
import cta.app.services.BookingRateLimiter
import cta.app.services.BoroughAvailabilityRules
import cta.app.services.DeliveryService
import cta.app.services.RefereeRequestLimitService
import cta.app.services.TurnstileService
import cta.toNullable
import graphql.GraphQLError
import graphql.GraphqlErrorBuilder
import jakarta.validation.ConstraintViolationException
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.GraphQlExceptionHandler
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.execution.ErrorType
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException

private val logger = KotlinLogging.logger {}

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
    private val overrides: DeliveryBookingOverrideRepository,
    private val deviceRequests: DeviceRequestRepository,
    private val delivery: DeliveryService,
    private val rateLimiter: BookingRateLimiter,
    private val turnstile: TurnstileService,
    private val clientIpResolver: ClientIpResolver,
    private val featureFlags: FeatureFlagRepository,
    private val boroughRules: BoroughAvailabilityRules,
    private val refereeLimits: RefereeRequestLimitService,
    @Value("\${delivery-booking.enforce-feature-flag}") private val enforceFeatureFlag: Boolean,
) {
    @MutationMapping
    fun submitDeliveryBookingPublic(
        @Argument @Valid input: DeliveryBookingInput,
    ): DeliveryBookingConfirmationGql {
        // Bot protection runs before any DB work so junk traffic is rejected before it takes a
        // per-window row lock. Order: soft per-IP throttle, then Turnstile, then the flag gate.
        val clientIp = clientIpResolver.resolve()
        if (!rateLimiter.tryAcquire(clientIp)) {
            throw DeliveryBookingException("Too many booking attempts. Please wait a few minutes and try again.")
        }
        turnstile.verifyOrThrow(input.turnstileToken, clientIp)
        // Prod sets enforce-feature-flag=true so the mutation is closed while the page is hidden;
        // elsewhere it stays open (default false) and the flag only gates the FE.
        if (enforceFeatureFlag &&
            featureFlags
                .findById("delivery-booking")
                .map { it.enabled }
                .orElse(false)
                .not()
        ) {
            throw DeliveryBookingException("Delivery booking is not currently available.")
        }

        val date =
            try {
                LocalDate.parse(input.date)
            } catch (e: DateTimeParseException) {
                throw DeliveryBookingException("Invalid delivery date: ${input.date}")
            }

        val windowId = input.windowId.toLongOrNull() ?: throw DeliveryBookingException("Unknown delivery window")

        // Advisory lock is always taken before the per-window row lock below, so lock order is
        // globally consistent across requests (advisory-then-row) and same-ref-different-window
        // races can't deadlock against it.
        bookings.acquireReferenceLock("delivery-booking:${input.ctaReference}")

        // Pessimistic lock: concurrent submits for the same window serialise here, so the
        // capacity check below can't oversell under a read-check-insert race.
        val window =
            windows.findByIdForUpdate(windowId)
                ?: throw DeliveryBookingException("Unknown delivery window")
        if (!window.active) throw DeliveryBookingException("That delivery window is no longer available")
        if (!delivery.isBookableDay(date)) throw DeliveryBookingException("Deliveries aren't available on that date")

        val booked = bookings.countByDeliveryDateAndWindowId(date, window.id)
        if (booked >= window.capacity) throw DeliveryBookingException("That delivery window is fully booked")

        // Borough gate (dashboard #180 / sheet row 18): only enforced while the flag is on, and
        // always fails open — we already accepted the linked request, so refusing a delivery to
        // it wrongly is far worse than letting one through we'd rather not have. Anything that
        // stops us confidently saying "not covered" (no request, no borough, no group match
        // lookup) falls through to "let it through".
        if (boroughRules.enabled()) {
            val linkedRequest =
                try {
                    deviceRequests.findById(input.ctaReference).toNullable()
                } catch (e: Exception) {
                    logger.warn(e) { "Borough check: failed to look up device request ${input.ctaReference}; letting the booking through." }
                    null
                }
            val borough = linkedRequest?.borough
            if (!borough.isNullOrBlank()) {
                var lookupFailed = false
                val group =
                    try {
                        refereeLimits.groupFor(borough)
                    } catch (e: Exception) {
                        logger.warn(e) { "Borough check: group lookup failed for '$borough'; letting the booking through." }
                        lookupFailed = true
                        null
                    }
                if (group == null && !lookupFailed) {
                    logger.warn {
                        "Delivery booking refused: borough '$borough' on device request ${linkedRequest.id} " +
                            "is not in a covered area."
                    }
                    throw DeliveryBookingException(
                        "We're sorry, we don't currently deliver to your area. Please call us on 020 3488 7742 " +
                            "to discuss other options.",
                    )
                }
            }
        }

        // One booking per CTA reference, past or future: honest-user dedup only (ctaReference is
        // attacker-controlled free text; bots/abuse are handled by rate-limit + Turnstile). Staff
        // can grant a one-off DeliveryBookingOverride to let a reference book again; using it here
        // consumes it, so the exemption is worth exactly one booking.
        if (bookings.existsByCtaReference(input.ctaReference)) {
            // Phone number matches CONTACT_PHONE in DeliveryService.kt (private there, so inlined).
            val override =
                overrides.findFirstByCtaReferenceAndConsumedAtIsNull(input.ctaReference)
                    ?: throw DeliveryBookingException(
                        "You already have an upcoming delivery booked. If you need to change it, " +
                            "please call us on 020 3488 7742.",
                    )
            override.consumedAt = Instant.now()
            overrides.save(override)
        }

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

        delivery.markCollectionDeliveryArranged(saved, window, date)
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

    @GraphQlExceptionHandler
    fun handleValidationError(ex: ConstraintViolationException): GraphQLError =
        GraphqlErrorBuilder
            .newError()
            .errorType(ErrorType.BAD_REQUEST)
            .message(
                ex.constraintViolations.joinToString("; ") {
                    "${it.propertyPath.toString().substringAfterLast('.')} ${it.message}"
                },
            ).build()
}

data class DeliveryBookingInput(
    @get:NotBlank @get:Size(max = 32) var date: String = "",
    @get:NotBlank @get:Size(max = 32) var windowId: String = "",
    @get:NotBlank @get:Size(max = 100) var firstName: String = "",
    @get:NotBlank @get:Size(max = 100) var surname: String = "",
    @get:NotBlank @get:Email @get:Size(max = 254) var email: String = "",
    @get:NotBlank @get:Size(max = 32) var phone: String = "",
    @get:NotBlank @get:Size(max = 1000) var address: String = "",
    @get:Size(max = 2000) var accessNotes: String? = null,
    /** The booker's device request id. Typed Long so a non-numeric reference is rejected. */
    @get:Positive var ctaReference: Long = 0,
    @get:Size(max = 2048) var turnstileToken: String? = null,
)

data class DeliveryBookingConfirmationGql(
    val id: String,
    val date: String,
    val dayLabel: String,
    val window: DeliveryWindowGql,
    val address: String,
    val ctaReference: Long,
    val confirmationSentTo: String,
)
