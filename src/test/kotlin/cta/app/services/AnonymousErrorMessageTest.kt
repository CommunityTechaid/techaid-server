package cta.app.services

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
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
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

    private fun graphQl(body: String): String =
        mockMvc
            .perform(
                post("/graphql")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andReturn()
            .response.contentAsString

    /**
     * createReferringOrganisationContact is anonymous, and an unknown organisation id makes it
     * throw EntityNotFoundException - which no controller handles, so it lands on the fallback.
     */
    private fun createContactAgainstMissingOrg(email: String): String {
        val document =
            "mutation { createReferringOrganisationContact(data: {" +
                "fullName: \\\"Referee\\\", email: \\\"$email\\\", phoneNumber: \\\"02034887742\\\", " +
                "address: \\\"1 Test Street\\\", referringOrganisation: 999999}) { id } }"
        return graphQl("""{"query":"$document"}""")
    }

    @Test
    fun `an anonymous caller gets a generic message, not the exception text`() {
        val content = createContactAgainstMissingOrg("referee@example.org")

        assertFalse(
            content.contains("No referring organisation was found"),
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
        val content = createContactAgainstMissingOrg("not-an-email")

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

    /**
     * resolveToSingleError is protected, so drive the resolver through the public
     * DataFetcherExceptionResolver entry point the framework itself calls.
     */
    private fun resolve(ex: Throwable): GraphQLError? =
        CustomErrorHandlerConfig().resolveException(ex, environment()).block()?.firstOrNull()

    private fun environment(): DataFetchingEnvironment {
        val stepInfo = Mockito.mock(ExecutionStepInfo::class.java)
        Mockito.`when`(stepInfo.path).thenReturn(ResultPath.rootPath())
        val env = Mockito.mock(DataFetchingEnvironment::class.java)
        Mockito.`when`(env.executionStepInfo).thenReturn(stepInfo)
        Mockito
            .`when`(env.field)
            .thenReturn(Field.newField("anything").sourceLocation(SourceLocation(1, 1)).build())
        return env
    }
}
