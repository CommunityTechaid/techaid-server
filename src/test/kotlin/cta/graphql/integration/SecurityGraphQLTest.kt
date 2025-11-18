package cta.graphql.integration

import cta.app.KitRepository
import cta.fixtures.TestDataFactory
import cta.graphql.GraphQLIntegrationTestBase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.test.context.support.WithMockUser

/**
 * Integration tests for GraphQL security and authorization.
 * Verifies that @PreAuthorize annotations work correctly.
 */
@DisplayName("GraphQL Security Tests")
class SecurityGraphQLTest : GraphQLIntegrationTestBase() {

    @Autowired
    private lateinit var kitRepository: KitRepository

    @AfterEach
    fun cleanup() {
        kitRepository.deleteAll()
    }

    @Test
    @DisplayName("Queries without authentication should be rejected")
    fun `unauthenticated query is rejected`() {
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
            .satisfy { errors ->
                assert(errors.isNotEmpty()) { "Expected errors for unauthenticated request" }
            }
    }

    @Test
    @WithMockUser(authorities = ["wrong:permission"])
    @DisplayName("Queries with wrong authority should be rejected")
    fun `query with wrong authority is rejected`() {
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
            .satisfy { errors ->
                assert(errors.isNotEmpty()) { "Expected errors for insufficient permissions" }
            }
    }

    @Test
    @WithMockUser(authorities = ["read:kits"])
    @DisplayName("Queries with correct read authority should succeed")
    fun `query with correct read authority succeeds`() {
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
            .verify()
            .path("statusCount")
            .entityList(Object::class.java)
            .hasSize(0) // Empty because no kits in database
    }

    @Test
    @WithMockUser(authorities = ["read:kits"])
    @DisplayName("Mutations with only read authority should be rejected")
    fun `mutation with only read authority is rejected`() {
        // When & Then
        graphQlTester
            .document(
                """
                mutation {
                    createKit(data: {
                        model: "Test Laptop"
                        type: LAPTOP
                        status: DONATION_NEW
                        location: "London"
                        age: 2
                    }) {
                        id
                        model
                    }
                }
                """.trimIndent()
            )
            .execute()
            .errors()
            .satisfy { errors ->
                assert(errors.isNotEmpty()) { "Expected errors for insufficient permissions" }
            }
    }

    @Test
    @WithMockUser(authorities = ["write:kits"])
    @DisplayName("Mutations with write authority should succeed")
    fun `mutation with write authority succeeds`() {
        // When & Then
        graphQlTester
            .document(
                """
                mutation {
                    createKit(data: {
                        model: "Test Laptop"
                        type: LAPTOP
                        status: DONATION_NEW
                        location: "London"
                        age: 2
                    }) {
                        id
                        model
                    }
                }
                """.trimIndent()
            )
            .execute()
            .path("createKit.model")
            .entity(String::class.java)
            .isEqualTo("Test Laptop")
    }

    @Test
    @WithMockUser(authorities = ["app:admin"])
    @DisplayName("Admin authority should have access to admin queries")
    fun `admin authority can access admin queries`() {
        // When & Then
        graphQlTester
            .document(
                """
                query {
                    adminConfig {
                        id
                    }
                }
                """.trimIndent()
            )
            .execute()
            .errors()
            .verify() // No errors expected
    }

    @Test
    @WithMockUser(authorities = ["read:kits"])
    @DisplayName("Non-admin authority should be rejected from admin queries")
    fun `non-admin authority cannot access admin queries`() {
        // When & Then
        graphQlTester
            .document(
                """
                query {
                    adminConfig {
                        id
                    }
                }
                """.trimIndent()
            )
            .execute()
            .errors()
            .satisfy { errors ->
                assert(errors.isNotEmpty()) { "Expected errors for non-admin access" }
            }
    }
}
