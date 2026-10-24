package cta.app.config

import org.springframework.security.web.util.matcher.IpAddressMatcher
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/**
 * Resolves the originating client IP for the current request, for use as a rate-limit key.
 *
 * `request.remoteAddr` is the immediate peer as Tomcat's RemoteIpValve sees it
 * (`server.forward-headers-strategy: NATIVE`): the valve walks X-Forwarded-For from the right,
 * skipping the container-app ingress hop (a private / 100.64.0.0/10 address, inside Spring Boot's
 * default `internal-proxies`), and stops at the first public address. For traffic that came
 * through Cloudflare that is the Cloudflare edge; for a request sent straight to the origin FQDN
 * it is the caller itself. (Measured in production access logs: every request via
 * api.communitytechaid.org.uk logs a Cloudflare edge address.)
 *
 * So `CF-Connecting-IP` is honoured only when that peer is inside Cloudflare's published ranges.
 * From any other peer the header is attacker-chosen and is ignored, and the peer address is the
 * key. Returns "unknown" outside a request context.
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
        val peer = request.remoteAddr ?: return "unknown"
        val cfConnectingIp = request.getHeader("CF-Connecting-IP")
        if (!cfConnectingIp.isNullOrBlank() && isCloudflare(peer)) return cfConnectingIp.trim()
        return peer
    }

    private fun isCloudflare(address: String): Boolean =
        CLOUDFLARE_MATCHERS.any {
            try {
                it.matches(address)
            } catch (e: IllegalArgumentException) {
                // Not an IP literal; never treat it as Cloudflare.
                false
            }
        }

    companion object {
        /**
         * Cloudflare's published edge ranges, fetched 2026-10-24 from
         * https://www.cloudflare.com/ips-v4 and https://www.cloudflare.com/ips-v6.
         *
         * Embedded rather than fetched at runtime so a cold start never depends on an outbound
         * call. To refresh: re-fetch both URLs and replace this list. Cloudflare changes it
         * rarely and announces changes in advance. A stale list fails closed, not open: an unlisted
         * edge is treated as an ordinary peer, so clients behind it share that edge's bucket
         * (stricter throttling) until the list is updated.
         */
        val CLOUDFLARE_RANGES =
            listOf(
                "173.245.48.0/20",
                "103.21.244.0/22",
                "103.22.200.0/22",
                "103.31.4.0/22",
                "141.101.64.0/18",
                "108.162.192.0/18",
                "190.93.240.0/20",
                "188.114.96.0/20",
                "197.234.240.0/22",
                "198.41.128.0/17",
                "162.158.0.0/15",
                "104.16.0.0/13",
                "104.24.0.0/14",
                "172.64.0.0/13",
                "131.0.72.0/22",
                "2400:cb00::/32",
                "2606:4700::/32",
                "2803:f800::/32",
                "2405:b500::/32",
                "2405:8100::/32",
                "2a06:98c0::/29",
                "2c0f:f248::/32",
            )

        private val CLOUDFLARE_MATCHERS = CLOUDFLARE_RANGES.map { IpAddressMatcher(it) }
    }
}
