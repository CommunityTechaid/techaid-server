package cta.app.services

import cta.app.CLOSED_REQUEST_STATUSES
import cta.app.CollectionMethod
import cta.app.DeliveryBlockedDateRepository
import cta.app.DeliveryBooking
import cta.app.DeliveryBookingRepository
import cta.app.DeliveryConfigRepository
import cta.app.DeliveryWindow
import cta.app.DeliveryWindowRepository
import cta.app.DeviceRequestRepository
import cta.app.DeviceRequestStatus
import jakarta.mail.internet.InternetAddress
import mu.KotlinLogging
import org.springframework.stereotype.Service
import org.thymeleaf.TemplateEngine
import org.thymeleaf.context.Context
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val DAY_LABEL_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)
private const val CONTACT_PHONE = "020 3488 2912"
private val logger = KotlinLogging.logger {}

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
    private val mailService: MailService,
    private val templateEngine: TemplateEngine,
    private val deviceRequests: DeviceRequestRepository,
) {
    /** ISO day-of-week numbers (1=Mon..7=Sun) the charity delivers on. */
    fun deliveryDaysOfWeek(): Set<Int> =
        config
            .getConfig()
            .daysOfWeek
            .split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .toSet()

    /**
     * The dates the public page currently offers: enabled, non-blocked delivery days,
     * starting at the lead time and capped at advanceDays entries.
     */
    fun offeredDates(today: LocalDate = LocalDate.now()): List<LocalDate> {
        val cfg = config.getConfig()
        if (!cfg.enabled) return emptyList()
        val days = deliveryDaysOfWeek()
        if (days.isEmpty()) return emptyList()

        val blocked = blockedDates.findAllByBlockedDateGreaterThanEqual(today).map { it.blockedDate }.toSet()

        val result = mutableListOf<LocalDate>()
        var cursor = today.plusDays(cfg.leadTimeDays.toLong())
        // Bound the scan so a misconfiguration (e.g. no matching days) can never loop forever.
        var guard = 0
        while (result.size < cfg.advanceDays && guard < 400) {
            guard++
            if (cursor.dayOfWeek.value in days && cursor !in blocked) {
                result.add(cursor)
            }
            cursor = cursor.plusDays(1)
        }
        return result
    }

    fun availability(today: LocalDate = LocalDate.now()): List<DayAvailability> {
        val activeWindows = windows.findByActiveTrueOrderBySortOrderAsc()
        if (activeWindows.isEmpty()) return emptyList()

        return offeredDates(today).map { date ->
            val windowAvailability =
                activeWindows.map { window ->
                    val booked = bookings.countByDeliveryDateAndWindowId(date, window.id)
                    WindowAvailability(window, (window.capacity - booked).toInt().coerceAtLeast(0))
                }
            DayAvailability(date, windowAvailability)
        }
    }

    /**
     * Whether [date] is a day the public page currently offers. Sharing [offeredDates]
     * with availability() means a submit can't book a day the page never showed —
     * including days beyond the advance window.
     */
    fun isBookableDay(
        date: LocalDate,
        today: LocalDate = LocalDate.now(),
    ): Boolean = date in offeredDates(today)

    /**
     * Records on the linked device request what the booking arranged: the arranged status, that
     * it is a delivery, when it starts, and who booked it. Without the last three the request
     * claims a delivery is arranged while showing no method and no date, and the only record of
     * either lives on the separate delivery-slots screen (issue #155).
     *
     * A reference that matches no request is logged, not thrown: the booking itself has already
     * succeeded and must stand. Closed requests (completed/declined/cancelled) are left entirely
     * alone so a stray or reused reference can't stamp a delivery onto finished work.
     */
    fun markCollectionDeliveryArranged(
        booking: DeliveryBooking,
        window: DeliveryWindow,
        date: LocalDate,
    ) {
        val request = deviceRequests.findById(booking.ctaReference).orElse(null)
        if (request == null) {
            logger.warn(
                "Delivery booking ${booking.id} references device request ${booking.ctaReference}, " +
                    "which does not exist — nothing was forwarded to a request.",
            )
            return
        }
        if (request.status in CLOSED_REQUEST_STATUSES) {
            logger.warn(
                "Delivery booking ${booking.id} references device request ${request.id}, which is " +
                    "closed (${request.status}) — status, method and date were left untouched.",
            )
            return
        }

        // Null when the window's display-formatted startTime doesn't parse. That leaves the date
        // unset rather than failing a booking that has already been taken and confirmed.
        val collectionStart = windowStartInstant(date, window.startTime)
        if (collectionStart == null) {
            logger.warn(
                "Delivery window ${window.id} has an unparseable startTime '${window.startTime}' — " +
                    "device request ${request.id} was left without a collectionDate.",
            )
        }

        request.status = DeviceRequestStatus.PROCESSING_COLLECTION_DELIVERY_ARRANGED
        request.collectionMethod = CollectionMethod.DELIVERY
        request.collectionDate = collectionStart
        request.collectionContactName = "${booking.firstName} ${booking.surname}".trim()
        deviceRequests.save(request)
    }

    fun dayLabel(date: LocalDate): String = date.format(DAY_LABEL_FORMAT)

    fun dayOfWeekName(date: LocalDate): String = date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)

    /**
     * Emails the booker a confirmation. No-op unless Gmail is enabled (off by default, so
     * UAT stays quiet). Failures are logged, never propagated — a booking must still succeed
     * if the mail send fails.
     */
    fun sendConfirmationEmail(
        booking: DeliveryBooking,
        window: DeliveryWindow,
        date: LocalDate,
    ) {
        if (!mailService.emailEnabled) return

        val context =
            Context().apply {
                setVariable("firstName", booking.firstName)
                setVariable("dayLabel", "${dayLabel(date)} ${date.year}")
                setVariable("windowName", window.name)
                setVariable("windowTime", "${window.startTime} – ${window.endTime}")
                setVariable("address", booking.address)
                setVariable("ctaReference", booking.ctaReference)
                setVariable("phone", CONTACT_PHONE)
            }

        val icsContent =
            try {
                DeliveryCalendarInvite.build(
                    bookingId = booking.id,
                    deliveryDate = date,
                    windowName = window.name,
                    windowStartTime = window.startTime,
                    windowEndTime = window.endTime,
                    address = booking.address,
                    ctaReference = booking.ctaReference.toString(),
                    contactPhone = CONTACT_PHONE,
                )
            } catch (e: Exception) {
                logger.error("Failed to build calendar invite for delivery booking ${booking.id}", e)
                null
            }

        val msg =
            createEmail(
                to = booking.email,
                from = mailService.address,
                subject = "Community TechAid: Your device delivery is booked — ${dayLabel(date)}",
                bodyText = templateEngine.process("email/delivery-confirmation", context),
                mimeType = "html",
                charset = "UTF-8",
                attachment =
                    icsContent?.let {
                        EmailAttachment(
                            filename = "delivery.ics",
                            content = it,
                            contentType = "text/calendar; charset=utf-8; method=PUBLISH",
                        )
                    },
            )

        if (!mailService.bccAddress.isNullOrEmpty()) {
            msg.addRecipient(
                jakarta.mail.Message.RecipientType.BCC,
                InternetAddress(mailService.bccAddress),
            )
        }

        try {
            mailService.sendMessage(msg)
        } catch (e: Exception) {
            logger.error("Failed to send delivery confirmation email", e)
        }
    }
}
