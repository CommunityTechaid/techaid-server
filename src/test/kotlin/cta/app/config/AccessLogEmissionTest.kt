package cta.app.config

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get

/**
 * Pins that the `cta.access` access log is actually emitted at the level `logback-spring.xml`
 * configures for it.
 *
 * Why this test exists: the emission level is set in TWO places that must agree —
 * `AccessLoggingFilter`'s call site (`RequestFilterConfig.kt`) and the explicit
 * `<logger name="cta.access" level="...">` in `logback-spring.xml`. The logger carries its own
 * explicit level, so it does NOT inherit `logging.level.cta` from application.yml; changing only
 * one of the two silently drops every access log line from stdout — and therefore from
 * `ContainerAppConsoleLogs_CL`, which is the only place the access log lands once it is held
 * below the Application Insights agent's INFO capture threshold.
 *
 * That failure is invisible in production: no error, no exception, just an empty table.
 *
 * The level itself is deliberately NOT overridden here — reading the configured level is the
 * whole point of the test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class AccessLogEmissionTest {
    @MockBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var mockMvc: MockMvc

    private val accessLogger = LoggerFactory.getLogger("cta.access") as Logger
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun attachAppender() {
        appender.start()
        accessLogger.addAppender(appender)
    }

    @AfterEach
    fun detachAppender() {
        accessLogger.detachAppender(appender)
        appender.stop()
    }

    @Test
    fun `every request emits exactly one cta_access line that survives the configured level`() {
        mockMvc.perform(get("/actuator/health"))

        val events = appender.list
        assertEquals(
            1,
            events.size,
            "Expected exactly one cta.access event. Zero means the call site's level is below the " +
                "level configured for the cta.access logger in logback-spring.xml — the access log " +
                "is silently disabled.",
        )
    }

    @Test
    fun `the access line carries the structured fields operators query on`() {
        mockMvc.perform(get("/actuator/health"))

        val event = appender.list.single()

        // The structured fields ride on a logstash Markers.appendEntries marker, which is what the
        // LogstashEncoder serialises into the JSON line. Assert against its rendering rather than
        // SLF4J key-value pairs, which this call site does not use.
        val marker = requireNonNull(event.marker, "access log line has no structured marker").toString()
        listOf("type", "method", "path", "status", "duration_ms", "remote_ip").forEach { key ->
            assertTrue(marker.contains(key), "access log marker is missing the '$key' field: $marker")
        }
        assertTrue(event.formattedMessage.contains("GET /actuator/health 200"), event.formattedMessage)
    }

    @Test
    fun `unknown paths are access logged too, since the filter outranks UnknownPathFilter`() {
        mockMvc.perform(get("/wp-admin.php"))

        val event = appender.list.single()
        assertTrue(
            event.formattedMessage.contains("GET /wp-admin.php 404"),
            "404s must still be access logged: ${event.formattedMessage}",
        )
    }

    private fun <T> requireNonNull(
        value: T?,
        message: String,
    ): T = value ?: throw AssertionError(message)
}
