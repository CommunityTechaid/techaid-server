package cta.app.graphql.mutations

import cta.app.DeliveryBlockedDate
import cta.app.DeliveryBlockedDateRepository
import cta.app.DeliveryBookingOverride
import cta.app.DeliveryBookingOverrideRepository
import cta.app.DeliveryBookingRepository
import cta.app.DeliveryConfigRepository
import cta.app.DeliveryWindow
import cta.app.DeliveryWindowRepository
import cta.app.DeviceRequestRepository
import cta.app.DeviceRequestStatus
import cta.app.graphql.queries.DeliveryBlockedDateGql
import cta.app.graphql.queries.DeliveryConfigGql
import cta.app.graphql.queries.DeliveryWindowAdminGql
import cta.app.graphql.queries.toAdminGql
import cta.app.graphql.queries.toGql
import cta.app.services.FilterService
import cta.toNullable
import graphql.GraphQLError
import graphql.GraphqlErrorBuilder
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.GraphQlExceptionHandler
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.execution.ErrorType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated
import java.time.LocalDate

/** Raised for admin-side delivery edits that can't be applied; surfaced as a BAD_REQUEST error. */
class DeliveryAdminException(
    message: String,
) : RuntimeException(message)

/**
 * Admin-only write side of the delivery-slots screen: edit settings, upsert/remove
 * windows, and manage blocked dates. Guarded by write:organisations, matching the
 * rest of the distributions & deliveries surface.
 */
@Controller
@Validated
@Transactional
class DeliveryAdminMutations(
    private val config: DeliveryConfigRepository,
    private val windows: DeliveryWindowRepository,
    private val blockedDates: DeliveryBlockedDateRepository,
    private val deviceRequests: DeviceRequestRepository,
    private val bookings: DeliveryBookingRepository,
    private val overrides: DeliveryBookingOverrideRepository,
    private val filterService: FilterService,
) {
    @PreAuthorize("hasAnyAuthority('write:organisations')")
    @MutationMapping
    fun updateDeliveryConfig(
        @Argument @Valid data: UpdateDeliveryConfigInput,
    ): DeliveryConfigGql {
        val entity = config.getConfig()
        entity.enabled = data.enabled
        entity.daysOfWeek = normaliseDaysOfWeek(data.daysOfWeek)
        entity.leadTimeDays = data.leadTimeDays.coerceAtLeast(0)
        entity.advanceDays = data.advanceDays.coerceIn(1, 60)
        return config.save(entity).toGql()
    }

    @PreAuthorize("hasAnyAuthority('write:organisations')")
    @MutationMapping
    fun saveDeliveryWindow(
        @Argument @Valid data: DeliveryWindowInput,
    ): DeliveryWindowAdminGql {
        val entity =
            data.id?.toLongOrNull()?.let { id ->
                windows.findById(id).toNullable()
                    ?: throw DeliveryAdminException("No delivery window with id $id")
            } ?: DeliveryWindow()
        entity.name = data.name
        entity.startTime = data.startTime
        entity.endTime = data.endTime
        entity.icon = data.icon ?: entity.icon
        entity.capacity = data.capacity.coerceAtLeast(0)
        entity.sortOrder = data.sortOrder ?: entity.sortOrder
        entity.active = data.active ?: entity.active
        return windows.save(entity).toAdminGql()
    }

    @PreAuthorize("hasAnyAuthority('write:organisations')")
    @MutationMapping
    fun deleteDeliveryWindow(
        @Argument id: String,
    ): Boolean {
        val windowId = id.toLongOrNull() ?: return false
        if (bookings.countByWindowId(windowId) > 0) {
            throw DeliveryAdminException("This window already has bookings — deactivate it instead of deleting it.")
        }
        windows.deleteById(windowId)
        return true
    }

    @PreAuthorize("hasAnyAuthority('write:organisations')")
    @MutationMapping
    fun addDeliveryBlockedDate(
        @Argument @Valid data: DeliveryBlockedDateInput,
    ): DeliveryBlockedDateGql {
        val date =
            try {
                LocalDate.parse(data.date)
            } catch (e: Exception) {
                throw DeliveryAdminException("Invalid date: ${data.date}")
            }
        // One row per date — re-blocking a date just updates its reason.
        val existing = blockedDates.findAllByOrderByBlockedDateAsc().firstOrNull { it.blockedDate == date }
        val entity = existing ?: DeliveryBlockedDate(blockedDate = date)
        entity.reason = data.reason
        return blockedDates.save(entity).toGql()
    }

    @PreAuthorize("hasAnyAuthority('write:organisations')")
    @MutationMapping
    fun deleteDeliveryBlockedDate(
        @Argument id: String,
    ): Boolean {
        val blockedId = id.toLongOrNull() ?: return false
        blockedDates.deleteById(blockedId)
        return true
    }

    /**
     * Deleting a booking does not unwind what it wrote onto the linked device request, so a
     * delete would leave that request claiming a delivery is arranged for a date that no longer
     * exists anywhere (issue #155, Q1). Refuse while the request still shows that status, and say
     * what to do instead — the same shape as deleteDeliveryWindow refusing a window with bookings
     * — unless the caller opts in via [clearRequestDelivery], in which case the request is
     * unwound in the same transaction rather than left half-pointing at a deleted booking.
     *
     * Deliberately narrow: only a request still sitting in PROCESSING_COLLECTION_DELIVERY_ARRANGED
     * is protected. Unmatched bookings, and bookings whose request staff have already moved on,
     * stay freely deletable — deletion is the only way to free a slot, since capacity counts rows
     * and a booking has no cancelled state.
     */
    @PreAuthorize("hasAnyAuthority('write:organisations')")
    @MutationMapping
    fun deleteDeliveryBooking(
        @Argument id: String,
        @Argument clearRequestDelivery: Boolean? = false,
    ): Boolean {
        val bookingId = id.toLongOrNull() ?: return false
        // A missing booking stays a silent no-op returning true, matching deleteById's behaviour
        // on the sibling delete mutations.
        val booking = bookings.findById(bookingId).toNullable()
        if (booking != null) {
            val linked = deviceRequests.findById(booking.ctaReference).toNullable()
            if (linked?.status == DeviceRequestStatus.PROCESSING_COLLECTION_DELIVERY_ARRANGED) {
                if (clearRequestDelivery != true) {
                    throw DeliveryAdminException(
                        "Device request ${linked.id} still shows a delivery as arranged for this booking. " +
                            "Update that request's status first, then delete the booking.",
                    )
                }
                // Rolled back to PROCESSING_EQUALITIES_DATA_COMPLETE: the status that immediately
                // precedes PROCESSING_COLLECTION_DELIVERY_ARRANGED in the normal flow (see
                // DeviceRequestService.markRequestStepsCompleted, the only other place that sets
                // it) — i.e. "processed, awaiting a collection/delivery method", which is exactly
                // what unwinding an arranged delivery leaves the request as.
                linked.status = DeviceRequestStatus.PROCESSING_EQUALITIES_DATA_COMPLETE
                linked.collectionDate = null
                linked.collectionMethod = null
                linked.collectionContactName = null
                deviceRequests.save(linked)
            }
        }
        bookings.deleteById(bookingId)
        return true
    }

    /**
     * Grants a one-off exemption from the one-booking-per-CTA-reference rule enforced in
     * DeliveryMutations. Idempotent: granting again while an unconsumed override already exists
     * for this reference does nothing rather than erroring or stacking a second exemption — the
     * rule only ever checks for the *existence* of an unconsumed row, not a count.
     */
    @PreAuthorize("hasAnyAuthority('write:organisations')")
    @MutationMapping
    fun allowAdditionalDeliveryBooking(
        @Argument ctaReference: Long,
        @Argument note: String?,
    ): Boolean {
        if (overrides.findFirstByCtaReferenceAndConsumedAtIsNull(ctaReference) != null) return true
        val createdBy =
            filterService
                .userDetails()
                .name
                .ifBlank { filterService.userDetails().email }
                .takeIf { it.isNotBlank() }
        overrides.save(
            DeliveryBookingOverride(
                ctaReference = ctaReference,
                note = note,
                createdBy = createdBy,
            ),
        )
        return true
    }

    @GraphQlExceptionHandler
    fun handleDeliveryAdminError(ex: DeliveryAdminException): GraphQLError =
        GraphqlErrorBuilder
            .newError()
            .errorType(ErrorType.BAD_REQUEST)
            .message(ex.message)
            .build()
}

private val DOW_REGEX = Regex("[1-7]")

/** Keep only valid ISO day-of-week digits (1=Mon..7=Sun), de-duplicated, e.g. "2,4". */
private fun normaliseDaysOfWeek(raw: String): String =
    raw
        .split(",")
        .map { it.trim() }
        .filter { it.matches(DOW_REGEX) }
        .distinct()
        .joinToString(",")

data class UpdateDeliveryConfigInput(
    var enabled: Boolean = true,
    var daysOfWeek: String = "",
    var leadTimeDays: Int = 1,
    var advanceDays: Int = 4,
)

data class DeliveryWindowInput(
    var id: String? = null,
    @get:NotBlank var name: String = "",
    @get:NotBlank var startTime: String = "",
    @get:NotBlank var endTime: String = "",
    var icon: String? = null,
    var capacity: Int = 0,
    var sortOrder: Int? = null,
    var active: Boolean? = null,
)

data class DeliveryBlockedDateInput(
    @get:NotBlank var date: String = "",
    var reason: String? = null,
)
