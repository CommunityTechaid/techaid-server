package cta.app.graphql.mutations

import cta.app.Kit
import cta.app.KitRepository
import cta.app.KitStatus
import cta.app.KitType
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

/**
 * Kit `attributes` is retired from the API (#151, #222). The inputs were silently discarded:
 * updateKit self-assigned the field and createKit had the line commented out. The search
 * filter was bypassed in 2024. The dashboard never displayed the values. So the schema no
 * longer advertises any of it, and a client that still sends it gets a validation error
 * instead of a success that drops its data.
 *
 * The `kits.attributes` column and the entity property stay. They hold legacy data (about 19,600
 * rows in prod) that nothing else records, and dropping that is a separate decision.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class KitAttributesRetiredTest {
    @MockitoBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var kits: KitRepository

    private fun graphQl(query: String): String =
        mockMvc
            .perform(
                post("/graphql")
                    .with(jwt().authorities(SimpleGrantedAuthority("read:kits"), SimpleGrantedAuthority("write:kits")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"query":"$query"}"""),
            ).andReturn()
            .response
            .contentAsString

    private fun kit(): Kit = kits.save(Kit(type = KitType.LAPTOP, status = KitStatus.PROCESSING_START, model = "Attr Probe", age = 0))

    private fun assertValidationError(response: String) {
        assertTrue(response.contains("ValidationError"), "expected a schema validation error: $response")
    }

    @Test
    fun `Kit no longer exposes attributes`() {
        val kit = kit()
        assertValidationError(graphQl("""query { kit(where: { id: { _eq: ${kit.id} } }) { id attributes { notes } } }"""))
    }

    @Test
    fun `kit search no longer accepts an attributes filter`() {
        assertValidationError(
            graphQl(
                """query { kitsConnection(where: { attributes: { filters: [{ key: \"notes\", _text: { _contains: \"x\" } }] } }) """ +
                    """{ totalElements } }""",
            ),
        )
    }

    @Test
    fun `updateKit rejects attributes instead of silently dropping them`() {
        val kit = kit()
        assertValidationError(
            graphQl(
                """mutation { updateKit(data: { id: ${kit.id}, type: LAPTOP, status: PROCESSING_START, model: \"Attr Probe\", """ +
                    """attributes: { notes: \"lost\" } }) { id } }""",
            ),
        )
    }

    @Test
    fun `updateKit without attributes still works`() {
        val kit = kit()
        val response =
            graphQl(
                """mutation { updateKit(data: { id: ${kit.id}, type: LAPTOP, status: PROCESSING_START, model: \"Renamed\" }) { id model } }""",
            )
        assertFalse(response.contains("errors"), response)
        assertEquals("Renamed", kits.findById(kit.id).orElseThrow().model)
    }

    /** CreateKitInput.attributes was a non-null Kotlin parameter, so omitting it failed binding. */
    @Test
    fun `createKit works without attributes`() {
        val response = graphQl("""mutation { createKit(data: { type: LAPTOP, model: \"Created\", age: 0 }) { id model } }""")
        assertFalse(response.contains("errors"), response)
        assertTrue(response.contains("\"model\":\"Created\""), response)
    }
}
