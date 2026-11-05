package cta.app.graphql.queries

import com.jayway.jsonpath.JsonPath
import cta.app.ReferringOrganisation
import cta.app.ReferringOrganisationRepository
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.Assertions.assertEquals
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
 * Limits on the anonymous referringOrganisationsPublic lookup (#352).
 *
 * The lookup backs the public request form's organisation typeahead, so it stays anonymous, but
 * an anonymous caller is held to what the form does: a per-client-IP budget, a name search of at
 * least 3 characters, and at most [ReferringOrganisationQueries.PUBLIC_RESULT_LIMIT] results.
 * Staff (the referee-page picker) are exempt from the budget and the minimum term length.
 *
 * The budget is lowered to 3 here; the limiter is a singleton in this context, so each method
 * uses its own client IP.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
@TestPropertySource(properties = ["public-lookup.rate-limit.organisations.max-requests=3"])
class PublicOrganisationLookupLimitTest {
    @MockitoBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var referringOrganisations: ReferringOrganisationRepository

    private val rateLimitMessage = "Too many searches from your network. Please wait a few minutes and try again."

    private val staff: (MockHttpServletRequestBuilder) -> MockHttpServletRequestBuilder = {
        it.with(jwt().authorities(SimpleGrantedAuthority("write:referees")))
    }

    /** Sent via a Cloudflare edge peer so [clientIp] is honoured as the rate-limit key. */
    private fun lookup(
        nameFilter: String,
        clientIp: String,
        customise: (MockHttpServletRequestBuilder) -> MockHttpServletRequestBuilder = { it },
    ): ResultActions =
        mockMvc.perform(
            customise(
                post("/graphql")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"query":"query { referringOrganisationsPublic(where: { name: $nameFilter, archived: { _eq: false } }) { id name } }"}""",
                    ).header("CF-Connecting-IP", clientIp)
                    .with {
                        it.remoteAddr = "172.69.10.20"
                        it
                    },
            ),
        )

    private fun contains(term: String) = """{ _contains: \"$term\" }"""

    private fun names(result: ResultActions): List<String> =
        JsonPath.read(result.andReturn().response.contentAsString, "$.data.referringOrganisationsPublic[*].name")

    private fun organisations(
        stamp: String,
        count: Int,
    ) = (1..count).forEach { referringOrganisations.save(ReferringOrganisation(name = "Lookup $stamp ${"%03d".format(it)}")) }

    @Test
    fun `anonymous lookups are throttled per client ip`() {
        val ip = "203.0.113.131"
        repeat(3) {
            lookup(contains("tech"), ip).andExpect(status().isOk).andExpect(jsonPath("$.errors").doesNotExist())
        }
        lookup(contains("tech"), ip)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errors[0].message").value(rateLimitMessage))
            .andExpect(jsonPath("$.errors[0].extensions.classification").value("BAD_REQUEST"))

        // A different client has its own budget.
        lookup(contains("tech"), "203.0.113.132").andExpect(jsonPath("$.errors").doesNotExist())
    }

    @Test
    fun `staff lookups are not throttled`() {
        val ip = "203.0.113.141"
        repeat(6) { lookup(contains("tech"), ip, staff).andExpect(jsonPath("$.errors").doesNotExist()) }
    }

    @Test
    fun `an anonymous search shorter than 3 characters returns nothing`() {
        val stamp = System.nanoTime().toString()
        organisations(stamp, 2)

        assertEquals(emptyList<String>(), names(lookup(contains("L"), "203.0.113.151")))
        assertEquals(emptyList<String>(), names(lookup("{ _contains: \\\"\\\" }", "203.0.113.152")))
        assertEquals(2, names(lookup(contains(stamp), "203.0.113.153")).size)
    }

    @Test
    fun `an anonymous caller cannot page through names with range comparators`() {
        val stamp = System.nanoTime().toString()
        organisations(stamp, 2)

        // Only _contains is honoured anonymously: a bare range filter matches nothing.
        assertEquals(emptyList<String>(), names(lookup("{ _gt: \\\"Lookup $stamp\\\" }", "203.0.113.161")))
    }

    @Test
    fun `results are capped at 50 and ordered by name`() {
        val stamp = System.nanoTime().toString()
        organisations(stamp, 55)
        val expected = (1..50).map { "Lookup $stamp ${"%03d".format(it)}" }

        assertEquals(expected, names(lookup(contains(stamp), "203.0.113.171")))
        assertEquals(expected, names(lookup(contains(stamp), "203.0.113.172", staff)))
    }

    @Test
    fun `staff can search with a short term`() {
        val stamp = System.nanoTime().toString()
        organisations(stamp, 1)

        val found = names(lookup(contains("L"), "203.0.113.181", staff))
        assert(found.isNotEmpty()) { "staff short-term search returned nothing" }
    }
}
