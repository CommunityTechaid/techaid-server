package cta.app.graphql.queries

import cta.app.DeliveryBlockedDate
import cta.app.DeliveryBlockedDateRepository
import cta.app.DeliveryBooking
import cta.app.DeliveryBookingRepository
import cta.app.DeliveryConfig
import cta.app.DeliveryConfigRepository
import cta.app.DeliveryWindow
import cta.app.DeliveryWindowRepository
import cta.app.services.DeliveryService
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import java.time.LocalDate

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
    private val delivery: DeliveryService,
) {
    @PreAuthorize("hasAnyAuthority('app:admin', 'read:organisations')")
    @QueryMapping
    fun deliveryConfig(): DeliveryConfigGql = config.getConfig().toGql()

    @PreAuthorize("hasAnyAuthority('app:admin', 'read:organisations')")
    @QueryMapping
    fun deliveryWindowsAdmin(): List<DeliveryWindowAdminGql> = windows.findAllByOrderBySortOrderAsc().map { it.toAdminGql() }

    @PreAuthorize("hasAnyAuthority('app:admin', 'read:organisations')")
    @QueryMapping
    fun deliveryBlockedDates(): List<DeliveryBlockedDateGql> = blockedDates.findAllByOrderByBlockedDateAsc().map { it.toGql() }

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
        return rows.map { it.toAdminGql(delivery.dayLabel(it.deliveryDate)) }
    }
}

data class DeliveryConfigGql(
    val id: String,
    val enabled: Boolean,
    val daysOfWeek: String,
    val leadTimeDays: Int,
    val advanceDays: Int,
    val updatedAt: String?,
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
    val ctaReference: String,
    val createdAt: String?,
)

fun DeliveryConfig.toGql(): DeliveryConfigGql =
    DeliveryConfigGql(
        id = id.toString(),
        enabled = enabled,
        daysOfWeek = daysOfWeek,
        leadTimeDays = leadTimeDays,
        advanceDays = advanceDays,
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

fun DeliveryBooking.toAdminGql(dayLabel: String): DeliveryBookingAdminGql =
    DeliveryBookingAdminGql(
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
    )
