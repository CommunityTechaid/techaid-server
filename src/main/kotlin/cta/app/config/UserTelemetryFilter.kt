package cta.app.config

import io.opentelemetry.api.trace.Span
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Tags the current OpenTelemetry span with enduser.id so App Insights
 * populates AppRequests.UserAuthenticatedId. Runs after Spring Security
 * has populated the SecurityContext.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
class UserTelemetryFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        try {
            filterChain.doFilter(request, response)
        } finally {
            runCatching {
                val auth = SecurityContextHolder.getContext().authentication
                val userId =
                    if (auth == null || !auth.isAuthenticated || auth.principal == "anonymousUser") {
                        "anonymous"
                    } else {
                        auth.name ?: "anonymous"
                    }
                Span.current().setAttribute("enduser.id", userId)
            }
        }
    }
}
