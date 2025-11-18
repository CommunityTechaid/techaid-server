package cta.graphql.queries

import cta.app.Kit
import cta.app.KitRepository
import cta.app.KitStatus
import cta.app.KitType
import cta.app.graphql.queries.KitQueries
import cta.app.services.FilterService
import cta.fixtures.TestDataFactory
import com.querydsl.core.types.Predicate
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import java.util.Optional

/**
 * Unit tests for KitQueries resolver.
 * Tests individual query methods in isolation with mocked dependencies.
 */
@DisplayName("KitQueries Unit Tests")
class KitQueriesTest {

    private lateinit var kitRepository: KitRepository
    private lateinit var filterService: FilterService
    private lateinit var kitQueries: KitQueries

    @BeforeEach
    fun setup() {
        kitRepository = mockk()
        filterService = mockk()
        kitQueries = KitQueries(kitRepository, filterService)
    }

    @Test
    @DisplayName("statusCount should return kit counts grouped by status")
    fun `statusCount returns kit counts`() {
        // Given
        val expectedCounts = listOf(
            mockk(relaxed = true),
            mockk(relaxed = true)
        )
        every { kitRepository.statusCount() } returns expectedCounts

        // When
        val result = kitQueries.statusCount()

        // Then
        assertEquals(expectedCounts, result)
        verify(exactly = 1) { kitRepository.statusCount() }
    }

    @Test
    @DisplayName("typeCount should return kit counts grouped by type")
    fun `typeCount returns kit counts`() {
        // Given
        val expectedCounts = listOf(
            mockk(relaxed = true),
            mockk(relaxed = true)
        )
        every { kitRepository.typeCount() } returns expectedCounts

        // When
        val result = kitQueries.typeCount()

        // Then
        assertEquals(expectedCounts, result)
        verify(exactly = 1) { kitRepository.typeCount() }
    }

    @Test
    @DisplayName("kit query should find single kit by ID")
    fun `kit query finds kit by id`() {
        // Given
        val kitId = 1L
        val expectedKit = TestDataFactory.createKit(id = kitId)
        val mockPredicate = mockk<Predicate>()

        every { filterService.kitFilter() } returns mockPredicate
        every { mockPredicate.and(any<Predicate>()) } returns mockPredicate
        every { kitRepository.findOne(mockPredicate) } returns Optional.of(expectedKit)

        // When
        val result = kitQueries.kit(mockk(relaxed = true))

        // Then
        assertEquals(expectedKit, result.get())
        verify { kitRepository.findOne(any()) }
    }
}
