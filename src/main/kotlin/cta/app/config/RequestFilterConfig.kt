package cta.app.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import mu.KotlinLogging
import net.logstash.logback.marker.Markers
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingResponseWrapper

@Configuration
class RequestFilterConfig {
    @Bean
    fun accessLoggingFilterRegistration(): FilterRegistrationBean<AccessLoggingFilter> {
        val registration = FilterRegistrationBean(AccessLoggingFilter())
        // Must run before UnknownPathFilter so we capture 404s for unknown paths too
        registration.order = Int.MIN_VALUE
        return registration
    }

    @Bean
    fun unknownPathFilterRegistration(): FilterRegistrationBean<UnknownPathFilter> {
        val registration = FilterRegistrationBean(UnknownPathFilter())
        registration.order = Int.MIN_VALUE + 1
        return registration
    }
}

private val accessLogger = KotlinLogging.logger("cta.access")

class AccessLoggingFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val startMs = System.currentTimeMillis()
        val wrappedResponse = ContentCachingResponseWrapper(response)
        try {
            filterChain.doFilter(request, wrappedResponse)
        } finally {
            wrappedResponse.copyBodyToResponse()
            val durationMs = System.currentTimeMillis() - startMs
            val queryString = request.queryString
            val fullPath = if (queryString != null) "${request.requestURI}?$queryString" else request.requestURI
            // With forward-headers-strategy: NATIVE, Tomcat's RemoteIpValve already resolves
            // X-Forwarded-For so remoteAddr is the real client IP.
            val remoteIp = request.remoteAddr
            val marker =
                Markers.appendEntries(
                    mapOf(
                        "type" to "access",
                        "method" to request.method,
                        "path" to request.requestURI,
                        "query" to (request.queryString ?: ""),
                        "full_path" to fullPath,
                        "status" to wrappedResponse.status,
                        "request_content_length" to request.contentLengthLong,
                        "response_content_length" to wrappedResponse.contentSize,
                        "duration_ms" to durationMs,
                        "remote_ip" to remoteIp,
                        "user_agent" to (request.getHeader("User-Agent") ?: ""),
                        "referer" to (request.getHeader("Referer") ?: ""),
                        "protocol" to request.protocol,
                    ),
                )
            accessLogger.info(marker, "{} {} {} {} {}ms", remoteIp, request.method, fullPath, wrappedResponse.status, durationMs)
        }
    }
}

class UnknownPathFilter : OncePerRequestFilter() {
    private val allowedPrefixes =
        listOf(
            "/graphql",
            "/actuator",
            "/login",
            "/error",
            "/typeform/hook",
        )

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val path = request.requestURI
        if (allowedPrefixes.any { path == it || path.startsWith("$it/") }) {
            filterChain.doFilter(request, response)
        } else {
            response.sendError(HttpServletResponse.SC_NOT_FOUND)
        }
    }
}
