package cta.app.config

import cta.app.services.FilterService
import io.opentelemetry.api.trace.Span
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Tags the current OpenTelemetry span with enduser.id so App Insights
 * populates AppRequests.UserAuthenticatedId. Uses FilterService (same
 * source as the Envers audit listener) to read the Auth0 namespaced
 * email claim — a friendlier identifier than the raw `auth0|...` sub.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
class UserTelemetryFilter(
    private val filterService: FilterService,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        try {
            filterChain.doFilter(request, response)
        } finally {
            runCatching {
                val user = filterService.userDetails()
                val userId =
                    when {
                        user.email.isNotBlank() -> user.email
                        user.name.isNotBlank() -> user.name
                        else -> "anonymous"
                    }
                Span.current().setAttribute("enduser.id", userId)
            }
        }
    }
}
