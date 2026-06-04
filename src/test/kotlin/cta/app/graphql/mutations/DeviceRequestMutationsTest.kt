package cta.app.graphql.mutations

import cta.app.DeviceRequest
import cta.app.DeviceRequestItems
import cta.app.ReferringOrganisationContact
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.time.Instant

/**
 * Unit tests for UpdateDeviceRequestInput.apply(). updateDeviceRequest is a full-replace
 * mutation, so an explicit null collectionDate must CLEAR the booking date (issue: operators
 * could not remove a collection/delivery booking — see techaid-dashboard #42).
 */
class DeviceRequestMutationsTest {
    private fun entityWithDate(date: Instant?): DeviceRequest =
        DeviceRequest(
            id = 1L,
            deviceRequestItems = DeviceRequestItems(laptops = 1),
            referringOrganisationContact = mock(ReferringOrganisationContact::class.java),
            clientRef = "REF001",
            borough = "Lambeth",
            details = "test",
            deviceRequestNeeds = null,
            collectionDate = date,
        )

    private fun input(collectionDate: String?): UpdateDeviceRequestInput =
        UpdateDeviceRequestInput(
            id = 1L,
            deviceRequestItems = DeviceRequestItemsInput(laptops = 1),
            isSales = false,
            clientRef = "REF001",
            borough = "Lambeth",
            details = "test",
            collectionDate = collectionDate,
        )

    @Test
    fun `apply clears collectionDate when input is null`() {
        val existing = Instant.parse("2026-01-26T13:23:00Z")
        val entity = entityWithDate(existing)

        input(collectionDate = null).apply(entity)

        assertNull(entity.collectionDate, "explicit null must clear the booking date")
    }

    @Test
    fun `apply sets collectionDate when input has a date`() {
        val entity = entityWithDate(null)

        input(collectionDate = "2027-03-03T09:00:00Z").apply(entity)

        assertEquals(Instant.parse("2027-03-03T09:00:00Z"), entity.collectionDate)
    }

    @Test
    fun `apply overwrites an existing collectionDate with a new one`() {
        val entity = entityWithDate(Instant.parse("2026-01-26T13:23:00Z"))

        input(collectionDate = "2027-03-03T09:00:00Z").apply(entity)

        assertEquals(Instant.parse("2027-03-03T09:00:00Z"), entity.collectionDate)
    }
}
