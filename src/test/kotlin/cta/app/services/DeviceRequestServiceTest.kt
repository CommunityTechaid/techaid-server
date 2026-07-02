package cta.app.services

import cta.app.DeviceRequestItems
import cta.app.DeviceRequestRepository
import cta.app.DeviceRequestStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyList
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.thymeleaf.TemplateEngine
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Optional

class DeviceRequestServiceTest {
    private lateinit var deviceRequests: DeviceRequestRepository
    private lateinit var mailService: MailService
    private lateinit var templateEngine: TemplateEngine
    private lateinit var service: DeviceRequestService

    @BeforeEach
    fun setUp() {
        deviceRequests = mock(DeviceRequestRepository::class.java)
        mailService = mock(MailService::class.java)
        templateEngine = mock(TemplateEngine::class.java)
        service = DeviceRequestService(deviceRequests, mailService, templateEngine)
    }

    // --- formatDeviceRequests ---

    @Test
    fun `formatDeviceRequests returns empty string when all items are zero or null`() {
        val items = DeviceRequestItems()
        assertEquals("", service.formatDeviceRequests(items))
    }

    @Test
    fun `formatDeviceRequests includes only non-zero items`() {
        val items = DeviceRequestItems(phones = 2, laptops = 1)
        val result = service.formatDeviceRequests(items)
        assertTrue(result.contains("Phones: 2"))
        assertTrue(result.contains("Laptops: 1"))
        assertFalse(result.contains("Tablets"))
        assertFalse(result.contains("Desktops"))
    }

    @Test
    fun `formatDeviceRequests includes all item types when non-zero`() {
        val items =
            DeviceRequestItems(
                phones = 1,
                tablets = 2,
                laptops = 3,
                allInOnes = 4,
                desktops = 5,
                other = 6,
                commsDevices = 7,
                broadbandHubs = 8,
            )
        val result = service.formatDeviceRequests(items)
        assertTrue(result.contains("Phones: 1"))
        assertTrue(result.contains("Tablets: 2"))
        assertTrue(result.contains("Laptops: 3"))
        assertTrue(result.contains("All-in-ones: 4"))
        assertTrue(result.contains("Desktops: 5"))
        assertTrue(result.contains("Other: 6"))
        assertTrue(result.contains("SIM card"))
        assertTrue(result.contains("Broadband Hubs: 8"))
    }

    @Test
    fun `formatDeviceRequests excludes items with zero count`() {
        val items = DeviceRequestItems(phones = 0, tablets = 0, laptops = 3)
        val result = service.formatDeviceRequests(items)
        assertFalse(result.contains("Phones"))
        assertFalse(result.contains("Tablets"))
        assertTrue(result.contains("Laptops: 3"))
    }

    // --- markRequestStepsCompleted ---

    @Test
    fun `markRequestStepsCompleted returns null when correlationId not found`() {
        `when`(deviceRequests.findByCorrelationId(999L)).thenReturn(Optional.empty())
        val result = service.markRequestStepsCompleted(999L)
        assertNull(result)
    }

    @Test
    fun `markRequestStepsCompleted updates status and clears correlationId`() {
        val mockRequest =
            cta.app.DeviceRequest(
                id = 1L,
                correlationId = 42L,
                deviceRequestItems = DeviceRequestItems(laptops = 1),
                referringOrganisationContact = mock(cta.app.ReferringOrganisationContact::class.java),
                clientRef = "REF001",
                borough = "Lambeth",
                details = "test",
                deviceRequestNeeds = null,
            )
        `when`(deviceRequests.findByCorrelationId(42L)).thenReturn(Optional.of(mockRequest))
        `when`(deviceRequests.save(mockRequest)).thenReturn(mockRequest)

        val result = service.markRequestStepsCompleted(42L)

        assertEquals(DeviceRequestStatus.PROCESSING_EQUALITIES_DATA_COMPLETE, result?.status)
        assertNull(result?.correlationId)
    }

    // --- declineIncompleteDeviceRequests ---

    private fun incompleteRequest(
        id: Long,
        createdAt: Instant,
    ): cta.app.DeviceRequest =
        cta.app.DeviceRequest(
            id = id,
            correlationId = id,
            deviceRequestItems = DeviceRequestItems(laptops = 1),
            referringOrganisationContact = mock(cta.app.ReferringOrganisationContact::class.java),
            clientRef = "REF$id",
            borough = "Lambeth",
            details = "test",
            deviceRequestNeeds = null,
            createdAt = createdAt,
        )

    @Test
    fun `declineIncompleteDeviceRequests declines only requests older than 20 minutes`() {
        val stale = incompleteRequest(1L, Instant.now().minus(30, ChronoUnit.MINUTES))
        val recent = incompleteRequest(2L, Instant.now().minus(5, ChronoUnit.MINUTES))
        `when`(deviceRequests.findAllByCorrelationIdIsNotNull()).thenReturn(listOf(stale, recent))
        var saved: List<cta.app.DeviceRequest>? = null
        `when`(deviceRequests.saveAll(anyList<cta.app.DeviceRequest>())).thenAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            (invocation.arguments[0] as List<cta.app.DeviceRequest>).also { saved = it }
        }

        val count = service.declineIncompleteDeviceRequests()

        assertEquals(1, count)
        assertEquals(DeviceRequestStatus.REQUEST_DECLINED, stale.status)
        assertNull(stale.correlationId)
        assertEquals(DeviceRequestStatus.NEW, recent.status)
        assertEquals(2L, recent.correlationId)
        assertEquals(listOf(stale), saved, "only the declined request should be written back")
    }

    @Test
    fun `declineIncompleteDeviceRequests writes nothing when no request crossed the threshold`() {
        val recent = incompleteRequest(3L, Instant.now().minus(5, ChronoUnit.MINUTES))
        `when`(deviceRequests.findAllByCorrelationIdIsNotNull()).thenReturn(listOf(recent))
        var saved: List<cta.app.DeviceRequest>? = null
        `when`(deviceRequests.saveAll(anyList<cta.app.DeviceRequest>())).thenAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            (invocation.arguments[0] as List<cta.app.DeviceRequest>).also { saved = it }
        }

        val count = service.declineIncompleteDeviceRequests()

        assertEquals(0, count)
        assertEquals(DeviceRequestStatus.NEW, recent.status)
        assertTrue(saved.isNullOrEmpty(), "no unchanged requests should be re-saved (audit churn)")
    }
}
