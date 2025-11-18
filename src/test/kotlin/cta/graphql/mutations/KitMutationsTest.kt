package cta.graphql.mutations

import cta.app.*
import cta.app.graphql.mutations.KitMutations
import cta.app.services.FilterService
import cta.app.services.KitService
import cta.app.services.LocationService
import cta.app.services.MailService
import cta.auth.AppUser
import cta.fixtures.TestDataFactory
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.security.core.authority.SimpleGrantedAuthority
import java.util.Optional

/**
 * Unit tests for KitMutations resolver.
 * Tests mutation methods with mocked dependencies.
 */
@DisplayName("KitMutations Unit Tests")
class KitMutationsTest {

    private lateinit var kitRepository: KitRepository
    private lateinit var donorRepository: DonorRepository
    private lateinit var deviceRequestRepository: DeviceRequestRepository
    private lateinit var locationService: LocationService
    private lateinit var filterService: FilterService
    private lateinit var mailService: MailService
    private lateinit var kitService: KitService
    private lateinit var kitMutations: KitMutations

    @BeforeEach
    fun setup() {
        kitRepository = mockk()
        donorRepository = mockk()
        deviceRequestRepository = mockk()
        locationService = mockk()
        filterService = mockk()
        mailService = mockk()
        kitService = mockk()

        kitMutations = KitMutations(
            kitRepository,
            donorRepository,
            deviceRequestRepository,
            locationService,
            filterService,
            mailService,
            kitService
        )

        // Mock user details
        val mockUser = AppUser(
            id = "test-user",
            email = "test@example.com",
            authorities = listOf(SimpleGrantedAuthority("write:kits"))
        )
        every { filterService.userDetails() } returns mockUser
    }

    @Test
    @DisplayName("createKit should save kit with donor when donorId provided")
    fun `createKit saves kit with donor`() {
        // Given
        val donorId = 1L
        val donor = TestDataFactory.createDonor(id = donorId)
        val kit = TestDataFactory.createKit(id = null, donor = donor)

        val createInput = mockk<CreateKitInput>(relaxed = true)
        every { createInput.entity } returns kit
        every { createInput.donorId } returns donorId
        every { createInput.note } returns null

        every { donorRepository.findById(donorId) } returns Optional.of(donor)
        every { kitRepository.save(any()) } returns kit
        every { locationService.findCoordinates(any()) } returns null

        // When
        val result = kitMutations.createKit(createInput)

        // Then
        assertEquals(kit, result)
        verify { donorRepository.findById(donorId) }
        verify { kitRepository.save(any()) }
    }

    @Test
    @DisplayName("createKit should geocode location when provided")
    fun `createKit geocodes location`() {
        // Given
        val kit = TestDataFactory.createKit(id = null, location = "London")
        val coordinates = mockk<Coordinates>(relaxed = true)

        val createInput = mockk<CreateKitInput>(relaxed = true)
        every { createInput.entity } returns kit
        every { createInput.donorId } returns null
        every { createInput.note } returns null

        every { locationService.findCoordinates("London") } returns coordinates
        every { kitRepository.save(any()) } returns kit

        // When
        val result = kitMutations.createKit(createInput)

        // Then
        verify { locationService.findCoordinates("London") }
        verify { kitRepository.save(any()) }
    }

    @Test
    @DisplayName("quickCreateKit should create kit without location geocoding")
    fun `quickCreateKit creates kit without geocoding`() {
        // Given
        val donorId = 1L
        val donor = TestDataFactory.createDonor(id = donorId)
        val kit = TestDataFactory.createKit(id = null, donor = donor)

        val createInput = mockk<QuickCreateKitInput>(relaxed = true)
        every { createInput.entity } returns kit
        every { createInput.donorId } returns donorId

        every { donorRepository.findById(donorId) } returns Optional.of(donor)
        every { kitRepository.save(any()) } returns kit

        // When
        val result = kitMutations.quickCreateKit(createInput)

        // Then
        assertEquals(kit, result)
        verify { donorRepository.findById(donorId) }
        verify { kitRepository.save(any()) }
        verify(exactly = 0) { locationService.findCoordinates(any()) }
    }
}
