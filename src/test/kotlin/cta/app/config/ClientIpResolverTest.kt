package cta.app.config

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/**
 * Pins which address [ClientIpResolver] hands to the public rate limiters.
 *
 * The origin FQDN of the container app is reachable without going through Cloudflare, so a
 * `CF-Connecting-IP` header is only meaningful when the immediate peer (remoteAddr, after
 * Tomcat's RemoteIpValve has consumed the ingress hop) is itself a Cloudflare edge address.
 * Before this was pinned, the header was trusted from any peer, so a direct caller could pick a
 * fresh rate-limit bucket per request.
 */
class ClientIpResolverTest {
    private val resolver = ClientIpResolver()

    @AfterEach
    fun clearRequest() {
        RequestContextHolder.resetRequestAttributes()
    }

    private fun resolveFor(
        peer: String,
        cfConnectingIp: String? = null,
    ): String {
        val request = MockHttpServletRequest()
        request.remoteAddr = peer
        if (cfConnectingIp != null) request.addHeader("CF-Connecting-IP", cfConnectingIp)
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request))
        return resolver.resolve()
    }

    @Test
    fun `trusts CF-Connecting-IP when the peer is a Cloudflare IPv4 edge`() {
        assertEquals("203.0.113.5", resolveFor(peer = "172.69.10.20", cfConnectingIp = "203.0.113.5"))
    }

    @Test
    fun `trusts CF-Connecting-IP when the peer is a Cloudflare IPv6 edge`() {
        assertEquals("2001:db8::1", resolveFor(peer = "2a06:98c0:3600::103", cfConnectingIp = "2001:db8::1"))
    }

    @Test
    fun `ignores CF-Connecting-IP when the peer is not Cloudflare`() {
        // A direct request to the origin: the peer is the caller's own address.
        assertEquals("198.51.100.7", resolveFor(peer = "198.51.100.7", cfConnectingIp = "203.0.113.5"))
    }

    @Test
    fun `ignores CF-Connecting-IP when the peer is an internal hop`() {
        assertEquals("100.100.0.229", resolveFor(peer = "100.100.0.229", cfConnectingIp = "203.0.113.5"))
    }

    @Test
    fun `falls back to the peer when a Cloudflare edge sends no header`() {
        assertEquals("162.158.1.1", resolveFor(peer = "162.158.1.1"))
        assertEquals("162.158.1.1", resolveFor(peer = "162.158.1.1", cfConnectingIp = " "))
    }

    @Test
    fun `returns unknown outside a request`() {
        RequestContextHolder.resetRequestAttributes()
        assertEquals("unknown", resolver.resolve())
    }
}
