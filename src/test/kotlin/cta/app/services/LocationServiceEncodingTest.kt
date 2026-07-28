package cta.app.services

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.test.util.ReflectionTestUtils
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

/**
 * Real addresses contain spaces, so the geocoding URI must be built with the query
 * parameters ENCODED. Building with `build(true)` claims they are already encoded and
 * makes Spring reject the first space, throwing before the HTTP call is ever made —
 * which `findLocation` swallows, silently returning null and leaving kits with no
 * coordinates. Production ran that way for at least 30 days: zero outbound calls to
 * the geocoding host, while `updateKit` logged
 * `Invalid character ' ' for QUERY_PARAM in "…, 1-3 Brixton Rd, London SW9 6DE, UK"`.
 */
class LocationServiceEncodingTest {
    /**
     * The regression guard: an address containing spaces (and a comma) must actually
     * reach the endpoint, with the address round-tripping intact once decoded.
     */
    @Test
    fun `findLocation sends an address containing spaces to the endpoint`() {
        val address = "336 Brixton Road, London SW9 7AA, UK"

        ServerSocket(0).use { server ->
            var requestLine: String? = null

            val responder =
                thread(start = true) {
                    server.accept().use { socket ->
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                        requestLine = reader.readLine()
                        socket.getOutputStream().write(
                            (
                                "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: application/json\r\n" +
                                    "Connection: close\r\n\r\n" +
                                    """{"status":"OK","results":[]}"""
                            ).toByteArray(),
                        )
                        socket.getOutputStream().flush()
                    }
                }

            val service = LocationService()
            ReflectionTestUtils.setField(service, "key", "test-key")
            ReflectionTestUtils.setField(service, "baseUrl", "http://localhost:${server.localPort}/geocode")

            val response = service.findLocation(address)
            responder.join(10_000)

            assertNotNull(
                requestLine,
                "the geocoding endpoint was never contacted — the URI threw before the call was made",
            )
            assertNotNull(response, "a successful geocoding call must not degrade to null")

            // The space must be percent- or plus-encoded on the wire, never raw.
            assertTrue(
                !requestLine!!.substringAfter("address=").substringBefore(" HTTP/").contains(' '),
                "raw space leaked into the query string: $requestLine",
            )

            val sentAddress =
                URLDecoder.decode(
                    requestLine!!.substringAfter("address=").substringBefore(" HTTP/"),
                    StandardCharsets.UTF_8,
                )
            assertEquals(address, sentAddress, "the address must survive encoding intact")
        }
    }

    /**
     * The API key must not be double-encoded on its way out — switching the builder to
     * encode everything is the obvious way to break a key containing reserved characters.
     */
    @Test
    fun `findLocation does not corrupt the api key`() {
        ServerSocket(0).use { server ->
            var requestLine: String? = null

            val responder =
                thread(start = true) {
                    server.accept().use { socket ->
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                        requestLine = reader.readLine()
                        socket.getOutputStream().write(
                            (
                                "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: application/json\r\n" +
                                    "Connection: close\r\n\r\n" +
                                    """{"status":"OK","results":[]}"""
                            ).toByteArray(),
                        )
                        socket.getOutputStream().flush()
                    }
                }

            val key = "AIzaSy-Test_Key123"
            val service = LocationService()
            ReflectionTestUtils.setField(service, "key", key)
            ReflectionTestUtils.setField(service, "baseUrl", "http://localhost:${server.localPort}/geocode")

            service.findLocation("Kennington Park Business Centre")
            responder.join(10_000)

            assertNotNull(requestLine, "the geocoding endpoint was never contacted")
            val sentKey =
                URLDecoder.decode(
                    requestLine!!.substringAfter("key=").substringBefore("&"),
                    StandardCharsets.UTF_8,
                )
            assertEquals(key, sentKey, "the api key must reach the endpoint unmodified")
        }
    }
}
