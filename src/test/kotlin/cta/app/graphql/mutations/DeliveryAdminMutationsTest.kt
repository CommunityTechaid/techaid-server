package cta.app.graphql.mutations

import cta.app.CollectionMethod
import cta.app.DeliveryBlockedDateRepository
import cta.app.DeliveryBooking
import cta.app.DeliveryBookingRepository
import cta.app.DeliveryConfigRepository
import cta.app.DeliveryDayBoroughRepository
import cta.app.DeliveryWindowRepository
import cta.app.DeviceRequest
import cta.app.DeviceRequestItems
import cta.app.DeviceRequestRepository
import cta.app.DeviceRequestStatus
import cta.app.ReferringOrganisationContact
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import java.time.Instant
import java.util.Optional

/**
 * Unit tests for deleteDeliveryBooking against mocked repositories — no Spring context, no
 * database. Mirrors the sibling deleteDeliveryWindow/deleteDeliveryBlockedDate style: deleteById
 * on Spring Data JPA 3.4 is a silent no-op for a missing row (findById(id).ifPresent(delete)), so
 * this mutation only reports false for a malformed (non-numeric) id, not a missing one.
 */
class DeliveryAdminMutationsTest {
    private val bookings = mock(DeliveryBookingRepository::class.java)
    private val deviceRequests = mock(DeviceRequestRepository::class.java)

    private val mutations =
        DeliveryAdminMutations(
            config = mock(DeliveryConfigRepository::class.java),
            windows = mock(DeliveryWindowRepository::class.java),
            blockedDates = mock(DeliveryBlockedDateRepository::class.java),
            deviceRequests = deviceRequests,
            bookings = bookings,
            dayBoroughs = mock(DeliveryDayBoroughRepository::class.java),
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

    /**
     * Deleting the booking would leave its device request claiming a delivery is arranged for a
     * date that no longer exists anywhere (issue #155, Q1). Refuse, and say what to do instead —
     * mirroring deleteDeliveryWindow, which already refuses to delete a window that has bookings.
     * Only bookings whose request still shows the arranged status are protected; unmatched or
     * junk bookings stay freely deletable, since deletion is the only way to free a slot.
     */
    @Test
    fun `refuses to delete a booking whose device request still shows a delivery arranged`() {
        val booking = DeliveryBooking(id = 42, ctaReference = 904310L)
        val request = mock(DeviceRequest::class.java)
        given(request.id).willReturn(904310L)
        given(request.status).willReturn(DeviceRequestStatus.PROCESSING_COLLECTION_DELIVERY_ARRANGED)
        given(bookings.findById(42L)).willReturn(Optional.of(booking))
        given(deviceRequests.findById(904310L)).willReturn(Optional.of(request))

        val error =
            assertThrows(DeliveryAdminException::class.java) {
                mutations.deleteDeliveryBooking("42")
            }

        assertTrue(error.message!!.contains("904310"))
        verify(bookings, never()).deleteById(anyLong())
    }

    @Test
    fun `deletes a booking whose device request has moved on from the arranged status`() {
        val booking = DeliveryBooking(id = 43, ctaReference = 904311L)
        val request = mock(DeviceRequest::class.java)
        given(request.status).willReturn(DeviceRequestStatus.REQUEST_COMPLETED)
        given(bookings.findById(43L)).willReturn(Optional.of(booking))
        given(deviceRequests.findById(904311L)).willReturn(Optional.of(request))

        val result = mutations.deleteDeliveryBooking("43")

        assertTrue(result)
        verify(bookings).deleteById(43L)
    }

    /**
     * clearRequestDelivery=true unwinds the request instead of refusing: status rolls back to
     * PROCESSING_EQUALITIES_DATA_COMPLETE and the collection fields it wrote are cleared, then
     * the booking is deleted.
     */
    @Test
    fun `clearRequestDelivery unwinds the request and deletes the booking`() {
        val booking = DeliveryBooking(id = 44, ctaReference = 904312L)
        val request =
            DeviceRequest(
                id = 904312L,
                deviceRequestItems = DeviceRequestItems(),
                referringOrganisationContact = mock(ReferringOrganisationContact::class.java),
                clientRef = "ref",
                borough = null,
                details = "",
                deviceRequestNeeds = null,
                status = DeviceRequestStatus.PROCESSING_COLLECTION_DELIVERY_ARRANGED,
                collectionDate = Instant.now(),
                collectionMethod = CollectionMethod.DELIVERY,
                collectionContactName = "Someone",
            )
        given(bookings.findById(44L)).willReturn(Optional.of(booking))
        given(deviceRequests.findById(904312L)).willReturn(Optional.of(request))

        val result = mutations.deleteDeliveryBooking("44", clearRequestDelivery = true)

        assertTrue(result)
        assertEquals(DeviceRequestStatus.PROCESSING_EQUALITIES_DATA_COMPLETE, request.status)
        assertNull(request.collectionDate)
        assertNull(request.collectionMethod)
        assertNull(request.collectionContactName)
        verify(deviceRequests).save(request)
        verify(bookings).deleteById(44L)
    }

    /** clearRequestDelivery defaults to false, so an omitted argument still refuses as before. */
    @Test
    fun `clearRequestDelivery false still refuses, matching the unchanged default behaviour`() {
        val booking = DeliveryBooking(id = 45, ctaReference = 904313L)
        val request = mock(DeviceRequest::class.java)
        given(request.id).willReturn(904313L)
        given(request.status).willReturn(DeviceRequestStatus.PROCESSING_COLLECTION_DELIVERY_ARRANGED)
        given(bookings.findById(45L)).willReturn(Optional.of(booking))
        given(deviceRequests.findById(904313L)).willReturn(Optional.of(request))

        assertThrows(DeliveryAdminException::class.java) {
            mutations.deleteDeliveryBooking("45", clearRequestDelivery = false)
        }
        verify(bookings, never()).deleteById(anyLong())
    }
}
