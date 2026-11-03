package cta.app.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jwt.Jwt
import java.time.Instant

/**
 * Pins the claim checks every Auth0 access token must pass ([tokenValidator]), above all the
 * `azp` allow-list that keeps UAT and production tokens apart. UAT and production share one
 * tenant and one API, so issuer and audience are identical across them; without the azp check a
 * token minted for UAT is accepted by production (the 2026-10-30 finding).
 *
 * The client IDs are the real ones (they are public identifiers, not secrets) so the scenarios
 * read as what they are.
 */
class TokenValidatorTest {
    private val issuer = "https://techaid-auth.eu.auth0.com/"
    private val audience = "https://api.communitytechaid.org.uk"

    private val prodDashboard = "puJcT35DydtxJUsOfjNFVg7MBf19UDzX"
    private val devUatDashboard = "ANqygfpxC0j2pEmErFb5rWOIUnfnkk5O"
    private val calendarSync = "hT6KJjVGOC7k2LcTVzNLg2gTVLQ33MsW"

    private val production = tokenValidator(issuer, audience, setOf(prodDashboard, calendarSync))
    private val uat = tokenValidator(issuer, audience, setOf(devUatDashboard))

    private fun token(
        azp: String?,
        aud: List<String> = listOf(audience),
        iss: String = issuer,
    ): Jwt =
        Jwt
            .withTokenValue("t")
            .header("alg", "RS256")
            .issuer(iss)
            .audience(aud)
            .subject("auth0|someone")
            .issuedAt(Instant.now().minusSeconds(60))
            .expiresAt(Instant.now().plusSeconds(3600))
            .apply { if (azp != null) claim("azp", azp) }
            .build()

    @Test
    fun `production accepts its own dashboard's tokens`() {
        assertThat(production.validate(token(prodDashboard)).hasErrors()).isFalse
    }

    @Test
    fun `production rejects a token minted by the Dev and UAT dashboard`() {
        assertThat(production.validate(token(devUatDashboard)).hasErrors()).isTrue
    }

    @Test
    fun `UAT rejects a token minted by the production dashboard`() {
        assertThat(uat.validate(token(prodDashboard)).hasErrors()).isTrue
    }

    @Test
    fun `machine-to-machine tokens are matched on azp too`() {
        // Auth0 sets azp on client-credentials tokens as well (sub is "<client id>@clients").
        assertThat(production.validate(token(calendarSync)).hasErrors()).isFalse
        assertThat(uat.validate(token(calendarSync)).hasErrors()).isTrue
    }

    @Test
    fun `a token with no azp is rejected`() {
        assertThat(production.validate(token(azp = null)).hasErrors()).isTrue
    }

    @Test
    fun `an empty allow-list accepts nothing`() {
        val misconfigured = tokenValidator(issuer, audience, emptySet())
        assertThat(misconfigured.validate(token(prodDashboard)).hasErrors()).isTrue
    }

    @Test
    fun `issuer and audience are still checked`() {
        assertThat(production.validate(token(prodDashboard, iss = "https://evil.example.com/")).hasErrors()).isTrue
        assertThat(production.validate(token(prodDashboard, aud = listOf("https://other.example.com"))).hasErrors()).isTrue
    }

    @Test
    fun `the allow-list is parsed from a comma-separated variable, tolerating spaces and blanks`() {
        assertThat(AuthorisedPartyValidator.parse(" a , b,,c ")).containsExactlyInAnyOrder("a", "b", "c")
        assertThat(AuthorisedPartyValidator.parse("")).isEmpty()
    }
}
