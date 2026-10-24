package cta.app.graphql.mutations

import cta.app.ReferringOrganisation
import cta.app.ReferringOrganisationContact
import cta.app.ReferringOrganisationContactRepository
import cta.app.ReferringOrganisationRepository
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * The per-client-IP throttle on the public createDeviceRequest mutation the referral form uses.
 *
 * It was anonymous with no throttle, so a single caller could create rows as fast as it could send
 * requests. Staff create requests through the same mutation from the dashboard, so callers holding
 * app:admin or write:organisations are exempt.
 *
 * Budgets are lowered to 2 here so the N+1th call is cheap to reach. The limiters are real
 * singletons shared across methods in this context, so each method uses its own client IP.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
@TestPropertySource(
    properties = [
        "public-create.rate-limit.device-request.max-requests=2",
    ],
)
class PublicCreateRateLimitTest {
    @MockitoBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var referringOrganisations: ReferringOrganisationRepository

    @Autowired
    lateinit var referringOrganisationContacts: ReferringOrganisationContactRepository

    private val rateLimitMessage =
        "Too many submissions from your network. Please wait an hour and try again, " +
            "or contact distributions@communitytechaid.org.uk."

    /** Sent via a Cloudflare edge peer so [clientIp] is honoured as the rate-limit key. */
    private fun graphQl(
        query: String,
        clientIp: String,
        customise: (MockHttpServletRequestBuilder) -> MockHttpServletRequestBuilder = { it },
    ): ResultActions =
        mockMvc.perform(
            customise(
                post("/graphql")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"query":"$query"}""")
                    .header("CF-Connecting-IP", clientIp)
                    .with {
                        it.remoteAddr = "172.69.10.20"
                        it
                    },
            ),
        )

    private fun newContact(): ReferringOrganisationContact {
        val stamp = System.nanoTime()
        val organisation = referringOrganisations.save(ReferringOrganisation(name = "Org rl $stamp"))
        return referringOrganisationContacts.save(
            ReferringOrganisationContact(
                fullName = "Referee $stamp",
                email = "referee-$stamp@example.com",
                phoneNumber = "0$stamp",
                address = "1 Test Street",
                referringOrganisation = organisation,
            ),
        )
    }

    // A fresh contact per call so the per-referee open-request cap never interferes.
    private fun createDeviceRequest() =
        """mutation { createDeviceRequest(data: { referringOrganisationContact: \"${newContact().id}\", """ +
            """deviceRequestItems: { laptops: 1 }, clientRef: \"RL\", borough: \"Lambeth\", details: \"Needs a laptop\" }) { id } }"""

    @Test
    fun `anonymous createDeviceRequest is throttled per client ip`() {
        val ip = "203.0.113.31"
        repeat(2) {
            graphQl(createDeviceRequest(), ip)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.createDeviceRequest.id").isNotEmpty)
        }
        graphQl(createDeviceRequest(), ip)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errors[0].message").value(rateLimitMessage))
            .andExpect(jsonPath("$.errors[0].extensions.classification").value("BAD_REQUEST"))

        // A different client has its own budget.
        graphQl(createDeviceRequest(), "203.0.113.32")
            .andExpect(jsonPath("$.errors").doesNotExist())
    }

    @Test
    fun `staff with write-organisations are exempt`() {
        val ip = "203.0.113.61"
        val staff: (MockHttpServletRequestBuilder) -> MockHttpServletRequestBuilder = {
            it.with(jwt().authorities(SimpleGrantedAuthority("write:organisations")))
        }
        repeat(4) {
            graphQl(createDeviceRequest(), ip, staff).andExpect(jsonPath("$.errors").doesNotExist())
        }
    }

    @Test
    fun `staff with app-admin are exempt`() {
        val ip = "203.0.113.71"
        val admin: (MockHttpServletRequestBuilder) -> MockHttpServletRequestBuilder = {
            it.with(jwt().authorities(SimpleGrantedAuthority("app:admin")))
        }
        repeat(4) {
            graphQl(createDeviceRequest(), ip, admin).andExpect(jsonPath("$.errors").doesNotExist())
        }
    }

    @Test
    fun `an authenticated caller without a staff scope is still throttled`() {
        val ip = "203.0.113.81"
        val reader: (MockHttpServletRequestBuilder) -> MockHttpServletRequestBuilder = {
            it.with(jwt().authorities(SimpleGrantedAuthority("read:organisations")))
        }
        repeat(2) { graphQl(createDeviceRequest(), ip, reader).andExpect(jsonPath("$.errors").doesNotExist()) }
        graphQl(createDeviceRequest(), ip, reader).andExpect(jsonPath("$.errors[0].message").value(rateLimitMessage))
    }
}
