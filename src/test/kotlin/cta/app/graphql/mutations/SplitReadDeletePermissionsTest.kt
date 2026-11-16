package cta.app.graphql.mutations

import cta.app.DeviceRequest
import cta.app.DeviceRequestItems
import cta.app.DeviceRequestRepository
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
 * #338 / #348: read:requests / delete:requests and read:referees / delete:referees guard request and
 * referee entry points. Since #348 the legacy *:organisations scopes no longer stand in for them: for
 * each entry point a new-scope token is allowed and an old-only or unrelated token is denied. Denials
 * come back as HTTP 200 with a GraphQL errors array, so assertions are on the response body.
 *
 * #341: nested notes / membershipContacts need read:referees; read:requests alone (or with
 * read:organisations) is denied them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class SplitReadDeletePermissionsTest {
    @MockitoBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var deviceRequests: DeviceRequestRepository

    @Autowired
    lateinit var referringOrganisations: ReferringOrganisationRepository

    @Autowired
    lateinit var referringOrganisationContacts: ReferringOrganisationContactRepository

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

    private class Ids(
        val org: Long,
        val contact: Long,
        val request: Long,
    )

    private fun seedContact(org: ReferringOrganisation): ReferringOrganisationContact =
        referringOrganisationContacts.save(
            ReferringOrganisationContact(
                fullName = "Split RD",
                phoneNumber = "0700000000",
                address = "1 Road",
                referringOrganisation = org,
            ),
        )

    private fun seed(): Ids {
        val org = referringOrganisations.save(ReferringOrganisation(name = "Split RD Org"))
        val contact = seedContact(org)
        val request =
            deviceRequests.save(
                DeviceRequest(
                    deviceRequestItems = DeviceRequestItems(laptops = 1),
                    referringOrganisationContact = contact,
                    clientRef = "SPLIT-RD-REF",
                    borough = "Lambeth",
                    details = "split read/delete probe",
                    deviceRequestNeeds = null,
                ),
            )
        return Ids(org.id!!, contact.id!!, request.id!!)
    }

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

    private val oldRead = "read:organisations"
    private val unrelated = "read:kits"

    @Test
    fun `request reads accept read requests and deny read organisations and unrelated`() {
        val ids = seed()
        val queries =
            listOf(
                "{ deviceRequests(where: {id: {_eq: ${ids.request}}}) { id } }" to "\"id\"",
                "{ deviceRequestConnection(where: {id: {_eq: ${ids.request}}}) { content { id } } }" to "\"id\"",
                "{ deviceRequest(where: {id: {_eq: ${ids.request}}}) { id } }" to "\"id\"",
                "{ deviceRequestAudits(where: ${ids.request}) { type } }" to "\"type\"",
            )
        for ((query, marker) in queries) {
            assertAllowed(graphQl(query, "read:requests"), marker)
            assertDenied(graphQl(query, oldRead), marker)
            assertDenied(graphQl(query, unrelated), marker)
        }
    }

    @Test
    fun `referee reads accept read referees and deny read organisations and unrelated`() {
        val ids = seed()
        val where = "where: {id: {_eq: ${ids.contact}}}"
        val queries =
            listOf(
                "{ referringOrganisationContacts($where) { id } }",
                "{ referringOrganisationContactsConnection($where) { content { id } } }",
                "{ referringOrganisationContact($where) { id } }",
                "{ referringOrganisationContact($where) { id notes { id } } }",
            )
        for (query in queries) {
            assertAllowed(graphQl(query, "read:referees"), "\"id\"")
            assertDenied(graphQl(query, oldRead), "\"id\"")
            assertDenied(graphQl(query, unrelated), "\"id\"")
        }
    }

    @Test
    fun `deleteDeviceRequest accepts delete requests and denies delete organisations and unrelated`() {
        val marker = "\"deleteDeviceRequest\":true"
        assertAllowed(graphQl("mutation { deleteDeviceRequest(id: ${seed().request}) }", "delete:requests"), marker)
        assertDenied(graphQl("mutation { deleteDeviceRequest(id: ${seed().request}) }", "delete:organisations"), marker)
        assertDenied(graphQl("mutation { deleteDeviceRequest(id: ${seed().request}) }", unrelated), marker)
    }

    @Test
    fun `deleteReferringOrganisationContact accepts delete referees and denies delete organisations and unrelated`() {
        val org = referringOrganisations.save(ReferringOrganisation(name = "Split RD Delete Org"))
        val marker = "\"deleteReferringOrganisationContact\":true"
        val delete = { authority: String ->
            graphQl("mutation { deleteReferringOrganisationContact(id: ${seedContact(org).id}) }", authority)
        }
        assertAllowed(delete("delete:referees"), marker)
        assertDenied(delete("delete:organisations"), marker)
        assertDenied(delete(unrelated), marker)
    }

    @Test
    fun `delete requests and delete referees do not cross over`() {
        val ids = seed()
        assertDenied(
            graphQl("mutation { deleteDeviceRequest(id: ${ids.request}) }", "delete:referees"),
            "\"deleteDeviceRequest\":true",
        )
        assertDenied(
            graphQl("mutation { deleteReferringOrganisationContact(id: ${ids.contact}) }", "delete:requests"),
            "\"deleteReferringOrganisationContact\":true",
        )
    }

    // Nested-field behaviour by token.

    @Test
    fun `read requests alone returns nested referee and organisation scalars`() {
        val ids = seed()
        val body =
            graphQl(
                "{ deviceRequests(where: {id: {_eq: ${ids.request}}}) { id referringOrganisationContact { id fullName referringOrganisation { id name } } } }",
                "read:requests",
            )
        assertFalse(body.contains("\"errors\""), body)
        assertTrue(body.contains("\"fullName\":\"Split RD\""), body)
        assertTrue(body.contains("\"name\":\"Split RD Org\""), body)
    }

    @Test
    fun `read requests alone is denied referee notes but a read referees token is not`() {
        val ids = seed()
        val query = "{ deviceRequests(where: {id: {_eq: ${ids.request}}}) { id referringOrganisationContact { id notes { id } } } }"
        // Decision: referee notes are referee data, so read:requests alone must NOT see them.
        assertTrue(graphQl(query, "read:requests").contains("\"errors\""))
        assertAllowed(graphQl(query, "read:requests", "read:referees"), "\"id\"")
        assertTrue(graphQl(query, "read:requests", oldRead).contains("\"errors\""))
        assertDenied(graphQl(query, unrelated), "\"id\"")
    }

    @Test
    fun `membershipContacts accepts read referees and denies read organisations or read requests alone`() {
        val ids = seed()
        val query =
            "{ deviceRequests(where: {id: {_eq: ${ids.request}}}) { id referringOrganisationContact { " +
                "referringOrganisation { id membershipContacts { id } } } } }"
        assertTrue(graphQl(query, "read:requests").contains("\"errors\""))
        assertAllowed(graphQl(query, "read:requests", "read:referees"), "\"membershipContacts\"")
        assertTrue(graphQl(query, "read:requests", oldRead).contains("\"errors\""))
        assertDenied(graphQl(query, unrelated), "\"membershipContacts\"")
    }

    @Test
    fun `read requests plus read referees returns both nested fields with no errors`() {
        val ids = seed()
        val body =
            graphQl(
                "{ deviceRequests(where: {id: {_eq: ${ids.request}}}) { id referringOrganisationContact { id notes { id } " +
                    "referringOrganisation { id membershipContacts { id } } } } }",
                "read:requests",
                "read:referees",
            )
        assertFalse(body.contains("\"errors\""), body)
        assertTrue(body.contains("\"notes\"") && body.contains("\"membershipContacts\""), body)
    }
}
