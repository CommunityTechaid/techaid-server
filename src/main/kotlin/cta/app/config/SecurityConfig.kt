package cta.app.config

import cta.auth.AuthService
import cta.auth.TokenAuthenticationFilter
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
import org.springframework.core.convert.converter.Converter
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtDecoders
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter
import tools.jackson.databind.ObjectMapper

private val logger = KotlinLogging.logger {}

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(securedEnabled = true)
class SecurityConfig(
    private val authService: AuthService,
    private val objectMapper: ObjectMapper,
) {
    @Value("\${auth0.audience}")
    private var audience: String = ""

    @Value("\${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    var issuer: String = ""

    @Value("\${auth0.allowed-clients:}")
    private var allowedClients: String = ""

    @Value("\${auth.dev-tech-wildcard:false}")
    private var devTechWildcard: Boolean = false

    @Bean
    @Lazy
    fun jwtDecoder(): JwtDecoder {
        val jwtDecoder = JwtDecoders.fromOidcIssuerLocation(issuer) as NimbusJwtDecoder
        jwtDecoder.setJwtValidator(tokenValidator(issuer, audience, AuthorisedPartyValidator.parse(allowedClients)))
        return jwtDecoder
    }

    /**
     * The admin secret is accepted in the `X-Auth-Admin-Secret` header only.
     *
     * There used to be a second route: `SecretAuthenticationFilter` read the same secret from an
     * `x-admin-token` REQUEST PARAMETER on `POST /login` and minted a session carrying the full
     * set of admin authorities. It is gone, because `AccessLoggingFilter` logs the whole query
     * string at DEBUG and production runs `cta: DEBUG` - so authenticating that way wrote the
     * admin secret in clear into container logs that are retained for 90 days. Two entry points
     * to the same static secret, one of which self-leaks, is not a trade worth keeping.
     *
     * Its `AuthenticationProvider` went with it, and so did the `AuthenticationManager` bean that
     * existed only to feed that filter. Leaving the bean behind is a trap: with no provider left
     * to terminate it, `AuthenticationConfiguration.getAuthenticationManager()` resolves back to
     * the bean it is defining, and any `POST /login` dies with a StackOverflowError. Nothing else
     * in the app injects an `AuthenticationManager`; `formLogin` is left with Spring's default.
     */
    @Bean
    public fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http.csrf { it.disable() }
        http.addFilterBefore(TokenAuthenticationFilter(authService, objectMapper), BasicAuthenticationFilter::class.java)
        http.oauth2ResourceServer { it.jwt { jwt -> jwt.jwtAuthenticationConverter(Auth0TokenConverter(devTechWildcard)) } }
        http.authorizeHttpRequests { it.anyRequest().permitAll() }
        http.formLogin { form ->
            form
                .loginPage("/login")
                .defaultSuccessUrl("/login", true)
                .failureUrl("/login?error")
        }
        return http.build()
    }
}

/**
 * Every check an Auth0 access token must pass, in one place so tests can exercise exactly what
 * production runs: signature-independent claims only (the decoder verifies the signature).
 *
 * UAT and production share one Auth0 tenant (same issuer) and one API (same audience), so issuer
 * and audience alone cannot tell a UAT token from a production one. [AuthorisedPartyValidator]
 * does: each environment lists the Auth0 applications whose tokens it accepts.
 */
fun tokenValidator(
    issuer: String,
    audience: String,
    allowedClients: Set<String>,
): OAuth2TokenValidator<Jwt> =
    DelegatingOAuth2TokenValidator(
        JwtValidators.createDefaultWithIssuer(issuer),
        AudienceValidator(audience),
        AuthorisedPartyValidator(allowedClients),
    )

/**
 * Accepts a token only when its `azp` (authorised party: the Auth0 application that requested it)
 * is on this environment's allow-list, `AUTH0_ALLOWED_CLIENTS`.
 *
 * This is what stops a token minted for UAT (by the "Dev & UAT" dashboard app) being replayed
 * against production, and production's machine-to-machine keys being used against UAT. Auth0 puts
 * `azp` on both user tokens and client-credentials tokens (checked against real tokens of each
 * kind, 2026-11-03).
 *
 * Fails closed: an empty list accepts nothing. `RequiredConfigurationCheck` refuses to start
 * without one, so an empty list is a misconfiguration that is reported at startup rather than a
 * silent outage.
 */
class AuthorisedPartyValidator(
    private val allowedClients: Set<String>,
) : OAuth2TokenValidator<Jwt> {
    override fun validate(jwt: Jwt): OAuth2TokenValidatorResult {
        val azp = jwt.getClaimAsString("azp")
        if (azp != null && azp in allowedClients) {
            return OAuth2TokenValidatorResult.success()
        }
        logger.warn { "Rejected a token from Auth0 application '$azp': not in AUTH0_ALLOWED_CLIENTS for this environment" }
        return OAuth2TokenValidatorResult.failure(
            OAuth2Error("invalid_token", "The token was not issued to an application this environment accepts", null),
        )
    }

    companion object {
        fun parse(csv: String): Set<String> =
            csv
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
    }
}

class AudienceValidator(
    private val audience: String,
) : OAuth2TokenValidator<Jwt> {
    override fun validate(jwt: Jwt): OAuth2TokenValidatorResult {
        val error = OAuth2Error("invalid_token", "The required audience is missing", null)
        return if (jwt.audience?.contains(audience) == true) {
            OAuth2TokenValidatorResult.success()
        } else {
            OAuth2TokenValidatorResult.failure(error)
        }
    }
}

/**
 * Maps the token's `permissions` claim to authorities.
 *
 * With [devTechWildcard] on, a token carrying [DevTechWildcard.PERMISSION] is also granted every
 * authority in [DevTechWildcard.AUTHORITIES]. The flag is `AUTH_DEV_TECH_WILDCARD`, and
 * `RequiredConfigurationCheck` refuses to start with it on outside the `local` and `testing`
 * profiles, so production always uses the explicit, per-permission checks.
 */
class Auth0TokenConverter(
    private val devTechWildcard: Boolean = false,
) : Converter<Jwt, AbstractAuthenticationToken> {
    private val converter = JwtGrantedAuthoritiesConverter()

    override fun convert(jwt: Jwt): AbstractAuthenticationToken {
        val authorities = converter.convert(jwt)!!
        val permissions = (jwt.claims["permissions"] as? List<*>).orEmpty().filterIsInstance<String>()
        permissions.forEach { authorities.add(SimpleGrantedAuthority(it)) }
        if (devTechWildcard && DevTechWildcard.PERMISSION in permissions) {
            DevTechWildcard.AUTHORITIES.forEach { authorities.add(SimpleGrantedAuthority(it)) }
        }
        return JwtAuthenticationToken(jwt, authorities.distinct())
    }
}

/**
 * The "dev:tech" permission: developers' full access in local and UAT, without any extra access
 * in production.
 */
object DevTechWildcard {
    const val PERMISSION = "dev:tech"

    /** The only profiles in which the wildcard may be switched on. */
    val ALLOWED_PROFILES = setOf("local", "testing")

    /**
     * Every authority the application checks, EXCEPT user management. The tenant is shared by
     * every environment, so `write:users` in UAT can assign a production role; the wildcard must
     * never grant it. DevTechWildcardCoverageTest fails if an authority is checked in code but
     * missing here, so the wildcard cannot silently fall behind.
     */
    val AUTHORITIES =
        setOf(
            "app:admin",
            "read:content",
            "write:content",
            "read:donorParents",
            "write:donorParents",
            "delete:donorParents",
            "read:donors",
            "write:donors",
            "delete:donors",
            "read:kits",
            "write:kits",
            "delete:kits",
            "read:organisations",
            "write:organisations",
            "write:requests",
            "write:referees",
            "delete:organisations",
        )

    /** Deliberately excluded from [AUTHORITIES]: they reach the shared Auth0 tenant. */
    val EXCLUDED = setOf("read:users", "write:users", "delete:users")
}
