package cta.app.services

import cta.app.DeliveryBlockedDateRepository
import cta.app.DeliveryBookingRepository
import cta.app.DeliveryConfigRepository
import cta.app.DeliveryWindow
import cta.app.DeliveryWindowRepository
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val DAY_LABEL_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)

/** Remaining capacity for one window on one day. */
data class WindowAvailability(
    val window: DeliveryWindow,
    val spotsRemaining: Int,
)

/** A bookable day and the availability of each of its active windows. */
data class DayAvailability(
    val date: LocalDate,
    val windows: List<WindowAvailability>,
)

/**
 * Computes public delivery availability from the seeded config/windows and existing
 * bookings, and validates that a chosen date is genuinely bookable. Kept free of any
 * GraphQL types so it can be reused by a future admin screen or booking API.
 */
@Service
class DeliveryService(
    private val config: DeliveryConfigRepository,
    private val windows: DeliveryWindowRepository,
    private val blockedDates: DeliveryBlockedDateRepository,
    private val bookings: DeliveryBookingRepository,
) {
    /** ISO day-of-week numbers (1=Mon..7=Sun) the charity delivers on. */
    fun deliveryDaysOfWeek(): Set<Int> =
        config
            .getConfig()
            .daysOfWeek
            .split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .toSet()

    fun availability(today: LocalDate = LocalDate.now()): List<DayAvailability> {
        val cfg = config.getConfig()
        if (!cfg.enabled) return emptyList()
        val days = deliveryDaysOfWeek()
        if (days.isEmpty()) return emptyList()
        val activeWindows = windows.findByActiveTrueOrderBySortOrderAsc()
        if (activeWindows.isEmpty()) return emptyList()

        val blocked = blockedDates.findAllByBlockedDateGreaterThanEqual(today).map { it.blockedDate }.toSet()

        val result = mutableListOf<DayAvailability>()
        var cursor = today.plusDays(cfg.leadTimeDays.toLong())
        // Bound the scan so a misconfiguration (e.g. no matching days) can never loop forever.
        var guard = 0
        while (result.size < cfg.advanceDays && guard < 400) {
            guard++
            if (cursor.dayOfWeek.value in days && cursor !in blocked) {
                val windowAvailability =
                    activeWindows.map { window ->
                        val booked = bookings.countByDeliveryDateAndWindowId(cursor, window.id)
                        WindowAvailability(window, (window.capacity - booked).toInt().coerceAtLeast(0))
                    }
                result.add(DayAvailability(cursor, windowAvailability))
            }
            cursor = cursor.plusDays(1)
        }
        return result
    }

    /** Whether [date] is an enabled, non-blocked delivery day at or beyond the lead time. */
    fun isBookableDay(
        date: LocalDate,
        today: LocalDate = LocalDate.now(),
    ): Boolean {
        val cfg = config.getConfig()
        if (!cfg.enabled) return false
        if (date.isBefore(today.plusDays(cfg.leadTimeDays.toLong()))) return false
        if (date.dayOfWeek.value !in deliveryDaysOfWeek()) return false
        if (blockedDates.existsByBlockedDate(date)) return false
        return true
    }

    fun dayLabel(date: LocalDate): String = date.format(DAY_LABEL_FORMAT)

    fun dayOfWeekName(date: LocalDate): String = date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
}
