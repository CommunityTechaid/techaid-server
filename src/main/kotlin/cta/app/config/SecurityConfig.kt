package cta.app.config

import cta.auth.AuthService
//import cta.auth.CorsFilter
import cta.auth.SecretAuthenticationFilter
import cta.auth.TokenAuthenticationFilter
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.converter.Converter
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer
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
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter
import org.springframework.security.web.session.SessionManagementFilter

private val logger = KotlinLogging.logger {}

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(securedEnabled = true)
class SecurityConfig(
    //private val corsFilter: CorsFilter,
    private val authService: AuthService
) {
    @Value("\${auth0.audience}")
    private var audience: String = ""

    @Value("\${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    var issuer: String = ""

    @Bean
    fun jwtDecoder(): JwtDecoder {
        val jwtDecoder = JwtDecoders.fromOidcIssuerLocation(issuer) as NimbusJwtDecoder
        val audienceValidator = AudienceValidator(audience)
        val withIssuer = JwtValidators.createDefaultWithIssuer(issuer)
        val withAudience = DelegatingOAuth2TokenValidator(withIssuer, audienceValidator)
        jwtDecoder.setJwtValidator(withAudience)
        return jwtDecoder
    }

    @Bean
    public fun authenticationManager(authenticationConfiguration: AuthenticationConfiguration): AuthenticationManager {
        return authenticationConfiguration.getAuthenticationManager()
    }

    fun secretAuthenticationFilter(authenticationConfiguration: AuthenticationConfiguration): SecretAuthenticationFilter {
        val filter = SecretAuthenticationFilter()
        filter.setAuthenticationManager(authenticationManager(authenticationConfiguration))
        filter.setAuthenticationFailureHandler(SimpleUrlAuthenticationFailureHandler("/login?error=true"))
        return filter
    }

    @Bean
    public fun filterChain(
        http: HttpSecurity,
        authenticationConfiguration: AuthenticationConfiguration): SecurityFilterChain {
        http.csrf { it.disable() }
        http.headers { headers ->
            headers.httpStrictTransportSecurity { hsts ->
                hsts.includeSubDomains(true)
                    .maxAgeInSeconds(31536000) // 1 year
            }
        }
        //http.addFilterBefore(corsFilter, SessionManagementFilter::class.java)
        http.addFilterBefore(TokenAuthenticationFilter(authService), BasicAuthenticationFilter::class.java)
        http.addFilterBefore(secretAuthenticationFilter(authenticationConfiguration), UsernamePasswordAuthenticationFilter::class.java)
        http.oauth2ResourceServer { oauth2 ->
            oauth2.jwt { jwt ->
                jwt.jwtAuthenticationConverter(Auth0TokenConverter())
            }
        }
        http.authorizeHttpRequests { authorize ->
            authorize.anyRequest().permitAll()
        }
        return http.build()
    }
}

class AudienceValidator(private val audience: String) : OAuth2TokenValidator<Jwt> {
    override fun validate(jwt: Jwt): OAuth2TokenValidatorResult {
        val error = OAuth2Error("invalid_token", "The required audience is missing", null)
        return if (jwt.audience.contains(audience)) {
            OAuth2TokenValidatorResult.success()
        } else OAuth2TokenValidatorResult.failure(error)
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
