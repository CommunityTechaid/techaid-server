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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class GraphQlEndpointTest {
    @MockBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `graphql endpoint is accessible and schema is loaded`() {
        mockMvc
            .perform(
                post("/graphql")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"query":"{ __typename }"}"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.__typename").value("Query"))
    }

    @Test
    fun `numeric value in a LenientString input field is coerced, not rejected`() {
        // lotId is LenientString; sending it as a number must NOT raise a String-coercion error.
        // model (String!) is omitted on purpose so the request still fails validation and creates
        // nothing — making this a safe probe that proves the lenient scalar is wired into the schema.
        val body =
            """
            {
              "query": "mutation createKit(${'$'}data: CreateKitInput!) { createKit(data: ${'$'}data) { id } }",
              "variables": { "data": { "type": "LAPTOP", "lotId": 26060301 } }
            }
            """.trimIndent()

        val content =
            mockMvc
                .perform(
                    post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(body),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString

        assertTrue(
            content.contains("\"errors\""),
            "request should still fail on the missing required model (creating nothing): $content",
        )
        assertFalse(
            content.contains("Expected a String input"),
            "numeric lotId must be coerced by LenientString, not rejected: $content",
        )
    }
}
