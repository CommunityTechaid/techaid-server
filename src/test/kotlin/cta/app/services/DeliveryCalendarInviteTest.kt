package cta.app.services

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Pure unit tests for the ICS builder — no Spring context needed. Window start/end times are
 * exercised in the exact display-string format the Flyway seed uses (`10:00am`, `1:00pm`, ...),
 * not a structured time type, since that's what [cta.app.DeliveryWindow] actually stores.
 */
class DeliveryCalendarInviteTest {
    private fun build(
        bookingId: Long = 42L,
        deliveryDate: LocalDate = LocalDate.of(2026, 7, 16),
        windowName: String = "Morning window",
        windowStartTime: String = "10:00am",
        windowEndTime: String = "1:00pm",
        address: String = "Flat 2, 14 Coldharbour Lane, London SW9 8PR",
        ctaReference: String = "4298",
        contactPhone: String = "020 3488 2912",
    ) = DeliveryCalendarInvite.build(
        bookingId = bookingId,
        deliveryDate = deliveryDate,
        windowName = windowName,
        windowStartTime = windowStartTime,
        windowEndTime = windowEndTime,
        address = address,
        ctaReference = ctaReference,
        contactPhone = contactPhone,
    )

    @Test
    fun `BST date converts London time to UTC an hour earlier`() {
        // 16 July 2026 is in British Summer Time (UTC+1): 10:00am/1:00pm London = 09:00/12:00 UTC.
        val ics = build(deliveryDate = LocalDate.of(2026, 7, 16), windowStartTime = "10:00am", windowEndTime = "1:00pm")

        assertTrue(ics.contains("DTSTART:20260716T090000Z"))
        assertTrue(ics.contains("DTEND:20260716T120000Z"))
    }

    @Test
    fun `GMT date needs no offset adjustment`() {
        // 15 January 2026 is outside BST (UTC+0): 10:00am London = 10:00 UTC.
        val ics = build(deliveryDate = LocalDate.of(2026, 1, 15), windowStartTime = "10:00am", windowEndTime = "1:00pm")

        assertTrue(ics.contains("DTSTART:20260115T100000Z"))
        assertTrue(ics.contains("DTEND:20260115T130000Z"))
    }

    @Test
    fun `address containing commas and newlines is escaped in LOCATION`() {
        val ics = build(address = "Flat 2, 14 Coldharbour Lane,\nLondon SW9 8PR")

        assertTrue(ics.contains("LOCATION:Flat 2\\, 14 Coldharbour Lane\\,\\nLondon SW9 8PR"))
        // The raw newline must not survive into the LOCATION value — only the escaped \n form.
        assertFalse(ics.lines().any { it.startsWith("LOCATION:") && it != "LOCATION:Flat 2\\, 14 Coldharbour Lane\\,\\nLondon SW9 8PR" })
    }

    @Test
    fun `semicolons and backslashes are escaped too`() {
        val ics = build(address = "Unit A; Block B \\ North Wing")

        assertTrue(ics.contains("LOCATION:Unit A\\; Block B \\\\ North Wing"))
    }

    @Test
    fun `required properties are present with CRLF line endings`() {
        val ics = build(bookingId = 777L)

        assertTrue(ics.contains("UID:booking-777@communitytechaid.org.uk"))
        assertTrue(ics.contains("METHOD:PUBLISH"))
        assertTrue(ics.contains("SUMMARY:Community TechAid device delivery"))
        assertTrue(ics.startsWith("BEGIN:VCALENDAR\r\n"))
        assertTrue(ics.contains("VERSION:2.0\r\n"))
        assertTrue(ics.trimEnd().endsWith("END:VCALENDAR"))
        // Every content line is CRLF-terminated (no bare \n line endings).
        assertFalse(ics.replace("\r\n", "").contains("\n"))
    }

    @Test
    fun `description includes window details, CTA reference and contact phone`() {
        val ics = build(windowName = "Afternoon window", ctaReference = "9911", contactPhone = "020 3488 2912")

        assertTrue(ics.contains("Afternoon window"))
        assertTrue(ics.contains("CTA reference: 9911"))
        assertTrue(ics.contains("020 3488 2912"))
    }

    @Test
    fun `unparsable window time falls back to an all-day event instead of throwing`() {
        val ics = build(deliveryDate = LocalDate.of(2026, 7, 16), windowStartTime = "sometime", windowEndTime = "later")

        assertTrue(ics.contains("DTSTART;VALUE=DATE:20260716"))
        assertTrue(ics.contains("DTEND;VALUE=DATE:20260717"))
        assertFalse(ics.contains("DTSTART:2026"))
    }

    @Test
    fun `blank window time also falls back to an all-day event`() {
        val ics = build(deliveryDate = LocalDate.of(2026, 1, 15), windowStartTime = "", windowEndTime = "")

        assertTrue(ics.contains("DTSTART;VALUE=DATE:20260115"))
        assertTrue(ics.contains("DTEND;VALUE=DATE:20260116"))
    }

    @Test
    fun `UID is stable for the same booking id`() {
        val first = build(bookingId = 5L)
        val second = build(bookingId = 5L)

        val uidLine = { text: String -> text.lines().first { it.startsWith("UID:") } }
        assertEquals(uidLine(first), uidLine(second))
    }
}
