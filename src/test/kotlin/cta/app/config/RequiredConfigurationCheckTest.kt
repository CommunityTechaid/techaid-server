package cta.app.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatIllegalStateException
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

/**
 * The value of [RequiredConfigurationCheck] is entirely in its failure message, so that is what
 * these assert on: which configuration it names, and whether it tells you how to fix it.
 *
 * No Spring context on purpose — the check is a pure function of the environment, and a
 * `@SpringBootTest` would be slower while proving less, since the test profile supplies both
 * properties anyway.
 */
class RequiredConfigurationCheckTest {
    private fun check(
        vararg present: Pair<String, String>,
        profiles: List<String> = emptyList(),
    ) = RequiredConfigurationCheck(
        MockEnvironment().apply {
            present.forEach { (k, v) -> setProperty(k, v) }
            setActiveProfiles(*profiles.toTypedArray())
        },
    )

    private val complete =
        arrayOf(
            "auth0.token-attribute" to "https://communitytechaid.org.uk",
            "auth.admin-secret" to "a-secret",
            "auth0.allowed-clients" to "a-client",
        )

    private fun fullyConfigured() = check(*complete)

    private fun withWildcard(vararg profiles: String) = check(*complete, "auth.dev-tech-wildcard" to "true", profiles = profiles.toList())

    @Test
    fun `a missing azp allow-list is named at startup`() {
        val withoutAllowList = complete.filter { it.first != "auth0.allowed-clients" }.toTypedArray()
        assertThatIllegalStateException()
            .isThrownBy { check(*withoutAllowList).afterPropertiesSet() }
            .withMessageContaining("auth0.allowed-clients")
            .withMessageContaining("AUTH0_ALLOWED_CLIENTS")
    }

    @Test
    fun `the dev-tech wildcard is allowed in the local and testing profiles`() {
        assertThatCode { withWildcard("local").afterPropertiesSet() }.doesNotThrowAnyException()
        assertThatCode { withWildcard("testing").afterPropertiesSet() }.doesNotThrowAnyException()
    }

    @Test
    fun `the dev-tech wildcard refuses to start in production`() {
        assertThatIllegalStateException()
            .isThrownBy { withWildcard("production").afterPropertiesSet() }
            .withMessageContaining("AUTH_DEV_TECH_WILDCARD")
        assertThatIllegalStateException()
            .isThrownBy { withWildcard("testing", "production").afterPropertiesSet() }
    }

    @Test
    fun `the dev-tech wildcard refuses to start with no or an unknown profile`() {
        // Allow-list, not deny-list: a missing or misspelt profile must not leave it live.
        assertThatIllegalStateException().isThrownBy { withWildcard().afterPropertiesSet() }
        assertThatIllegalStateException().isThrownBy { withWildcard("prod").afterPropertiesSet() }
    }

    @Test
    fun `the wildcard off is fine in any profile`() {
        assertThatCode { check(*complete, profiles = listOf("production")).afterPropertiesSet() }
            .doesNotThrowAnyException()
    }

    @Test
    fun `a fully configured environment starts`() {
        assertThatCode { fullyConfigured().afterPropertiesSet() }.doesNotThrowAnyException()
    }

    @Test
    fun `a missing property is named, along with the variable that supplies it`() {
        assertThatIllegalStateException()
            .isThrownBy { check("auth0.token-attribute" to "https://communitytechaid.org.uk").afterPropertiesSet() }
            .withMessageContaining("auth.admin-secret")
            .withMessageContaining("AUTH_ADMIN_SECRET")
            .withMessageContaining("fail open")
    }

    @Test
    fun `a blank value counts as missing`() {
        // A secretRef that resolves to an empty string is the realistic deployed failure, not an
        // unset key: the platform supplies the variable, the secret behind it is what went wrong.
        assertThatIllegalStateException()
            .isThrownBy {
                check(
                    "auth0.token-attribute" to "https://communitytechaid.org.uk",
                    "auth.admin-secret" to "   ",
                    "auth0.allowed-clients" to "a-client",
                ).afterPropertiesSet()
            }.withMessageContaining("auth.admin-secret")
    }

    @Test
    fun `every missing property is listed at once, so fixing is not iterative`() {
        assertThatIllegalStateException()
            .isThrownBy { check().afterPropertiesSet() }
            .withMessageContaining("auth0.token-attribute")
            .withMessageContaining("auth.admin-secret")
    }

    @Test
    fun `it asserts on resolved properties, not on environment variable names`() {
        // This is the distinction that matters: application-test.yml sets these properties
        // directly, so the environment variables are absent while the app is correctly
        // configured. An earlier version checked variable names and broke all 48
        // context-loading tests.
        assertThatCode {
            check(
                "auth0.token-attribute" to "https://test.example.com",
                "auth.admin-secret" to "password",
                "auth0.allowed-clients" to "test-dashboard-client",
            ).afterPropertiesSet()
        }.doesNotThrowAnyException()

        assertThat(RequiredConfigurationCheck.REQUIRED.keys)
            .`as`("keys must be Spring property names")
            .allMatch { it.contains(".") && it == it.lowercase() }
    }

    @Test
    fun `HOSTNAME is deliberately not required`() {
        assertThat(RequiredConfigurationCheck.REQUIRED.values)
            .`as`("Docker and Azure Container Apps both supply HOSTNAME; requiring it would break bootRun")
            .doesNotContain("HOSTNAME")
    }
}
