package cta.auth

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.test.util.ReflectionTestUtils

/**
 * Pins what Auth0Service sends to the Auth0 Authentication API — the client-credentials token
 * request behind every Management API call, password reset and sign-up — so the SDK 2.x -> 5.x
 * migration (issue #242) cannot change it unnoticed. 5.x, for instance, only accepts the
 * sign-up password as a char[].
 *
 * Talks to [FakeAuth0Server] over real HTTP; nothing reaches a real tenant.
 */
class Auth0ServiceAuthApiTest {
    private val auth0 = FakeAuth0Server()

    private val service =
        Auth0Service().also {
            ReflectionTestUtils.setField(it, "domain", auth0.url)
            ReflectionTestUtils.setField(it, "clientId", "test-client-id")
            ReflectionTestUtils.setField(it, "clientSecret", "test-client-secret")
        }

    @AfterEach
    fun stop() = auth0.stop()

    @Test
    fun `management calls use a client-credentials token, requested once and reused`() {
        service.findById("auth0|alice")
        service.findById("auth0|bob")

        val tokenRequest = auth0.callsTo("POST", "/oauth/token").single().json
        assertThat(tokenRequest.path("grant_type").asString()).isEqualTo("client_credentials")
        assertThat(tokenRequest.path("client_id").asString()).isEqualTo("test-client-id")
        assertThat(tokenRequest.path("client_secret").asString()).isEqualTo("test-client-secret")
        assertThat(tokenRequest.path("audience").asString()).isEqualTo("https://${auth0.url}/api/v2/")
        assertThat(auth0.calls.filter { it.path.startsWith("/api/v2/") }.map { it.authorization })
            .containsOnly("Bearer ${FakeAuth0Server.MGMT_TOKEN}")
    }

    @Test
    fun `resetPassword asks Auth0 to email a reset link for the database connection`() {
        service.resetPassword("alice@example.com")

        val body = auth0.callsTo("POST", "/dbconnections/change_password").single().json
        assertThat(body.path("email").asString()).isEqualTo("alice@example.com")
        assertThat(body.path("connection").asString()).isEqualTo("Username-Password-Authentication")
        assertThat(body.path("client_id").asString()).isEqualTo("test-client-id")
    }

    @Test
    fun `signUp registers the user on the database connection with custom fields`() {
        service.signUp("new@example.com", "newbie", "s3cret-Passw0rd", mapOf("org" to "CTA"))

        val body = auth0.callsTo("POST", "/dbconnections/signup").single().json
        assertThat(body.path("email").asString()).isEqualTo("new@example.com")
        assertThat(body.path("username").asString()).isEqualTo("newbie")
        assertThat(body.path("password").asString()).isEqualTo("s3cret-Passw0rd")
        assertThat(body.path("connection").asString()).isEqualTo("Username-Password-Authentication")
        assertThat(body.path("client_id").asString()).isEqualTo("test-client-id")
        assertThat(body.path("user_metadata").path("org").asString()).isEqualTo("CTA")
    }
}
