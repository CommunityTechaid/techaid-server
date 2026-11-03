package cta.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.ServletException
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import mu.KotlinLogging
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.GenericFilterBean
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.util.LinkedHashMap

/**
 * Its own logger name, so admin-secret use can be found in AppTraces without matching on text:
 * `AppTraces | where Properties.LoggerName == "cta.auth.admin-secret"`.
 */
private val adminSecretLogger = KotlinLogging.logger("cta.auth.admin-secret")

/**
 * Filter to intercept JWT Tokens from the header and create a user session
 */
class TokenAuthenticationFilter(
    private val authService: AuthService,
    private val objectMapper: ObjectMapper,
) : GenericFilterBean() {
    /**
     * Verifies JWT token from the http-request header
     */
    @Throws(IOException::class, ServletException::class)
    override fun doFilter(
        request: ServletRequest,
        response: ServletResponse,
        chain: FilterChain,
    ) {
        val httpRequest = request as HttpServletRequest
        val httpResponse = response as HttpServletResponse
        var accessToken = request.getHeader(authService.adminHeader) ?: ""

        if (accessToken.isNotBlank()) {
            var admin = authService.adminForToken(accessToken)
            if (admin == null) {
                haltRequest(
                    httpResponse,
                    httpRequest,
                    "Invalid superuser token specified in ${authService.adminHeader}",
                    "INVALID_ADMIN_TOKEN",
                )
                return
            }
            val authentication =
                UsernamePasswordAuthenticationToken(
                    admin,
                    accessToken,
                    admin.authorities,
                )
            SecurityContextHolder.getContext().authentication = authentication
            // Measurement (2026-11): the admin secret looks like a legacy artefact with no
            // automated caller. Logging every use (never the value) is how production proves
            // whether anything still depends on it before it is removed.
            adminSecretLogger.warn { "Request authenticated with the admin secret: ${httpRequest.method} ${httpRequest.requestURI}" }
            chain.doFilter(request, response)
            return
        }
        chain.doFilter(request, response)
    }

    private fun loggedIn(): Boolean {
        val authentication = SecurityContextHolder.getContext().authentication
        return authentication != null && authentication.isAuthenticated
    }

    private fun haltRequest(
        httpResponse: HttpServletResponse,
        httpRequest: HttpServletRequest,
        message: String?,
        type: String,
    ) {
        val responseBody = LinkedHashMap<String, Any?>()
        httpResponse.status = HttpServletResponse.SC_UNAUTHORIZED
        httpResponse.addHeader("Content-Type", "application/json;charset=utf-8")

        responseBody["url"] = httpRequest.requestURI
        responseBody["method"] = httpRequest.method
        responseBody["message"] = message
        responseBody["type"] = type
        httpResponse.writer.write(objectMapper.writeValueAsString(responseBody))
        httpResponse.writer.flush()
        httpResponse.writer.close()
    }
}
