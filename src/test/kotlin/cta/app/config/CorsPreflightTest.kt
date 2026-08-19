package cta.app.config

import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * The CORS preflight contract for /graphql.
 *
 * `Access-Control-Max-Age` is the load-bearing assertion. The API scales to zero, so an expired
 * preflight wakes the container — measured to 2026-08-17, `OPTIONS /graphql` caused 36 of the 89
 * attributable production cold starts over 14 days, more than any scanner. Spring's default of
 * 1800s is what produced that, and a future edit to [CorsConfig] that drops `.maxAge(...)` would
 * silently reintroduce it with nothing else failing.
 *
 * The allowed-origin assertions are here because a preflight that stops answering correctly takes
 * the whole dashboard and the public request form down at once, and both origins have to keep
 * working — app-testing against UAT, app against production.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class CorsPreflightTest {
    @MockBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `a preflight is cacheable for far longer than Spring's default`() {
        preflight("https://app.communitytechaid.org.uk")
            .andExpect(status().isOk)
            .andExpect(header().string("Access-Control-Max-Age", "86400"))
    }

    @Test
    fun `both dashboard origins are still allowed`() {
        for (origin in listOf(
            "https://app.communitytechaid.org.uk",
            "https://app-testing.communitytechaid.org.uk",
        )) {
            preflight(origin)
                .andExpect(status().isOk)
                .andExpect(header().string("Access-Control-Allow-Origin", origin))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
        }
    }

    @Test
    fun `an unknown origin is still refused`() {
        preflight("https://not-ours.example.com").andExpect(status().isForbidden)
    }

    private fun preflight(origin: String) =
        mockMvc.perform(
            options("/graphql")
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type,authorization"),
        )
}
