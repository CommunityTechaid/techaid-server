package cta.app.config

import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

/**
 * Spring's session form login on `/login` is off by default. Nothing called it (one POST in 90
 * days of production telemetry, from our own security testing), the GET page it pointed at had
 * no template, and it was the only session-minting entry point left on an API whose callers all
 * send bearer tokens - which is what made CodeQL's disabled-CSRF finding a real question.
 *
 * `AUTH_FORM_LOGIN_ENABLED=true` puts back exactly what was there before, in case something we
 * did not see depended on it. Both states are pinned here so the switch keeps working.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class FormLoginDisabledByDefaultTest {
    @MockitoBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var securityFilterChain: SecurityFilterChain

    @Test
    fun `no form login filter is in the security chain`() {
        val filters = securityFilterChain.filters.map { it.javaClass.simpleName }

        assertFalse(
            filters.contains("UsernamePasswordAuthenticationFilter"),
            "form login is back in the chain without the toggle: $filters",
        )
    }

    @Test
    fun `login is not found`() {
        assertEquals(
            404,
            mockMvc
                .perform(get("/login"))
                .andReturn()
                .response.status,
            "GET /login",
        )
        assertEquals(
            404,
            mockMvc
                .perform(post("/login"))
                .andReturn()
                .response.status,
            "POST /login",
        )
    }
}

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = ["auth.form-login-enabled=true"],
)
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class FormLoginToggledOnTest {
    @MockitoBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var securityFilterChain: SecurityFilterChain

    @Test
    fun `the toggle restores the form login filter`() {
        val filters = securityFilterChain.filters.map { it.javaClass.simpleName }

        assertTrue(
            filters.contains("UsernamePasswordAuthenticationFilter"),
            "AUTH_FORM_LOGIN_ENABLED=true must put form login back: $filters",
        )
    }

    @Test
    fun `the toggle restores the login endpoint`() {
        // A failed form login redirects to the configured failure URL, as production did before.
        val response = mockMvc.perform(post("/login")).andReturn().response

        assertEquals(302, response.status, "POST /login")
        assertEquals("/login?error", response.redirectedUrl)
    }
}
