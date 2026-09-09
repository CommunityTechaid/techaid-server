package cta.graphql

import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

/**
 * Pins GraphQL introspection CLOSED for anonymous callers.
 *
 * Production answered `{ __schema { queryType { name } } }` to an unauthenticated caller until
 * this was fixed. The config carried `spring.graphql.schema.inspection.enabled: false`, which is
 * the startup schema-mapping *report* — an unrelated diagnostic. The property that closes
 * introspection is `spring.graphql.schema.introspection.enabled`; the two are one letter apart
 * and nothing caught it.
 *
 * These requests carry no credentials, exactly like the live probe did. Introspection in
 * graphql-java is a JVM-wide switch set from `spring.graphql.schema.introspection.enabled`, so
 * this test is really asserting on the shipped config — the test profile deliberately does not
 * override it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class GraphQlIntrospectionDisabledTest {
    @MockBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var mockMvc: MockMvc

    private fun graphQl(query: String): String =
        mockMvc
            .perform(
                post("/graphql")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .content("""{"query":${'"'}$query${'"'}}"""),
            ).andReturn()
            .response.contentAsString

    @Test
    fun `anonymous __schema introspection is rejected`() {
        val content = graphQl("{ __schema { queryType { name } } }")

        assertTrue(
            content.contains("\"errors\""),
            "anonymous __schema query must be rejected, not answered: $content",
        )
        assertFalse(
            content.contains("\"queryType\""),
            "no part of the schema may be returned to an anonymous caller: $content",
        )
    }

    @Test
    fun `anonymous __type field enumeration is rejected`() {
        val content = graphQl("{ __type(name: \\\"Kit\\\") { fields { name } } }")

        assertTrue(
            content.contains("\"errors\""),
            "anonymous __type query must be rejected, not answered: $content",
        )
        assertFalse(
            content.contains("\"fields\""),
            "Kit's fields may not be enumerated by an anonymous caller: $content",
        )
    }
}
