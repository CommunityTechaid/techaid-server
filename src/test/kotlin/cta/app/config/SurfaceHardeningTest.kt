package cta.app.config

import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

/**
 * Three unrelated bits of surface reduction that shipped together, each with its own reason to
 * stay shut.
 *
 * 1. The admin secret was accepted as an `x-admin-token` REQUEST PARAMETER on `POST /login`, in
 *    addition to the `X-Auth-Admin-Secret` header. `AccessLoggingFilter` logs the whole query
 *    string at DEBUG and production runs `cta: DEBUG`, so using that route wrote a secret
 *    granting full delete rights into container logs retained for 90 days.
 * 2. `/actuator/metrics` was exposed anonymously. Nothing consumed it; it let anyone enumerate
 *    JVM, Hikari and `http.server.requests` metrics. `health` and `info` stay - probes and the
 *    Apps Script warm-up poll health, and info is how a deploy is verified.
 * 3. There was no query depth bound at all, on an API with an anonymous surface.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class SurfaceHardeningTest {
    @MockitoBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var securityFilterChain: SecurityFilterChain

    private fun graphQl(
        query: String,
        session: MockHttpSession? = null,
    ): String {
        val request =
            post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content("""{"query":"$query"}""")
        if (session != null) {
            request.session(session)
        }
        return mockMvc
            .perform(request)
            .andReturn()
            .response.contentAsString
    }

    @Test
    fun `no filter authenticates the admin secret from a request parameter`() {
        val filters = securityFilterChain.filters.map { it.javaClass.simpleName }

        assertFalse(
            filters.any { it.contains("Secret", ignoreCase = true) },
            "a parameter-based admin auth filter is back in the chain: $filters",
        )
    }

    @Test
    fun `posting the admin secret as a query parameter grants nothing`() {
        // 'password' is the admin secret in application-test.yml. Before this change the same
        // request authenticated and the session below could read donors.
        val login =
            mockMvc
                .perform(post("/login").param("x-admin-token", "password"))
                .andReturn()
        val session = login.request.session as MockHttpSession

        val content = graphQl("{ donorsConnection(page: {size: 1}) { totalElements } }", session)

        assertTrue(
            content.contains("Access Denied"),
            "a session minted from ?x-admin-token must carry no authority: $content",
        )
        assertFalse(
            content.contains("\"totalElements\""),
            "donor data must not be readable from that session: $content",
        )
    }

    @Test
    fun `actuator exposes health and info but not metrics`() {
        assertEquals(
            404,
            mockMvc
                .perform(get("/actuator/metrics"))
                .andReturn()
                .response.status,
            "metrics must not be exposed",
        )
        assertEquals(
            200,
            mockMvc
                .perform(get("/actuator/health"))
                .andReturn()
                .response.status,
            "container probes and the Apps Script warm-up need health",
        )
        assertEquals(
            200,
            mockMvc
                .perform(get("/actuator/info"))
                .andReturn()
                .response.status,
            "info is how a deploy is verified - it must stay reachable",
        )
    }

    @Test
    fun `a document nested past the depth limit is refused`() {
        val content = graphQl(nested(10))

        assertTrue(
            content.contains("depth", ignoreCase = true),
            "a deeply nested document must be refused on depth: $content",
        )
    }

    @Test
    fun `a realistically shallow document is not refused on depth`() {
        // One level of nesting, well inside the limit. This is refused for AUTHORISATION, which
        // is the point: the depth bound must not be what stops ordinary queries.
        val content = graphQl(nested(1))

        assertFalse(
            content.contains("depth", ignoreCase = true),
            "a shallow document must not trip the depth limit: $content",
        )
    }

    /**
     * Builds a document that walks the organisation <-> contact cycle [levels] times. Each level
     * adds two field selections, so levels=10 is around depth 22 and levels=1 is depth 4.
     */
    private fun nested(levels: Int): String {
        val open = StringBuilder()
        val close = StringBuilder()
        repeat(levels) {
            open.append("referringOrganisationContacts { referringOrganisation { ")
            close.append("} } ")
        }
        return "{ referringOrganisations(where: {}) { name $open name $close } }"
    }
}
