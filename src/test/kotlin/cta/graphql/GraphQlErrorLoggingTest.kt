package cta.graphql

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.jayway.jsonpath.JsonPath
import cta.app.config.GraphQlTelemetryConfig
import cta.app.config.validClientErrorRef
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
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
    @MockitoBean
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

    // ---- client-supplied error ref (X-CTA-Ref, #371) ----

    private val coercionErrorBody =
        """
        {
          "query": "mutation createKit(${'$'}data: CreateKitInput!) { createKit(data: ${'$'}data) { id } }",
          "variables": { "data": { "type": "LAPTOP", "model": 3000 } }
        }
        """.trimIndent()

    private fun graphQl(
        body: String,
        ref: String? = null,
    ): MvcResult {
        val request =
            post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(body)
        if (ref != null) request.header("X-CTA-Ref", ref)
        return mockMvc.perform(request).andExpect(status().isOk).andReturn()
    }

    private fun errorWarnings() =
        appender.list.filter { it.level == Level.WARN && it.formattedMessage.contains("GraphQL error on operation") }

    private fun errorRefs(content: String): List<Any?> = JsonPath.read(content, "$.errors[*].extensions.ref")

    @Test
    fun `a valid client ref is in the error log line and in every error's extensions`() {
        val content = graphQl(coercionErrorBody, ref = "K7Q2M9XA").response.contentAsString

        val warnings = errorWarnings()
        assertTrue(warnings.isNotEmpty(), "expected a WARN log for the GraphQL error")
        warnings.forEach {
            assertTrue(
                it.formattedMessage.startsWith("GraphQL error on operation 'createKit' [ValidationError] [ref K7Q2M9XA]: "),
                "the log line must carry the client ref so AppTraces can be searched by it: ${it.formattedMessage}",
            )
        }
        val refs = errorRefs(content)
        assertTrue(refs.isNotEmpty(), "expected at least one error: $content")
        refs.forEach { assertEquals("K7Q2M9XA", it, "each error should echo the ref back: $content") }
        // The existing classification extension must survive alongside the ref.
        val classifications: List<Any?> = JsonPath.read(content, "$.errors[*].extensions.classification")
        classifications.forEach { assertEquals("ValidationError", it, content) }
    }

    @Test
    fun `an invalid client ref is never logged or returned, and the request is otherwise unaffected`() {
        val baseline = graphQl(coercionErrorBody).response.contentAsString
        val baselineLines = errorWarnings().map { it.formattedMessage }

        val invalidRefs = listOf("A".repeat(33), "BAD*REF", "has space", "semi;colon", "", "caf\u00e9")
        for (bad in invalidRefs) {
            appender.list.clear()
            val content = graphQl(coercionErrorBody, ref = bad).response.contentAsString

            assertEquals(baseline, content, "an invalid ref must not change the response at all")
            assertFalse(content.contains("\"ref\""), "an invalid ref must not be returned: $content")
            val lines = errorWarnings().map { it.formattedMessage }
            assertEquals(baselineLines.size, lines.size, "same errors, same number of log lines")
            lines.zip(baselineLines).forEach { (line, base) ->
                assertEquals(
                    base.replaceFirst("[ValidationError]: ", "[ValidationError] [ref invalid]: "),
                    line,
                    "an invalid ref is logged as a fixed placeholder, never verbatim",
                )
            }
        }
    }

    /**
     * A CR/LF in the ref could forge a log line. Spring Security's firewall already rejects any
     * header value carrying control characters with a 400 before GraphQL runs (true of every header,
     * before and after #371); the validator test below pins that the interceptor would drop it too.
     */
    @Test
    fun `a ref with CR or LF never reaches the log or the response`() {
        for (bad in listOf("ABC\r\nGraphQL error on operation 'forged' [x]: forged", "ABC\nforged", "ABC\rforged")) {
            appender.list.clear()
            val request =
                post("/graphql")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .content(coercionErrorBody)
                    .header("X-CTA-Ref", bad)
            val response = mockMvc.perform(request).andReturn().response

            assertEquals(400, response.status, "the firewall rejects control characters in a header")
            assertFalse(response.contentAsString.contains("forged"), response.contentAsString)
            assertTrue(appender.list.none { it.formattedMessage.contains("forged") }, "the raw header leaked: ${appender.list}")
        }
    }

    @Test
    fun `the ref validator accepts only 1 to 32 letters, digits and hyphens`() {
        for (ok in listOf("K7Q2M9XA", "a", "A".repeat(32), "ab-12-CD")) {
            assertEquals(ok, validClientErrorRef(ok), "should accept '$ok'")
        }
        for (bad in listOf(null, "", "A".repeat(33), "ABC\n", "ABC\r\nX", "AB C", "AB_C", "AB*C", "caf\u00e9", "\u0661\u0662")) {
            assertEquals(null, validClientErrorRef(bad), "should reject '$bad'")
        }
    }

    @Test
    fun `no ref header gives exactly today's log line and no ref extension`() {
        val content = graphQl(coercionErrorBody).response.contentAsString

        val warnings = errorWarnings()
        assertTrue(warnings.isNotEmpty(), "expected a WARN log for the GraphQL error")
        warnings.forEach {
            assertEquals(
                "GraphQL error on operation '{}' [{}]: {} (path={}, locations={}) — input variables: {}",
                it.message,
                "the log format without a ref must be unchanged",
            )
            assertFalse(it.formattedMessage.contains("[ref "), it.formattedMessage)
        }
        assertTrue(errorRefs(content).isEmpty(), "no ref was sent, so none may be returned: $content")
    }

    @Test
    fun `a successful response is unchanged by a ref and logs nothing`() {
        val body = """{"query":"{ __typename }"}"""
        val withoutRef = graphQl(body).response.contentAsString
        val withRef = graphQl(body, ref = "K7Q2M9XA").response.contentAsString

        assertEquals(withoutRef, withRef, "a successful response must not change when a ref is sent")
        assertFalse(withRef.contains("errors"), withRef)
        assertTrue(appender.list.isEmpty(), "nothing is logged for a successful request: ${appender.list}")
    }
}
