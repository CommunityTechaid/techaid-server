package cta.app.config

import org.springframework.beans.factory.InitializingBean
import org.springframework.context.annotation.Lazy
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * Fails startup, loudly and by name, when required configuration is absent.
 *
 * `application.yml` has three placeholders with no default: `TOKEN_ATTRIBUTE`,
 * `AUTH_ADMIN_SECRET` and `HOSTNAME`. The platform always supplies `HOSTNAME`; the other two come
 * from the container app (a plain value and a secretRef respectively).
 *
 * The missing defaults are deliberate and must stay. `AUTH_ADMIN_SECRET` especially:
 * `X-Auth-Admin-Secret` grants full authorities, so a default — and an empty default above all —
 * would fail *open*. Refusing to serve is correct.
 *
 * What is not correct is *when* the absence is noticed. `spring.main.lazy-initialization: true`
 * defers `authService` until the first request that touches the security chain, so a missing value
 * looks like this:
 *
 *     Started ApplicationKt in 11.498 seconds      <- container reports healthy
 *     ...then every request 500s on PlaceholderResolutionException
 *
 * A container that starts and then fails every request is the worst shape a deploy can take: the
 * startup log says success, only the liveness probe disagrees, and the stack trace names
 * `WebSecurityConfiguration` rather than the variable that is actually absent.
 *
 * `@Lazy(false)` opts this bean out of the global lazy initialisation, so the check runs during
 * context refresh and the application refuses to start at all.
 *
 * NOTE it asserts on the resolved SPRING PROPERTY, not on the environment variable name. The two
 * are not interchangeable: `application-test.yml` sets `auth.admin-secret` and
 * `auth0.token-attribute` directly, so the variables are absent there while the application is
 * correctly configured. Checking variable names broke all 48 context-loading tests.
 */
@Component
@Lazy(false)
class RequiredConfigurationCheck(
    private val environment: Environment,
) : InitializingBean {
    override fun afterPropertiesSet() {
        val missing =
            REQUIRED.filter { (property, _) ->
                // An unresolvable ${VAR} throws rather than returning null, and that is exactly
                // the case being caught - so treat a throw as "absent" and report it properly.
                runCatching { environment.getProperty(property) }.getOrNull().isNullOrBlank()
            }

        check(missing.isEmpty()) {
            "Missing required configuration: " +
                missing.entries.joinToString(", ") { (property, variable) -> "$property (set $variable)" } +
                ". These have no default in application.yml, deliberately - AUTH_ADMIN_SECRET " +
                "grants full authorities via X-Auth-Admin-Secret, so defaulting it would fail open. " +
                "Set them on the container app rather than adding defaults here."
        }
    }

    companion object {
        /**
         * Spring property to the environment variable that supplies it in a deployed environment.
         *
         * `HOSTNAME` is deliberately absent: Docker and Azure Container Apps both supply it, and
         * requiring it would break `bootRun` on a bare shell for no benefit.
         */
        val REQUIRED =
            mapOf(
                "auth0.token-attribute" to "TOKEN_ATTRIBUTE",
                "auth.admin-secret" to "AUTH_ADMIN_SECRET",
            )
    }
}
