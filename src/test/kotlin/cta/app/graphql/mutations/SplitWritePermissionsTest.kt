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
 * #265 phase 1: write:requests guards the device-request mutations, write:referees guards the
 * referee-contact mutations, and write:organisations still passes everywhere. Denials come back
 * as HTTP 200 with a GraphQL errors array, so assertions are on the response body.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class SplitWritePermissionsTest {
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
        authority: String,
    ): String =
        mockMvc
            .perform(
                post("/graphql")
                    .with(jwt().authorities(SimpleGrantedAuthority(authority)))
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

    private fun seed(): Ids {
        val org = referringOrganisations.save(ReferringOrganisation(name = "Split Perm Org"))
        val contact =
            referringOrganisationContacts.save(
                ReferringOrganisationContact(
                    fullName = "Split Perm",
                    phoneNumber = "0700000000",
                    address = "1 Road",
                    referringOrganisation = org,
                ),
            )
        val request =
            deviceRequests.save(
                DeviceRequest(
                    deviceRequestItems = DeviceRequestItems(laptops = 1),
                    referringOrganisationContact = contact,
                    clientRef = "SPLIT-REF",
                    borough = "Lambeth",
                    details = "split perm probe",
                    deviceRequestNeeds = null,
                ),
            )
        return Ids(org.id!!, contact.id!!, request.id!!)
    }

    private fun updateRequest(
        ids: Ids,
        authority: String,
    ) = graphQl(
        "mutation { updateDeviceRequest(data: {id: ${ids.request}, deviceRequestItems: {laptops: 1}, isSales: false, clientRef: \\\"SPLIT-REF\\\", borough: \\\"Lambeth\\\", details: \\\"split perm probe\\\"}) { id } }",
        authority,
    )

    private fun updateContact(
        ids: Ids,
        authority: String,
    ) = graphQl(
        "mutation { updateReferringOrganisationContact(data: {id: ${ids.contact}, fullName: \\\"Split Perm\\\", email: \\\"split@example.com\\\", phoneNumber: \\\"0700000000\\\", referringOrganisationId: ${ids.org}}) { id } }",
        authority,
    )

    private fun updateOrg(
        ids: Ids,
        authority: String,
    ) = graphQl(
        "mutation { updateReferringOrganisation(data: {id: ${ids.org}, name: \\\"Split Perm Org\\\"}) { id } }",
        authority,
    )

    private fun assertAllowed(body: String) {
        assertFalse(body.contains("\"errors\""), body)
        assertTrue(body.contains("\"id\""), body)
    }

    private fun assertDenied(body: String) {
        assertTrue(body.contains("\"errors\""), body)
        assertFalse(body.contains("\"id\""), body)
    }

    @Test
    fun `write requests alone can edit requests but not referees or organisations`() {
        val ids = seed()
        assertAllowed(updateRequest(ids, "write:requests"))
        assertDenied(updateContact(ids, "write:requests"))
        assertDenied(updateOrg(ids, "write:requests"))
    }

    @Test
    fun `write referees alone can edit contacts but not requests or organisations`() {
        val ids = seed()
        assertAllowed(updateContact(ids, "write:referees"))
        assertDenied(updateRequest(ids, "write:referees"))
        assertDenied(updateOrg(ids, "write:referees"))
    }

    @Test
    fun `write organisations alone still passes everything`() {
        val ids = seed()
        assertAllowed(updateRequest(ids, "write:organisations"))
        assertAllowed(updateContact(ids, "write:organisations"))
        assertAllowed(updateOrg(ids, "write:organisations"))
    }
}
