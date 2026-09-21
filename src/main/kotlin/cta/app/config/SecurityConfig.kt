package cta.app.config

import com.fasterxml.jackson.databind.ObjectMapper
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

    @Bean
    @Lazy
    fun jwtDecoder(): JwtDecoder {
        val jwtDecoder = JwtDecoders.fromOidcIssuerLocation(issuer) as NimbusJwtDecoder
        val audienceValidator = AudienceValidator(audience)
        val withIssuer = JwtValidators.createDefaultWithIssuer(issuer)
        val withAudience = DelegatingOAuth2TokenValidator(withIssuer, audienceValidator)
        jwtDecoder.setJwtValidator(withAudience)
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
        http.oauth2ResourceServer { it.jwt { jwt -> jwt.jwtAuthenticationConverter(Auth0TokenConverter()) } }
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

class AudienceValidator(
    private val audience: String,
) : OAuth2TokenValidator<Jwt> {
    override fun validate(jwt: Jwt): OAuth2TokenValidatorResult {
        val error = OAuth2Error("invalid_token", "The required audience is missing", null)
        return if (jwt.audience.contains(audience)) {
            OAuth2TokenValidatorResult.success()
        } else {
            OAuth2TokenValidatorResult.failure(error)
        }
    }
}

class Auth0TokenConverter : Converter<Jwt, AbstractAuthenticationToken> {
    private val converter = JwtGrantedAuthoritiesConverter()

    override fun convert(jwt: Jwt): AbstractAuthenticationToken {
        val authorities = converter.convert(jwt)!!
        val permissions = jwt.claims["permissions"]
        if (permissions is List<*>) {
            permissions.forEach {
                if (it is String) {
                    authorities.add(SimpleGrantedAuthority(it))
                }
            }
        }
        return JwtAuthenticationToken(jwt, authorities)
    }
}
