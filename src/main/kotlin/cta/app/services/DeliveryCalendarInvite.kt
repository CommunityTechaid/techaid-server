package cta.app.services

import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val UTC_DATE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ENGLISH)
private val ALL_DAY_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ENGLISH)

/**
 * Builds an RFC 5545 iCalendar (.ics) invite for a booked delivery slot so recipients can add
 * it to a calendar app straight from the confirmation email. Deliberately Spring-free so it's
 * unit-testable without a Spring context, and deliberately never throws: [DeliveryWindow]
 * stores its start/end times as free-form display strings (e.g. "10:00am"), not a structured
 * time type, so a value that doesn't match the expected format falls back to an all-day event
 * rather than blowing up — per DeliveryService's posture, a calendar invite must never be able
 * to fail the confirmation email.
 */
object DeliveryCalendarInvite {
    fun build(
        bookingId: Long,
        deliveryDate: LocalDate,
        windowName: String,
        windowStartTime: String,
        windowEndTime: String,
        address: String,
        ctaReference: String,
        contactPhone: String,
    ): String {
        val (dtStartLine, dtEndLine) = timeLines(deliveryDate, windowStartTime, windowEndTime)

        val description =
            listOf(
                "$windowName ($windowStartTime - $windowEndTime)",
                "CTA reference: $ctaReference",
                "Questions? Call us on $contactPhone",
            ).joinToString("\n")

        val lines =
            listOf(
                "BEGIN:VCALENDAR",
                "VERSION:2.0",
                "PRODID:-//Community TechAid//Delivery Booking//EN",
                "METHOD:PUBLISH",
                "CALSCALE:GREGORIAN",
                "BEGIN:VEVENT",
                "UID:booking-$bookingId@communitytechaid.org.uk",
                "DTSTAMP:${nowUtc()}",
                dtStartLine,
                dtEndLine,
                "SUMMARY:Community TechAid device delivery",
                "LOCATION:${escape(address)}",
                "DESCRIPTION:${escape(description)}",
                "END:VEVENT",
                "END:VCALENDAR",
            )

        return lines.joinToString("\r\n", postfix = "\r\n")
    }

    private fun nowUtc(): String = ZonedDateTime.now(ZoneOffset.UTC).format(UTC_DATE_TIME_FORMAT)

    /** Returns the DTSTART/DTEND content lines, falling back to an all-day event if either time can't be parsed. */
    private fun timeLines(
        date: LocalDate,
        startTime: String,
        endTime: String,
    ): Pair<String, String> {
        val start = parseWindowTime(startTime)
        val end = parseWindowTime(endTime)
        if (start == null || end == null) {
            val allDayStart = date.format(ALL_DAY_DATE_FORMAT)
            val allDayEnd = date.plusDays(1).format(ALL_DAY_DATE_FORMAT)
            return "DTSTART;VALUE=DATE:$allDayStart" to "DTEND;VALUE=DATE:$allDayEnd"
        }

        val startUtc = ZonedDateTime.of(date, start, LONDON_ZONE).withZoneSameInstant(ZoneOffset.UTC)
        val endUtc = ZonedDateTime.of(date, end, LONDON_ZONE).withZoneSameInstant(ZoneOffset.UTC)
        return "DTSTART:${startUtc.format(UTC_DATE_TIME_FORMAT)}" to "DTEND:${endUtc.format(UTC_DATE_TIME_FORMAT)}"
    }

    /** RFC 5545 §3.3.11 text escaping: backslash first, then the other reserved characters. */
    private fun escape(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace("\r\n", "\n")
            .replace("\n", "\\n")
}
