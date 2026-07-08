package cta.app.graphql.mutations

import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Authorization contract for operations reachable at /graphql. The security model is
 * anyRequest().permitAll() + per-method @PreAuthorize, so an operation without an
 * annotation is anonymously callable — these tests pin down which operations must
 * reject anonymous callers.
 *
 * "Rejected" surfaces as a GraphQL error with message "Access Denied" (Spring
 * Security's AccessDeniedException) and null data. The authorized variants assert the
 * gate lets the right scope through: any remaining error must NOT be an access
 * denial (the test DB has no seed data, so a not-found/SQL error after the gate
 * still proves authorization passed).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class PublicSurfaceAuthorizationTest {
    @MockBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var mockMvc: MockMvc

    private fun anonymousGraphQl(query: String): ResultActions =
        mockMvc.perform(
            post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query":"$query"}"""),
        )

    private fun authorizedGraphQl(
        query: String,
        authority: String,
    ): ResultActions =
        mockMvc.perform(
            post("/graphql")
                .with(jwt().authorities(SimpleGrantedAuthority(authority)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query":"$query"}"""),
        )

    private val syncMutation =
        """mutation { synchronizeCollectionDataForDeviceRequest(data: { id: 999999 }) { id } }"""

    private val createOrgMutation =
        """mutation { createReferringOrganisation(data: { name: \"Auth Probe Org\" }) { id name } }"""

    @Test
    fun `synchronizeCollectionDataForDeviceRequest rejects anonymous callers`() {
        anonymousGraphQl(syncMutation)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errors[0].message").value("Access Denied"))
            .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()))
    }

    @Test
    fun `synchronizeCollectionDataForDeviceRequest admits write-organisations scope`() {
        authorizedGraphQl(syncMutation, "write:organisations")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errors[0].message").value(notAccessDenied))
    }

    @Test
    fun `createReferringOrganisation rejects anonymous callers`() {
        anonymousGraphQl(createOrgMutation)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errors[0].message").value("Access Denied"))
            .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()))
    }

    @Test
    fun `createReferringOrganisation admits write-organisations scope`() {
        // Since the Flyway baseline migration the test schema is complete, so the
        // mutation succeeds outright — stronger than the old "any error but Access
        // Denied" assertion (the schema gap used to fail it post-gate on a missing
        // sequence).
        authorizedGraphQl(createOrgMutation, "write:organisations")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.createReferringOrganisation.name").value("Auth Probe Org"))
    }

    // The location query proxies the billed Google geocoding key and is unused by the
    // dashboard's public pages, so it must require authentication.
    private val locationQuery = """query { location(address: \"SW9 8RR\") { lat lng } }"""

    @Test
    fun `location query rejects anonymous callers`() {
        anonymousGraphQl(locationQuery)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errors[0].message").value("Access Denied"))
    }

    @Test
    fun `location query admits any authenticated user`() {
        // google.places.url points at a closed port in tests, so the lookup degrades to
        // null — the point is only that the gate lets an authenticated caller through.
        authorizedGraphQl(locationQuery, "read:organisations")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.location").value(org.hamcrest.Matchers.nullValue()))
    }

    // referringOrganisationsPublic stays anonymous (public referral form typeahead) but
    // must only accept the restricted filter surface the form actually uses.
    @Test
    fun `referringOrganisationsPublic accepts the public filter shape anonymously`() {
        val content =
            anonymousGraphQl(
                """query { referringOrganisationsPublic(where: { name: { _contains: \"tech\" }, archived: { _eq: false } }) { id name } }""",
            ).andExpect(status().isOk)
                .andReturn()
                .response
                .contentAsString

        assertFalse(content.contains("Access Denied"), content)
        assertFalse(content.contains("Validation error"), content)
    }

    @Test
    fun `referringOrganisationsPublic rejects admin-only filter fields`() {
        val content =
            anonymousGraphQl(
                """query { referringOrganisationsPublic(where: { website: { _contains: \"x\" } }) { id name } }""",
            ).andExpect(status().isOk)
                .andReturn()
                .response
                .contentAsString

        assertTrue(
            content.contains("Validation error"),
            "website must not be filterable on the public query: $content",
        )
    }

    // The public booking page reads availability anonymously — the Flyway seed gives it
    // upcoming Tue/Thu days, so an anonymous caller must get data and no access denial.
    @Test
    fun `deliveryAvailabilityPublic is callable anonymously and returns seeded days`() {
        anonymousGraphQl(
            """query { deliveryAvailabilityPublic { date dayOfWeek windows { spotsRemaining window { id name } } } }""",
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.deliveryAvailabilityPublic").isArray)
    }

    // The public booking mutation must be reachable without auth; a well-formed but
    // out-of-policy date is rejected by the booking rules (BAD_REQUEST), never by the auth gate.
    @Test
    fun `submitDeliveryBookingPublic is callable anonymously`() {
        val content =
            anonymousGraphQl(
                """mutation { submitDeliveryBookingPublic(input: { date: \"1970-01-01\", windowId: \"1\", firstName: \"A\", surname: \"B\", email: \"a@b.com\", phone: \"0700000000\", address: \"1 Road\", ctaReference: \"CTA-1\" }) { id } }""",
            ).andExpect(status().isOk)
                .andReturn()
                .response
                .contentAsString

        assertFalse(content.contains("Access Denied"), content)
    }

    companion object {
        private val notAccessDenied = org.hamcrest.Matchers.not("Access Denied")
    }
}
