package cta.app.services

import cta.app.CLOSED_REQUEST_STATUSES
import cta.app.CollectionMethod
import cta.app.DeliveryBlockedDateRepository
import cta.app.DeliveryBooking
import cta.app.DeliveryBookingRepository
import cta.app.DeliveryConfigRepository
import cta.app.DeliveryDayBoroughRepository
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
private const val CONTACT_PHONE = "020 3488 7742"
private val logger = KotlinLogging.logger {}

/** Result of [DeliveryService.checkBookingEligibility]. [message] is set only when ineligible. */
data class BookingEligibility(
    val eligible: Boolean,
    val message: String?,
)

/**
 * Result of [DeliveryService.checkBoroughDaySchedule]. [borough] is set only when [allowed] is
 * false, so callers have what they need to build a rejection message without a second lookup.
 */
data class BoroughDaySchedule(
    val allowed: Boolean,
    val borough: String? = null,
)

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
    private val dayBoroughs: DeliveryDayBoroughRepository,
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

    /**
     * [ctaReference] is optional (sheet row 23): when supplied, days whose weekday is restricted
     * away from that reference's borough are dropped — see [checkBoroughDaySchedule].
     */
    fun availability(
        today: LocalDate = LocalDate.now(),
        ctaReference: Long? = null,
    ): List<DayAvailability> {
        val activeWindows = windows.findByActiveTrueOrderBySortOrderAsc()
        if (activeWindows.isEmpty()) return emptyList()

        return offeredDates(today)
            .filter { date -> checkBoroughDaySchedule(ctaReference, date).allowed }
            .map { date ->
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

    /**
     * The status gate a delivery booking's ctaReference must clear (sheet row 24): eligible only
     * when a DeviceRequest exists with id == [ctaReference] and status exactly
     * PROCESSING_EQUALITIES_DATA_COMPLETE. Shared by submitDeliveryBookingPublic and
     * deliveryBookingEligibilityPublic so the two can never drift apart. Deliberately does NOT
     * fail open — unlike the borough gate — and the message is identical whether the reference
     * doesn't exist or is just in the wrong status, so an unauthenticated caller can't use it to
     * fish for which references exist.
     */
    fun checkBookingEligibility(ctaReference: Long): BookingEligibility {
        val request = deviceRequests.findById(ctaReference).orElse(null)
        return if (request?.status == DeviceRequestStatus.PROCESSING_EQUALITIES_DATA_COMPLETE) {
            BookingEligibility(eligible = true, message = null)
        } else {
            BookingEligibility(eligible = false, message = ineligibleBookingMessage(ctaReference))
        }
    }

    /**
     * Per-weekday borough restriction (sheet row 23), shared by deliveryAvailabilityPublic and
     * submitDeliveryBookingPublic so the two can never drift apart — mirrors how
     * [checkBookingEligibility] is shared. Off by default via
     * [cta.app.DeliveryConfig.boroughSchedulingEnabled], and independent of the
     * borough-availability-rules feature flag ([BoroughAvailabilityRules]).
     *
     * Fails open at every step: disabled config, no reference, an unresolvable request, or a
     * blank borough all return allowed. We already accepted the linked request, so wrongly
     * turning someone away is worse than letting a booking through we'd rather have scheduled
     * differently. A weekday with no configured boroughs is open to every borough.
     */
    fun checkBoroughDaySchedule(
        ctaReference: Long?,
        date: LocalDate,
    ): BoroughDaySchedule {
        if (!config.getConfig().boroughSchedulingEnabled) return BoroughDaySchedule(allowed = true)
        if (ctaReference == null) return BoroughDaySchedule(allowed = true)
        val borough =
            try {
                deviceRequests.findById(ctaReference).orElse(null)?.borough
            } catch (e: Exception) {
                logger.warn(e) { "Borough day schedule: failed to look up device request $ctaReference; letting it through." }
                null
            }
        if (borough.isNullOrBlank()) return BoroughDaySchedule(allowed = true)

        val restricted = dayBoroughs.findAllByDayOfWeek(date.dayOfWeek.value).map { it.borough }.toSet()
        if (restricted.isEmpty()) return BoroughDaySchedule(allowed = true)

        return BoroughDaySchedule(allowed = borough in restricted, borough = borough)
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

private fun ineligibleBookingMessage(ctaReference: Long): String =
    "You are not able to book a delivery for request ID '$ctaReference' at this time. Please check the number " +
        "is correct, and try again if not. Otherwise please contact distributions@communitytechaid.org.uk " +
        "quoting your request ID for further information"
