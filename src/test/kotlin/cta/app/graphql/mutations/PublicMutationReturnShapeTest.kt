package cta.app.graphql.mutations

import cta.app.ReferringOrganisation
import cta.app.ReferringOrganisationContact
import cta.app.ReferringOrganisationContactRepository
import cta.app.ReferringOrganisationRepository
import cta.app.graphql.queries.ReferringOrganisationContactQueries
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.context.support.WithAnonymousUser
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * What an ANONYMOUS caller can read back through the return value of the two public mutations
 * the referral form uses.
 *
 * PublicSurfaceAuthorizationTest pins which top-level operations reject anonymous callers; it
 * cannot see this. Both mutations are deliberately public, but they used to return the full
 * entity, and the full entity links to its organisation, whose `referringOrganisationContacts`
 * lists every referee in it — name, email, phone, address, notes. So an unauthenticated caller
 * could create (or, via the name+email dedupe, re-fetch) a contact against any organisation id and
 * walk back down to everybody else's PII. That is a schema-shape defect, so it is tested at the
 * GraphQL layer with a real document over HTTP, not by calling the controller method.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class PublicMutationReturnShapeTest {
    @MockitoBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var referringOrganisations: ReferringOrganisationRepository

    @Autowired
    lateinit var referringOrganisationContacts: ReferringOrganisationContactRepository

    @Autowired
    lateinit var contactQueries: ReferringOrganisationContactQueries

    private fun anonymousGraphQl(
        document: String,
        variables: String,
    ): String =
        mockMvc
            .perform(
                post("/graphql")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"query":${quote(document)},"variables":$variables}"""),
            ).andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    @Test
    fun `createReferringOrganisationContact cannot reach other referees in the organisation`() {
        val victim = victimInNewOrganisation()

        val content =
            anonymousGraphQl(
                """
                mutation (${'$'}org: ID!) {
                  createReferringOrganisationContact(data: { fullName: "Probe", email: "probe@example.com", phoneNumber: "07000000000", referringOrganisation: ${'$'}org }) {
                    referringOrganisation { phoneNumber referringOrganisationContacts { fullName email phoneNumber address } }
                  }
                }
                """.trimIndent(),
                """{"org":"${victim.referringOrganisation.id}"}""",
            )

        assertFalse(content.contains(victim.email), "another referee's email leaked to an anonymous caller: $content")
        assertFalse(content.contains(victim.phoneNumber), "another referee's phone leaked to an anonymous caller: $content")
        assertTrue(content.contains("Validation error"), "the nested field must not exist on the public return type: $content")
    }

    @Test
    fun `createDeviceRequest cannot reach other referees in the organisation`() {
        val victim = victimInNewOrganisation()
        val requester = contact(victim.referringOrganisation, "Requester")

        val content =
            anonymousGraphQl(
                """
                mutation (${'$'}contactId: ID!) {
                  createDeviceRequest(data: {
                    referringOrganisationContact: ${'$'}contactId
                    deviceRequestItems: { laptops: 1 }
                    clientRef: "SHAPE-PROBE"
                    borough: "Lambeth"
                    details: "Household needs a laptop"
                  }) {
                    referringOrganisationContact { referringOrganisation { referringOrganisationContacts { fullName email phoneNumber address } } }
                  }
                }
                """.trimIndent(),
                """{"contactId":"${requester.id}"}""",
            )

        assertFalse(content.contains(victim.email), "another referee's email leaked to an anonymous caller: $content")
        assertFalse(content.contains(victim.phoneNumber), "another referee's phone leaked to an anonymous caller: $content")
        assertTrue(content.contains("Validation error"), "the nested field must not exist on the public return type: $content")
    }

    // The exact selection the referral form (org-request.ts) and the admin referee components send.
    @Test
    fun `createReferringOrganisationContact still returns the id the dashboard selects`() {
        val organisation = referringOrganisations.save(ReferringOrganisation(name = "Org happy ${System.nanoTime()}"))

        mockMvc
            .perform(
                post("/graphql")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"query":${quote(
                            "mutation (\$data: CreateReferringOrganisationContactInput!) { createReferringOrganisationContact(data: \$data) { id } }",
                        )},"variables":{"data":{"fullName":"New Referee","email":"new-${System.nanoTime()}@example.com","phoneNumber":"07000000000","referringOrganisation":"${organisation.id}"}}}""",
                    ),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.createReferringOrganisationContact.id").isNotEmpty)
    }

    // org-request.ts selects { id correlationId }; the dashboard E2E lifecycle fixture selects { id status }.
    @Test
    fun `createDeviceRequest still returns the fields the dashboard selects`() {
        val requester = contact(referringOrganisations.save(ReferringOrganisation(name = "Org dr ${System.nanoTime()}")), "Requester")

        mockMvc
            .perform(
                post("/graphql")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"query":${quote(
                            """
                            mutation (${'$'}contactId: ID!) {
                              createDeviceRequest(data: {
                                referringOrganisationContact: ${'$'}contactId
                                deviceRequestItems: { laptops: 1 }
                                clientRef: "HAPPY"
                                borough: "Lambeth"
                                details: "Household needs a laptop"
                              }) { id correlationId status }
                            }
                            """.trimIndent(),
                        )},"variables":{"contactId":"${requester.id}"}}""",
                    ),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.createDeviceRequest.id").isNotEmpty)
            .andExpect(jsonPath("$.data.createDeviceRequest.correlationId").isNotEmpty)
            .andExpect(jsonPath("$.data.createDeviceRequest.status").value("NEW"))
    }

    // Defence in depth: contact notes are internal volunteer comments, so the field resolver is
    // gated on its own rather than relying on every path to a contact being gated.
    @Test
    @WithAnonymousUser
    fun `contact notes resolver rejects anonymous callers`() {
        val victim = victimInNewOrganisation()
        assertThrows<AccessDeniedException> { contactQueries.notes(victim) }
    }

    @Test
    @WithMockUser(authorities = ["read:organisations"])
    fun `contact notes resolver admits read-organisations scope`() {
        val victim = victimInNewOrganisation()
        contactQueries.notes(victim)
    }

    private fun victimInNewOrganisation(): ReferringOrganisationContact {
        val organisation = referringOrganisations.save(ReferringOrganisation(name = "Org victim ${System.nanoTime()}"))
        return contact(organisation, "Victim")
    }

    private fun contact(
        organisation: ReferringOrganisation,
        name: String,
    ): ReferringOrganisationContact {
        val stamp = System.nanoTime()
        return referringOrganisationContacts.save(
            ReferringOrganisationContact(
                fullName = "$name $stamp",
                email = "${name.lowercase()}-$stamp@example.com",
                phoneNumber = "0$stamp",
                address = "1 Test Street",
                referringOrganisation = organisation,
            ),
        )
    }
}
