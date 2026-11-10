package cta.app.graphql.mutations

import cta.app.DeliveryBlockedDate
import cta.app.DeliveryBlockedDateRepository
import cta.app.DeliveryBooking
import cta.app.DeliveryBookingRepository
import cta.app.DeliveryConfigRepository
import cta.app.DeliveryDayBorough
import cta.app.DeliveryDayBoroughRepository
import cta.app.DeliveryWindow
import cta.app.DeliveryWindowRepository
import cta.app.DeviceRequestRepository
import cta.app.DeviceRequestStatus
import cta.app.graphql.queries.DeliveryBlockedDateGql
import cta.app.graphql.queries.DeliveryBookingAdminGql
import cta.app.graphql.queries.DeliveryConfigGql
import cta.app.graphql.queries.DeliveryDayBoroughsGql
import cta.app.graphql.queries.DeliveryWindowAdminGql
import cta.app.graphql.queries.toAdminGql
import cta.app.graphql.queries.toGql
import cta.app.services.AdminSettingAuditService
import cta.app.services.DeliveryService
import cta.app.services.FilterService
import cta.toNullable
import graphql.GraphQLError
import graphql.GraphqlErrorBuilder
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.GraphQlExceptionHandler
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.execution.ErrorType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated
import java.time.LocalDate
import java.time.format.DateTimeParseException

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
    private val dayBoroughs: DeliveryDayBoroughRepository,
    private val delivery: DeliveryService,
    private val filterService: FilterService,
    private val audit: AdminSettingAuditService,
) {
    @PreAuthorize("hasAnyAuthority('write:organisations')")
    @MutationMapping
    fun updateDeliveryConfig(
        @Argument @Valid data: UpdateDeliveryConfigInput,
    ): DeliveryConfigGql {
        val entity = config.getConfig()
        val before = AdminSettingAuditService.deliveryConfig(entity)
        entity.enabled = data.enabled
        entity.daysOfWeek = normaliseDaysOfWeek(data.daysOfWeek)
        entity.leadTimeDays = data.leadTimeDays.coerceAtLeast(0)
        entity.advanceDays = data.advanceDays.coerceIn(1, 60)
        // null means "not supplied" (older dashboard builds predate this field) — leave the
        // existing value alone rather than falling back to a default that would silently
        // disable borough scheduling on every save from such a client.
        data.boroughSchedulingEnabled?.let { entity.boroughSchedulingEnabled = it }
        val saved = config.save(entity)
        audit.record(before, AdminSettingAuditService.deliveryConfig(saved))
        return saved.toGql()
    }

    /**
     * Replaces the whole set of allowed boroughs for [dayOfWeek] (sheet row 23): delete-then-
     * insert, matching the "the caller sends the full desired state" shape the dashboard already
     * uses for daysOfWeek. An empty [boroughs] clears the restriction, opening the weekday back
     * up to every borough — see DeliveryService.checkBoroughDaySchedule.
     */
    @PreAuthorize("hasAnyAuthority('write:organisations')")
    @MutationMapping
    fun setDeliveryDayBoroughs(
        @Argument dayOfWeek: Int,
        @Argument boroughs: List<String>,
    ): DeliveryDayBoroughsGql {
        val before = AdminSettingAuditService.deliveryDayBoroughs(dayBoroughs.findAllByDayOfWeek(dayOfWeek))
        dayBoroughs.deleteByDayOfWeek(dayOfWeek)
        // Hibernate flushes inserts before deletes; without this the re-inserts of any borough
        // that was already stored hit ux_delivery_day_boroughs_day_borough (#360).
        dayBoroughs.flush()
        val distinct = boroughs.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val saved = distinct.map { borough -> dayBoroughs.save(DeliveryDayBorough(dayOfWeek = dayOfWeek, borough = borough)) }
        audit.record(before, AdminSettingAuditService.deliveryDayBoroughs(saved))
        return DeliveryDayBoroughsGql(dayOfWeek = dayOfWeek, boroughs = distinct)
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
        val before = AdminSettingAuditService.deliveryWindow(entity.takeIf { it.id != 0L })
        entity.name = data.name
        entity.startTime = data.startTime
        entity.endTime = data.endTime
        entity.icon = data.icon ?: entity.icon
        entity.capacity = data.capacity.coerceAtLeast(0)
        entity.sortOrder = data.sortOrder ?: entity.sortOrder
        entity.active = data.active ?: entity.active
        val saved = windows.save(entity)
        audit.record(before, AdminSettingAuditService.deliveryWindow(saved))
        return saved.toAdminGql()
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
        val before = AdminSettingAuditService.deliveryWindow(windows.findById(windowId).toNullable())
        windows.deleteById(windowId)
        audit.record(before, emptyMap())
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
        val before = AdminSettingAuditService.deliveryBlockedDates(listOfNotNull(existing))
        val entity = existing ?: DeliveryBlockedDate(blockedDate = date)
        entity.reason = data.reason
        val saved = blockedDates.save(entity)
        audit.record(before, AdminSettingAuditService.deliveryBlockedDates(listOf(saved)))
        return saved.toGql()
    }

    @PreAuthorize("hasAnyAuthority('write:organisations')")
    @MutationMapping
    fun deleteDeliveryBlockedDate(
        @Argument id: String,
    ): Boolean {
        val blockedId = id.toLongOrNull() ?: return false
        val before = AdminSettingAuditService.deliveryBlockedDates(listOfNotNull(blockedDates.findById(blockedId).toNullable()))
        blockedDates.deleteById(blockedId)
        audit.record(before, emptyMap())
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
     * Admin exception booking (#73). Policy (Tony, 2026-11-04): admins only; any day and any
     * existing window; NOT counted against capacity, so the public page's slots are untouched;
     * the normal confirmation email and request update; recorded as an exception with the admin's
     * email. The public gates (capacity, offered days, borough schedule, request status, Turnstile,
     * rate limit, feature flag) deliberately don't apply. The only checks are the ones that stop
     * a typo producing a booking that links to nothing.
     */
    @PreAuthorize("hasAuthority('app:admin')")
    @MutationMapping
    fun submitDeliveryBookingAdmin(
        @Argument @Valid input: DeliveryBookingAdminInput,
    ): DeliveryBookingAdminGql {
        val date =
            try {
                LocalDate.parse(input.date)
            } catch (e: DateTimeParseException) {
                throw DeliveryAdminException("Invalid delivery date: ${input.date}")
            }
        if (date.isBefore(LocalDate.now())) throw DeliveryAdminException("A delivery can't be booked in the past ($date).")
        val window =
            input.windowId.toLongOrNull()?.let { windows.findById(it).toNullable() }
                ?: throw DeliveryAdminException("Unknown delivery window: ${input.windowId}")
        if (!deviceRequests.existsById(input.ctaReference)) {
            throw DeliveryAdminException("No device request has the ID ${input.ctaReference}.")
        }
        val admin = filterService.userDetails()

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
                    adminException = true,
                    bookedBy = admin.email.ifBlank { admin.name }.ifBlank { null },
                    exceptionReason = input.exceptionReason?.trim()?.ifBlank { null },
                ),
            )
        delivery.markCollectionDeliveryArranged(saved, window, date)
        delivery.sendConfirmationEmail(saved, window, date)
        val matched = deviceRequests.findAllById(listOf(saved.ctaReference)).associateBy { it.id }
        return saved.toAdminGql(delivery.dayLabel(date), matched)
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
    var boroughSchedulingEnabled: Boolean? = null,
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

data class DeliveryBookingAdminInput(
    @get:NotBlank @get:Size(max = 32) var date: String = "",
    @get:NotBlank @get:Size(max = 32) var windowId: String = "",
    @get:NotBlank @get:Size(max = 100) var firstName: String = "",
    @get:NotBlank @get:Size(max = 100) var surname: String = "",
    @get:NotBlank @get:Email @get:Size(max = 254) var email: String = "",
    @get:NotBlank @get:Size(max = 32) var phone: String = "",
    @get:NotBlank @get:Size(max = 1000) var address: String = "",
    @get:Size(max = 2000) var accessNotes: String? = null,
    @get:Positive var ctaReference: Long = 0,
    @get:Size(max = 1000) var exceptionReason: String? = null,
)

data class DeliveryBlockedDateInput(
    @get:NotBlank var date: String = "",
    var reason: String? = null,
)
