package cta.app.services

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import org.springframework.test.util.ReflectionTestUtils
import java.net.ServerSocket
import java.time.Duration

class LocationServiceTimeoutTest {
    /**
     * A stalled Google geocoding call must not hang a request thread indefinitely —
     * on the small container a handful of stuck threads exhausts the Tomcat pool.
     * The server socket below accepts the TCP connection (kernel backlog) but never
     * responds, so without a read timeout this call would block forever.
     */
    @Test
    fun `findLocation gives up when the geocoding endpoint stalls`() {
        ServerSocket(0).use { server ->
            val service = LocationService()
            ReflectionTestUtils.setField(service, "key", "test-key")
            ReflectionTestUtils.setField(service, "baseUrl", "http://localhost:${server.localPort}/geocode")

            var result: LocationResponse? = LocationResponse("not-called", listOf())
            assertTimeoutPreemptively(Duration.ofSeconds(20)) {
                result = service.findLocation("SW9 8RR")
            }

            assertNull(result, "a timed-out geocoding call must degrade to null, not throw")
        }
    }
}
