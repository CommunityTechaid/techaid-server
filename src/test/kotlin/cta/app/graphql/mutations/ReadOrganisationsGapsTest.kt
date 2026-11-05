package cta.app.graphql.mutations

import cta.app.ReferringOrganisation
import cta.app.ReferringOrganisationContact
import cta.app.ReferringOrganisationContactRepository
import cta.app.ReferringOrganisationRepository
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

/**
 * #345: views that still needed read:organisations after the #338 split. Each changed guard accepts
 * the new authority, keeps the old one and denies an unrelated one. Denials are HTTP 200 with a
 * GraphQL errors array, so assertions are on the body.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class ReadOrganisationsGapsTest {
    @MockitoBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var referringOrganisations: ReferringOrganisationRepository

    @Autowired
    lateinit var contacts: ReferringOrganisationContactRepository

    private fun graphQl(
        query: String,
        vararg authorities: String,
    ): String =
        mockMvc
            .perform(
                post("/graphql")
                    .with(jwt().authorities(authorities.map { SimpleGrantedAuthority(it) }))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"query":"$query"}"""),
            ).andReturn()
            .response
            .contentAsString

    private fun assertAllowed(
        body: String,
        marker: String,
    ) {
        assertFalse(body.contains("\"errors\""), body)
        assertTrue(body.contains(marker), body)
    }

    private fun assertDenied(
        body: String,
        marker: String,
    ) {
        assertTrue(body.contains("\"errors\""), body)
        assertFalse(body.contains(marker), body)
    }

    private val unrelated = "read:kits"

    @Test
    fun `deliveryBookingsAdmin accepts read requests or read organisations and denies unrelated`() {
        val query = "{ deliveryBookingsAdmin { ctaReference } }"
        assertAllowed(graphQl(query, "read:requests"), "\"deliveryBookingsAdmin\":[")
        assertAllowed(graphQl(query, "read:organisations"), "\"deliveryBookingsAdmin\":[")
        assertDenied(graphQl(query, unrelated), "\"deliveryBookingsAdmin\":[")
    }

    @Test
    fun `read requests does not unlock delivery configuration or booking deletion`() {
        assertDenied(graphQl("{ deliveryWindowsAdmin { id } }", "read:requests"), "\"deliveryWindowsAdmin\":[")
        assertDenied(graphQl("{ deliveryBlockedDates { id } }", "read:requests"), "\"deliveryBlockedDates\":[")
        assertDenied(
            graphQl("mutation { deleteDeliveryBooking(id: \\\"1\\\") }", "read:requests", "write:requests", "delete:requests"),
            "\"deleteDeliveryBooking\":",
        )
    }

    @Test
    fun `membership hint accepts write requests or read organisations and denies unrelated`() {
        val org = referringOrganisations.save(ReferringOrganisation(name = "Hint Org 345"))
        contacts.save(
            ReferringOrganisationContact(
                fullName = "Hint Contact",
                phoneNumber = "0700000000",
                address = "1 Road",
                referringOrganisation = org,
                isMembershipContact = true,
            ),
        )
        val query =
            "{ referringOrganisationMembershipHint(id: ${org.id}) " +
                "{ id name membership { tier used state } membershipContacts { id fullName } } }"
        val body = graphQl(query, "write:requests")
        assertAllowed(body, "\"name\":\"Hint Org 345\"")
        assertTrue(body.contains("\"fullName\":\"Hint Contact\""), body)
        assertTrue(body.contains("\"tier\":\"FREE\""), body)
        assertAllowed(graphQl(query, "read:organisations"), "\"name\":\"Hint Org 345\"")
        assertDenied(graphQl(query, unrelated), "Hint Org 345")
        assertDenied(graphQl(query, "read:requests"), "Hint Org 345")
    }

    @Test
    fun `write requests still cannot read the full organisation record`() {
        val org = referringOrganisations.save(ReferringOrganisation(name = "Hint Org 345b"))
        assertDenied(
            graphQl("{ referringOrganisation(where: {id: {_eq: ${org.id}}}) { id name } }", "write:requests"),
            "Hint Org 345b",
        )
    }
}
