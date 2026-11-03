package cta.app.graphql.queries

import cta.auth.Auth0Service
import cta.auth.FakeAuth0Server
import cta.auth.FakeAuth0Server.Companion.MAPPER
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import tools.jackson.databind.JsonNode

/*
 * The user-admin screens outside production. The server's Auth0 credentials are a key to the
 * tenant every environment shares, so developer machines get none and UAT gets a read-only
 * grant. Both must fail with a message the dashboard can show, not a stack trace or a bare
 * status code.
 */

private fun MockMvc.graphQl(query: String): JsonNode {
    val body = MAPPER.writeValueAsString(mapOf("query" to query))
    val response =
        perform(
            post("/graphql")
                .with(jwt().authorities(SimpleGrantedAuthority("read:users"), SimpleGrantedAuthority("write:users")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        ).andReturn()
            .response.contentAsString
    return MAPPER.readTree(response)
}

private fun JsonNode.firstErrorMessage(): String = path("errors").path(0).path("message").asString()

private const val USERS = "{ users(page: {page: 0, size: 5}, filter: \"\") { total items { userId } } }"
private const val ASSIGN = """mutation { assignRoles(roleId: "rol_admin", userIds: ["auth0|bob"]) { id } }"""

/** No credentials at all: a developer machine (application-test.yml configures none). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class UserManagementNotConfiguredTest {
    @MockitoBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `reading users says user management is not configured`() {
        assertThat(mockMvc.graphQl(USERS).firstErrorMessage()).isEqualTo(Auth0Service.NOT_CONFIGURED)
    }

    @Test
    fun `assigning a role says user management is not configured`() {
        assertThat(mockMvc.graphQl(ASSIGN).firstErrorMessage()).isEqualTo(Auth0Service.NOT_CONFIGURED)
    }
}

/** A read-only Management API grant: UAT's. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class UserManagementReadOnlyTest {
    @MockitoBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var mockMvc: MockMvc

    companion object {
        private val auth0 = FakeAuth0Server().apply { rejectWrites = true }

        @JvmStatic
        @DynamicPropertySource
        fun auth0Properties(registry: DynamicPropertyRegistry) {
            registry.add("auth0.domain") { auth0.url }
            registry.add("auth0.client-id") { "uat-client-id" }
            registry.add("auth0.client-secret") { "uat-client-secret" }
        }

        @JvmStatic
        @AfterAll
        fun stopAuth0() = auth0.stop()
    }

    @BeforeEach
    fun clearCalls() = auth0.reset()

    @Test
    fun `reads still work`() {
        val json = mockMvc.graphQl(USERS)
        assertThat(json.path("errors").isMissingNode).describedAs("errors: %s", json.path("errors")).isTrue
        assertThat(
            json
                .path("data")
                .path("users")
                .path("total")
                .asInt(),
        ).isEqualTo(3)
    }

    @Test
    fun `a write refused by Auth0 says user management is read-only`() {
        assertThat(mockMvc.graphQl(ASSIGN).firstErrorMessage()).isEqualTo(Auth0Service.READ_ONLY)
        assertThat(auth0.callsTo("POST", "/api/v2/roles/rol_admin/users")).hasSize(1)
    }
}
