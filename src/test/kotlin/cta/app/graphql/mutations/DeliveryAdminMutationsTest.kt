package cta.app.graphql.mutations

import cta.app.DeliveryBlockedDateRepository
import cta.app.DeliveryBookingRepository
import cta.app.DeliveryConfigRepository
import cta.app.DeliveryWindowRepository
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions

/**
 * Unit tests for deleteDeliveryBooking against mocked repositories — no Spring context, no
 * database. Mirrors the sibling deleteDeliveryWindow/deleteDeliveryBlockedDate style: deleteById
 * on Spring Data JPA 3.4 is a silent no-op for a missing row (findById(id).ifPresent(delete)), so
 * this mutation only reports false for a malformed (non-numeric) id, not a missing one.
 */
class DeliveryAdminMutationsTest {
    private val bookings = mock(DeliveryBookingRepository::class.java)

    private val mutations =
        DeliveryAdminMutations(
            config = mock(DeliveryConfigRepository::class.java),
            windows = mock(DeliveryWindowRepository::class.java),
            blockedDates = mock(DeliveryBlockedDateRepository::class.java),
            bookings = bookings,
        )

    @Test
    fun `deletes a booking by id`() {
        val result = mutations.deleteDeliveryBooking("42")

        assertTrue(result)
        verify(bookings).deleteById(42L)
    }

    @Test
    fun `returns false for a malformed id without touching the repository`() {
        val result = mutations.deleteDeliveryBooking("not-a-number")

        assertFalse(result)
        verifyNoInteractions(bookings)
    }
}
