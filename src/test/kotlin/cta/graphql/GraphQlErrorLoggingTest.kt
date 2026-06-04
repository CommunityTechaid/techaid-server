package cta.graphql

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cta.app.config.GraphQlTelemetryConfig
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Verifies that a GraphQL variable-coercion error (returned in the body with HTTP 200) is
 * logged by [GraphQlTelemetryConfig] together with the offending input variables, so bulk
 * inserts that send a numeric value into a String field can be diagnosed from the logs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class GraphQlErrorLoggingTest {
    @MockBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var mockMvc: MockMvc

    private val telemetryLogger = LoggerFactory.getLogger(GraphQlTelemetryConfig::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun attachAppender() {
        appender.start()
        telemetryLogger.addAppender(appender)
    }

    @AfterEach
    fun detachAppender() {
        telemetryLogger.detachAppender(appender)
        appender.stop()
    }

    @Test
    fun `coercion error is logged with operation name and offending input variables`() {
        // model is String! in CreateKitInput; send a number to reproduce the bulk-insert failure.
        val body =
            """
            {
              "query": "mutation createKit(${'$'}data: CreateKitInput!) { createKit(data: ${'$'}data) { id } }",
              "variables": { "data": { "type": "LAPTOP", "model": 3000 } }
            }
            """.trimIndent()

        // Accept application/json mirrors the production clients (which see HTTP 200 with the
        // error in the body); application/graphql-response+json would instead yield HTTP 400.
        mockMvc
            .perform(
                post("/graphql")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.errors").isArray)

        val warning =
            appender.list.firstOrNull { it.level == Level.WARN && it.formattedMessage.contains("GraphQL error") }

        assertTrue(warning != null, "expected a WARN log for the GraphQL error")
        val message = warning!!.formattedMessage
        assertTrue(message.contains("createKit"), "log should name the failing operation: $message")
        assertTrue(message.contains("\"model\":3000"), "log should show the offending numeric input: $message")
        assertTrue(message.contains("String"), "log should include the coercion error text: $message")
    }
}
