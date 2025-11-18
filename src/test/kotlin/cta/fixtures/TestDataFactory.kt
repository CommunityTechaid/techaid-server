package cta.fixtures

import cta.app.*
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Factory for creating test data objects.
 * Use this to create consistent test data across all tests.
 */
object TestDataFactory {

    fun createDonor(
        id: Long = 1L,
        email: String = "donor@example.com",
        phoneNumber: String = "1234567890",
        name: String = "Test Donor",
        postCode: String = "SW1A 1AA",
        consent: Boolean = true
    ): Donor {
        return Donor().apply {
            this.id = id
            this.email = email
            this.phoneNumber = phoneNumber
            this.name = name
            this.postCode = postCode
            this.consent = consent
            this.createdAt = LocalDateTime.now()
            this.updatedAt = LocalDateTime.now()
        }
    }

    fun createKit(
        id: Long = 1L,
        model: String = "Dell Latitude",
        type: KitType = KitType.LAPTOP,
        status: KitStatus = KitStatus.DONATION_NEW,
        location: String = "London",
        age: Int = 3,
        donor: Donor? = null
    ): Kit {
        return Kit().apply {
            this.id = id
            this.model = model
            this.type = type
            this.status = status
            this.location = location
            this.age = age
            this.donor = donor
            this.createdAt = LocalDateTime.now()
            this.updatedAt = LocalDateTime.now()
        }
    }

    fun createDeviceRequest(
        id: Long = 1L,
        clientRef: String = "REQ-001",
        status: DeviceRequestStatus = DeviceRequestStatus.NEW,
        isSales: Boolean = false,
        details: String = "Test request"
    ): DeviceRequest {
        return DeviceRequest().apply {
            this.id = id
            this.clientRef = clientRef
            this.status = status
            this.isSales = isSales
            this.details = details
            this.createdAt = LocalDateTime.now()
            this.updatedAt = LocalDateTime.now()
        }
    }

    fun createReferringOrganisation(
        id: Long = 1L,
        name: String = "Test Organisation",
        website: String = "https://example.com"
    ): ReferringOrganisation {
        return ReferringOrganisation().apply {
            this.id = id
            this.name = name
            this.website = website
            this.createdAt = LocalDateTime.now()
            this.updatedAt = LocalDateTime.now()
        }
    }
}
