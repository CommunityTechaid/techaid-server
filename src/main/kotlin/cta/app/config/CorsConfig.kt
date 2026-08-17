package cta.app.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * How long a browser may reuse a successful CORS preflight before asking again.
 *
 * Spring's default is 1800s. That mattered here because the API scales to zero: a preflight is a
 * request like any other, so an expired one wakes the container. Measured over 14 days to
 * 2026-08-17, `OPTIONS /graphql` was the single largest identifiable cause of cold starts in
 * production — 36 of the 89 wakes App Insights could attribute, ahead of every scanner.
 *
 * Preflights are also the one kind of request that genuinely does not need the origin: the answer
 * is static configuration. Letting browsers cache it for longer removes those wakes without
 * blocking anyone or changing what the API does.
 *
 * Set to 24h, but read that as an upper bound rather than a promise — browsers clamp it, and
 * Chrome's ceiling is 2h. The realistic effect is roughly 4x fewer preflights there and up to 48x
 * in browsers that honour the full value.
 *
 * The cost of a longer cache: a change to the allowed origins, methods or headers below takes up
 * to this long to reach a browser that has already cached the old answer. That is a real
 * consideration when editing this file — expect a lag, and do not conclude a CORS change failed
 * because a warm browser has not picked it up.
 */
private const val PREFLIGHT_CACHE_SECONDS = 86400L

@Configuration
class CorsConfig {
    @Bean
    fun corsConfigurer(): WebMvcConfigurer =
        object : WebMvcConfigurer {
            override fun addCorsMappings(registry: CorsRegistry) {
                registry
                    .addMapping("/graphql")
                    .allowedOrigins("https://app-testing.communitytechaid.org.uk", "https://app.communitytechaid.org.uk")
                    .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                    .allowedHeaders("*")
                    .allowCredentials(true)
                    .maxAge(PREFLIGHT_CACHE_SECONDS)
            }
        }
}
