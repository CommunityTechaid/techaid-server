package cta.app.graphql.queries

import cta.app.CLOSED_REQUEST_STATUSES
import cta.app.DeliveryBlockedDate
import cta.app.DeliveryBlockedDateRepository
import cta.app.DeliveryBooking
import cta.app.DeliveryBookingRepository
import cta.app.DeliveryConfig
import cta.app.DeliveryConfigRepository
import cta.app.DeliveryDayBoroughRepository
import cta.app.DeliveryWindow
import cta.app.DeliveryWindowRepository
import cta.app.DeviceRequest
import cta.app.DeviceRequestRepository
import cta.app.DeviceRequestStatus
import cta.app.services.DeliveryService
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Bookings whose linked request has been closed this long or longer are hidden (sheet row 25). */
private const val STALE_BOOKING_THRESHOLD_DAYS = 7L

/** Only these two closed statuses count as "stale" for hiding a booking; others stay visible. */
private val STALE_BOOKING_STATUSES = setOf(DeviceRequestStatus.REQUEST_COMPLETED, DeviceRequestStatus.REQUEST_CANCELLED)

/**
 * Admin-only read side of the delivery-slots screen: settings, windows (incl. inactive),
 * blocked dates, and the bookings that have come in (who signed up for each slot).
 */
@Controller
class DeliveryAdminQueries(
    private val config: DeliveryConfigRepository,
    private val windows: DeliveryWindowRepository,
    private val blockedDates: DeliveryBlockedDateRepository,
    private val bookings: DeliveryBookingRepository,
    private val deviceRequests: DeviceRequestRepository,
    private val delivery: DeliveryService,
    private val dayBoroughs: DeliveryDayBoroughRepository,
) {
    @PreAuthorize("hasAnyAuthority('app:admin', 'read:organisations')")
    @QueryMapping
    fun deliveryConfig(): DeliveryConfigGql = config.getConfig().toGql()

    /** Only weekdays with a configured restriction appear; an absent weekday allows every borough. */
    @PreAuthorize("hasAnyAuthority('app:admin', 'read:organisations')")
    @QueryMapping
    fun deliveryDayBoroughs(): List<DeliveryDayBoroughsGql> =
        dayBoroughs
            .findAllByOrderByDayOfWeekAscBoroughAsc()
            .groupBy { it.dayOfWeek }
            .map { (dayOfWeek, rows) -> DeliveryDayBoroughsGql(dayOfWeek, rows.map { it.borough }) }

    @PreAuthorize("hasAnyAuthority('app:admin', 'read:organisations')")
    @QueryMapping
    fun deliveryWindowsAdmin(): List<DeliveryWindowAdminGql> = windows.findAllByOrderBySortOrderAsc().map { it.toAdminGql() }

    @PreAuthorize("hasAnyAuthority('app:admin', 'read:organisations')")
    @QueryMapping
    fun deliveryBlockedDates(): List<DeliveryBlockedDateGql> = blockedDates.findAllByOrderByBlockedDateAsc().map { it.toGql() }

    /**
     * Excludes bookings whose linked request has been closed as completed or cancelled for
     * [STALE_BOOKING_THRESHOLD_DAYS] or longer (sheet row 25) — display filter only, nothing is
     * deleted. `REQUEST_DECLINED` and `REQUEST_COLLECTION_DELIVERY_FAILED` stay visible. A
     * booking with no resolvable request stays visible too (fails open).
     */
    @PreAuthorize("hasAnyAuthority('app:admin', 'read:organisations')")
    @QueryMapping
    fun deliveryBookingsAdmin(
        @Argument from: String?,
        @Argument to: String?,
    ): List<DeliveryBookingAdminGql> {
        val rows =
            if (from != null && to != null) {
                bookings.findAllByDeliveryDateBetweenOrderByDeliveryDateAscCreatedAtAsc(
                    LocalDate.parse(from),
                    LocalDate.parse(to),
                )
            } else {
                bookings.findAllByOrderByDeliveryDateAscCreatedAtAsc()
            }
        val referencedIds = rows.map { it.ctaReference }.distinct()
        val matchedRequestsById = deviceRequests.findAllById(referencedIds).associateBy { it.id }
        val staleCutoff = Instant.now().minus(STALE_BOOKING_THRESHOLD_DAYS, ChronoUnit.DAYS)
        return rows
            .filterNot { booking ->
                val request = matchedRequestsById[booking.ctaReference]
                request != null && request.status in STALE_BOOKING_STATUSES && request.updatedAt.isBefore(staleCutoff)
            }.map { it.toAdminGql(delivery.dayLabel(it.deliveryDate), matchedRequestsById) }
    }
}

data class DeliveryConfigGql(
    val id: String,
    val enabled: Boolean,
    val daysOfWeek: String,
    val leadTimeDays: Int,
    val advanceDays: Int,
    val boroughSchedulingEnabled: Boolean,
    val updatedAt: String?,
)

data class DeliveryDayBoroughsGql(
    val dayOfWeek: Int,
    val boroughs: List<String>,
)

data class DeliveryWindowAdminGql(
    val id: String,
    val name: String,
    val startTime: String,
    val endTime: String,
    val icon: String,
    val capacity: Int,
    val sortOrder: Int,
    val active: Boolean,
)

data class DeliveryBlockedDateGql(
    val id: String,
    val date: String,
    val reason: String?,
)

data class DeliveryBookingAdminGql(
    val id: String,
    val date: String,
    val dayLabel: String,
    val window: DeliveryWindowAdminGql?,
    val firstName: String,
    val surname: String,
    val email: String,
    val phone: String,
    val address: String,
    val accessNotes: String?,
    val ctaReference: Long,
    val createdAt: String?,
    val matchedRequestId: String?,
    val matchedRequestStatus: String?,
    val matchedRequestOpen: Boolean?,
)

fun DeliveryConfig.toGql(): DeliveryConfigGql =
    DeliveryConfigGql(
        id = id.toString(),
        enabled = enabled,
        daysOfWeek = daysOfWeek,
        leadTimeDays = leadTimeDays,
        advanceDays = advanceDays,
        boroughSchedulingEnabled = boroughSchedulingEnabled,
        updatedAt = updatedAt.toString(),
    )

fun DeliveryWindow.toAdminGql(): DeliveryWindowAdminGql =
    DeliveryWindowAdminGql(
        id = id.toString(),
        name = name,
        startTime = startTime,
        endTime = endTime,
        icon = icon,
        capacity = capacity,
        sortOrder = sortOrder,
        active = active,
    )

fun DeliveryBlockedDate.toGql(): DeliveryBlockedDateGql =
    DeliveryBlockedDateGql(id = id.toString(), date = blockedDate.toString(), reason = reason)

fun DeliveryBooking.toAdminGql(
    dayLabel: String,
    matchedRequestsById: Map<Long, DeviceRequest> = emptyMap(),
): DeliveryBookingAdminGql {
    val matchedRequest = matchedRequestsById[ctaReference]
    return DeliveryBookingAdminGql(
        id = id.toString(),
        date = deliveryDate.toString(),
        dayLabel = dayLabel,
        window = window?.toAdminGql(),
        firstName = firstName,
        surname = surname,
        email = email,
        phone = phone,
        address = address,
        accessNotes = accessNotes,
        ctaReference = ctaReference,
        createdAt = createdAt.toString(),
        matchedRequestId = matchedRequest?.id?.toString(),
        matchedRequestStatus = matchedRequest?.status?.name,
        matchedRequestOpen = matchedRequest?.let { it.status !in CLOSED_REQUEST_STATUSES },
    )
}
