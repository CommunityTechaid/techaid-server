package cta.app.services

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.jayway.jsonpath.JsonPath
import cta.app.config.CLIENT_ERROR_REF_CONTEXT_KEY
import graphql.GraphQLContext
import graphql.GraphQLError
import graphql.execution.ExecutionStepInfo
import graphql.execution.ResultPath
import graphql.language.Field
import graphql.language.SourceLocation
import graphql.schema.DataFetchingEnvironment
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import jakarta.persistence.EntityNotFoundException
import jakarta.validation.ConstraintViolationException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

/**
 * Keeps raw exception text away from unauthenticated callers.
 *
 * [CustomErrorHandlerConfig] is the global fallback for exceptions no controller handled, and it
 * echoed `ex.message` to whoever asked. The public referral and delivery-booking surfaces are
 * anonymous, so anything an unauthenticated caller can provoke - a missing row, a Hibernate
 * constraint violation, a coercion failure - came back with ids, column names and statement
 * fragments in it.
 *
 * The two carve-outs are as load-bearing as the fix. Authenticated staff must keep the real
 * message, because the dashboard shows it; and bean-validation messages describe only the
 * caller's own input, so the public forms need them to stay usable.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class AnonymousErrorMessageTest {
    @MockitoBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var mockMvc: MockMvc

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun graphQl(
        body: String,
        ref: String? = null,
    ): String {
        val request =
            post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(body)
        if (ref != null) request.header("X-CTA-Ref", ref)
        return mockMvc
            .perform(request)
            .andReturn()
            .response.contentAsString
    }

    /**
     * createDeviceRequest is anonymous, and an unknown contact id makes it throw
     * EntityNotFoundException - which no controller handles, so it lands on the fallback.
     */
    private fun createRequestAgainstMissingContact(ref: String? = null): String {
        val document =
            "mutation { createDeviceRequest(data: {" +
                "referringOrganisationContact: 999999, deviceRequestItems: { laptops: 1 }, " +
                "clientRef: \\\"ERR\\\", borough: \\\"Lambeth\\\", details: \\\"Needs a laptop\\\"}) { id } }"
        return graphQl("""{"query":"$document"}""", ref)
    }

    /** The public delivery-booking submit is anonymous and bean-validates its input. */
    private fun submitBookingWithEmail(email: String): String {
        val document =
            "mutation { submitDeliveryBookingPublic(input: {" +
                "date: \\\"1970-01-01\\\", windowId: \\\"1\\\", firstName: \\\"A\\\", surname: \\\"B\\\", " +
                "email: \\\"$email\\\", phone: \\\"0700000000\\\", address: \\\"1 Road\\\", ctaReference: 1}) { id } }"
        return graphQl("""{"query":"$document"}""")
    }

    @Test
    fun `an anonymous caller gets a generic message, not the exception text`() {
        val content = createRequestAgainstMissingContact()

        assertFalse(
            content.contains("No referring contact found"),
            "the raw exception text must not reach an anonymous caller: $content",
        )
        assertTrue(
            content.contains("Something went wrong"),
            "an anonymous caller should get the generic message: $content",
        )
        assertTrue(
            content.contains("quoting reference"),
            "the generic message should carry a reference support can join to the log: $content",
        )
    }

    @Test
    fun `an anonymous caller still gets field validation feedback`() {
        val content = submitBookingWithEmail("not-an-email")

        assertTrue(
            content.contains("email"),
            "bean validation must still tell a public form which field is wrong: $content",
        )
        assertFalse(
            content.contains("Something went wrong"),
            "a validation failure is not an unexpected error: $content",
        )
    }

    @Test
    fun `an authenticated caller keeps the real message`() {
        SecurityContextHolder.getContext().authentication =
            TestingAuthenticationToken("staff", "n/a", "read:kits")

        val error = resolve(EntityNotFoundException("Unable to locate a kit with id: 42"))

        assertEquals(
            "Unable to locate a kit with id: 42",
            error?.message,
            "staff-facing messages are displayed by the dashboard and must survive",
        )
    }

    @Test
    fun `bean validation messages survive for an anonymous caller`() {
        val error = resolve(ConstraintViolationException("data.email: must be a well-formed email address", emptySet()))

        assertEquals(
            "data.email: must be a well-formed email address",
            error?.message,
            "a validation message only ever describes the caller's own input",
        )
    }

    /**
     * The collection-booking Apps Script classifies `'access denied'` as retryable, and the denial
     * arrives as HTTP 200 with that message - so genericising it would silently disable the
     * calendar sync's cold-start retry. The full suite caught this; keep it caught.
     */
    @Test
    fun `an anonymous caller still sees Access Denied verbatim`() {
        val error = resolve(AccessDeniedException("Access Denied"))

        assertEquals(
            "Access Denied",
            error?.message,
            "the calendar sync retries on this exact text; it must not become a generic message",
        )
    }

    @Test
    fun `an unexpected error is generic and every reference is distinct`() {
        val sqlText = "could not execute statement [ERROR: duplicate key value violates unique constraint]"
        val first = resolve(IllegalStateException(sqlText))
        val second = resolve(IllegalStateException(sqlText))

        assertFalse(
            first?.message?.contains("duplicate key") == true,
            "SQL text must not be echoed: ${first?.message}",
        )
        assertTrue(first?.message?.startsWith("Something went wrong") == true, "expected the generic message")
        assertFalse(
            first?.message == second?.message,
            "each occurrence needs its own reference, or the log cannot be joined to a report",
        )
    }

    // ---- client-supplied error ref (X-CTA-Ref, #371) ----

    private fun <T> capturingResolverLog(block: () -> T): Pair<T, List<String>> {
        val resolverLogger = LoggerFactory.getLogger(CustomErrorHandlerConfig::class.java.name) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        resolverLogger.addAppender(appender)
        try {
            return block() to appender.list.map { it.formattedMessage }
        } finally {
            resolverLogger.detachAppender(appender)
            appender.stop()
        }
    }

    private fun quotedReference(message: String): String? = Regex("quoting reference ([^.]+)\\.").find(message)?.groupValues?.get(1)

    @Test
    fun `a valid client ref replaces the server reference so the request has one ref`() {
        val (content, logLines) = capturingResolverLog { createRequestAgainstMissingContact(ref = "K7Q2M9XA") }

        val message: String = JsonPath.read(content, "$.errors[0].message")
        assertEquals("K7Q2M9XA", quotedReference(message), "the displayed reference must be the client ref: $content")
        assertEquals("K7Q2M9XA", JsonPath.read<String>(content, "$.errors[0].extensions.ref"), content)
        assertTrue(
            logLines.any { it.contains("[ref K7Q2M9XA]") },
            "the logged reference must be the same value the caller was shown: $logLines",
        )
    }

    @Test
    fun `without a valid client ref the server reference is used, logged and displayed alike`() {
        for (ref in listOf(null, "not valid!", "A".repeat(33))) {
            val (content, logLines) = capturingResolverLog { createRequestAgainstMissingContact(ref = ref) }

            val message: String = JsonPath.read(content, "$.errors[0].message")
            val shown = quotedReference(message)
            assertTrue(shown != null && Regex("^[0-9a-f]{8}$").matches(shown), "expected the 8-char server ref: $message")
            assertTrue(logLines.any { it.contains("[ref $shown]") }, "logged and displayed refs must match: $logLines")
            assertFalse(content.contains("\"ref\""), "no valid client ref, so no ref extension: $content")
            if (ref != null) assertFalse(content.contains(ref), "an invalid ref must never be echoed: $content")
        }
    }

    @Test
    fun `the resolver takes the ref from the GraphQL context`() {
        val error = resolve(IllegalStateException("boom"), GraphQLContext.of(mapOf(CLIENT_ERROR_REF_CONTEXT_KEY to "AB12CD34")))

        assertEquals(CustomErrorHandlerConfig.GENERIC_MESSAGE.format("AB12CD34"), error?.message)
    }

    /**
     * resolveToSingleError is protected, so drive the resolver through the public
     * DataFetcherExceptionResolver entry point the framework itself calls.
     */
    private fun resolve(
        ex: Throwable,
        context: GraphQLContext? = null,
    ): GraphQLError? = CustomErrorHandlerConfig().resolveException(ex, environment(context)).block()?.firstOrNull()

    private fun environment(context: GraphQLContext? = null): DataFetchingEnvironment {
        val stepInfo = Mockito.mock(ExecutionStepInfo::class.java)
        Mockito.`when`(stepInfo.path).thenReturn(ResultPath.rootPath())
        val env = Mockito.mock(DataFetchingEnvironment::class.java)
        Mockito.`when`(env.executionStepInfo).thenReturn(stepInfo)
        Mockito
            .`when`(env.field)
            .thenReturn(Field.newField("anything").sourceLocation(SourceLocation(1, 1)).build())
        Mockito.`when`(env.graphQlContext).thenReturn(context)
        return env
    }
}
