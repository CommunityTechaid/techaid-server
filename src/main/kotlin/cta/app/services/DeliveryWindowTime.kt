package cta.app.services

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Conversion of a [cta.app.DeliveryWindow]'s display-formatted times into real time values.
 *
 * DeliveryWindow stores startTime/endTime as free-form display strings (e.g. "10:00am"), not a
 * structured time type — see V26.07.08.1000__delivery_bookings.sql. Two callers need them as
 * actual times: the .ics calendar invite on the confirmation email (DeliveryCalendarInvite) and
 * the collectionDate stamped on the linked device request (DeliveryService).
 *
 * They share this parser deliberately. A second implementation would eventually drift, and the
 * date shown on the device request would stop matching the calendar invite the booker received.
 *
 * Deliberately Spring-free so DeliveryCalendarInvite stays unit-testable without a context.
 */
internal val LONDON_ZONE: ZoneId = ZoneId.of("Europe/London")

/** Matches the window time storage format seen in the Flyway seed, e.g. "10:00am", "2:00pm". */
internal val WINDOW_TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .appendPattern("h:mma")
        .toFormatter(Locale.ENGLISH)

/** Parses a display time, returning null for anything that doesn't match the expected format. */
internal fun parseWindowTime(value: String): LocalTime? =
    try {
        LocalTime.parse(value.trim(), WINDOW_TIME_FORMAT)
    } catch (e: DateTimeParseException) {
        null
    }

/**
 * The instant a window starts on [date], or null if [startTime] isn't parseable. Callers treat
 * null as "no date recorded" rather than an error — an unparseable window must never fail a
 * booking that has otherwise succeeded.
 */
internal fun windowStartInstant(
    date: LocalDate,
    startTime: String,
): Instant? = parseWindowTime(startTime)?.let { ZonedDateTime.of(date, it, LONDON_ZONE).toInstant() }
