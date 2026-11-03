package cta.app.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jwt.Jwt
import java.time.Instant

class Auth0TokenConverterTest {
    private fun token(vararg permissions: String): Jwt =
        Jwt
            .withTokenValue("t")
            .header("alg", "RS256")
            .subject("auth0|dev")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .claim("permissions", permissions.toList())
            .build()

    private fun authorities(
        wildcard: Boolean,
        vararg permissions: String,
    ): Set<String> =
        Auth0TokenConverter(wildcard)
            .convert(token(*permissions))
            .authorities
            .map { it.authority!! }
            .toSet()

    @Test
    fun `permissions in the token become authorities`() {
        assertThat(authorities(false, "read:kits", "write:kits")).contains("read:kits", "write:kits")
    }

    @Test
    fun `with the wildcard off, dev-tech grants nothing extra`() {
        // Production: the permission may sit in the token (the tenant is shared) but means nothing.
        assertThat(authorities(false, "dev:tech")).doesNotContain("app:admin", "write:kits")
    }

    @Test
    fun `with the wildcard on, dev-tech grants every application authority`() {
        assertThat(authorities(true, "dev:tech")).containsAll(DevTechWildcard.AUTHORITIES)
    }

    @Test
    fun `the wildcard never grants user management`() {
        // write:users in UAT can assign a role in the shared tenant, i.e. a production role.
        assertThat(authorities(true, "dev:tech")).doesNotContainAnyElementsOf(DevTechWildcard.EXCLUDED)
    }

    @Test
    fun `with the wildcard on, a token without dev-tech is unchanged`() {
        assertThat(authorities(true, "read:kits")).doesNotContain("app:admin")
    }
}
