package cta.app.config

import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/**
 * Resolves the originating client IP for the current request, for use as a rate-limit key.
 *
 * Prefers Cloudflare's `CF-Connecting-IP` header: in production only Cloudflare can reach the
 * container app, so that header is trustworthy. Falls back to `request.remoteAddr` (which the
 * app derives from X-Forwarded-For via `server.forward-headers-strategy: NATIVE` and is therefore
 * spoofable) — acceptable for a soft throttle. Returns "unknown" outside a request context.
 *
 * Works during GraphQL execution because the servlet request is available via
 * RequestContextHolder (same mechanism as GraphQlTelemetryInterceptor).
 */
@Component
class ClientIpResolver {
    fun resolve(): String {
        val attributes =
            RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes
                ?: return "unknown"
        val request = attributes.request
        val cfConnectingIp = request.getHeader("CF-Connecting-IP")
        if (!cfConnectingIp.isNullOrBlank()) return cfConnectingIp
        return request.remoteAddr ?: "unknown"
    }
}
