package cta.graphql.integration

import cta.app.DonorRepository
import cta.app.KitRepository
import cta.app.KitStatus
import cta.app.KitType
import cta.fixtures.TestDataFactory
import cta.graphql.GraphQLIntegrationTestBase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.test.context.support.WithMockUser

/**
 * Integration tests for Kit GraphQL API.
 * Tests the full GraphQL request/response cycle with real database.
 */
@DisplayName("Kit GraphQL Integration Tests")
class KitGraphQLIntegrationTest : GraphQLIntegrationTestBase() {

    @Autowired
    private lateinit var kitRepository: KitRepository

    @Autowired
    private lateinit var donorRepository: DonorRepository

    @BeforeEach
    fun setup() {
        // Clean database before each test
        kitRepository.deleteAll()
        donorRepository.deleteAll()
    }

    @AfterEach
    fun cleanup() {
        kitRepository.deleteAll()
        donorRepository.deleteAll()
    }

    @Test
    @WithMockUser(authorities = ["read:kits"])
    @DisplayName("Query kitsConnection should return paginated kits")
    fun `kitsConnection query returns paginated results`() {
        // Given
        val donor = donorRepository.save(TestDataFactory.createDonor())
        kitRepository.save(TestDataFactory.createKit(id = null, donor = donor))
        kitRepository.save(TestDataFactory.createKit(id = null, model = "HP ProBook", donor = donor))

        // When & Then
        graphQlTester
            .documentName("kitsConnection") // References src/test/resources/graphql-test/kitsConnection.graphql
            .variable("page", mapOf("page" to 0, "size" to 10))
            .execute()
            .path("kitsConnection.content")
            .entityList(Object::class.java)
            .hasSize(2)
            .path("kitsConnection.totalElements")
            .entity(Int::class.java)
            .isEqualTo(2)
    }

    @Test
    @WithMockUser(authorities = ["read:kits"])
    @DisplayName("Query statusCount should return kit counts by status")
    fun `statusCount query returns correct counts`() {
        // Given
        val donor = donorRepository.save(TestDataFactory.createDonor())
        kitRepository.save(TestDataFactory.createKit(id = null, status = KitStatus.DONATION_NEW, donor = donor))
        kitRepository.save(TestDataFactory.createKit(id = null, status = KitStatus.DONATION_NEW, donor = donor))
        kitRepository.save(TestDataFactory.createKit(id = null, status = KitStatus.PROCESSING_WIPING, donor = donor))

        // When & Then
        graphQlTester
            .document(
                """
                query {
                    statusCount {
                        status
                        count
                    }
                }
                """.trimIndent()
            )
            .execute()
            .path("statusCount")
            .entityList(Object::class.java)
            .hasSizeGreaterThan(0)
    }

    @Test
    @WithMockUser(authorities = ["read:kits"])
    @DisplayName("Query typeCount should return kit counts by type")
    fun `typeCount query returns correct counts`() {
        // Given
        val donor = donorRepository.save(TestDataFactory.createDonor())
        kitRepository.save(TestDataFactory.createKit(id = null, type = KitType.LAPTOP, donor = donor))
        kitRepository.save(TestDataFactory.createKit(id = null, type = KitType.LAPTOP, donor = donor))
        kitRepository.save(TestDataFactory.createKit(id = null, type = KitType.DESKTOP, donor = donor))

        // When & Then
        graphQlTester
            .document(
                """
                query {
                    typeCount {
                        type
                        count
                    }
                }
                """.trimIndent()
            )
            .execute()
            .path("typeCount")
            .entityList(Object::class.java)
            .hasSizeGreaterThan(0)
    }

    @Test
    @DisplayName("Query without authentication should fail")
    fun `unauthenticated query should be rejected`() {
        // When & Then
        graphQlTester
            .document(
                """
                query {
                    statusCount {
                        status
                        count
                    }
                }
                """.trimIndent()
            )
            .execute()
            .errors()
            .expect { it.errorType.toString() == "UNAUTHORIZED" }
    }
}
