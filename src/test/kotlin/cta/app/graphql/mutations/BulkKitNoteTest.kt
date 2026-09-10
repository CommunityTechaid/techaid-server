package cta.app.graphql.mutations

import cta.app.Kit
import cta.app.KitRepository
import cta.app.KitStatus
import cta.app.KitType
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

/**
 * `updateKits` can carry a note, which is appended to every kit in the batch as
 * its own Note row — the same shape `updateKit` produces, not a shared one.
 *
 * This exists for the dashboard's Update Scanner, where an operator types one
 * bench note and it is stamped onto each device they scan. Before this the bulk
 * input had no note field at all, and the only way to add one was `updateKit`,
 * which resets `subStatus` and `typeOfStorage` from its input defaults — so a
 * note-only call there would silently clear a kit's blocking flags.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class BulkKitNoteTest {
    @MockBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var kits: KitRepository

    private fun graphQl(query: String): String =
        mockMvc
            .perform(
                post("/graphql")
                    .with(
                        jwt().authorities(
                            SimpleGrantedAuthority("write:kits"),
                            SimpleGrantedAuthority("read:kits"),
                        ),
                    ).contentType(MediaType.APPLICATION_JSON)
                    .content("""{"query":"$query"}"""),
            ).andReturn()
            .response
            .contentAsString

    private fun kit(model: String): Kit =
        kits.save(
            Kit(
                type = KitType.LAPTOP,
                status = KitStatus.PROCESSING_START,
                model = model,
                age = 0,
            ),
        )

    private fun notesOf(id: Long): String = graphQl("""query { kit(where: { id: { _eq: $id } }) { notes { content } } }""")

    @Test
    fun `a bulk note is appended to every kit in the batch`() {
        val first = kit("Note Probe A")
        val second = kit("Note Probe B")

        val response =
            graphQl(
                """mutation { updateKits(data: { ids: [${first.id}, ${second.id}], """ +
                    """status: PROCESSING_OS_INSTALLED, note: { content: \"Batch 42 bench check\" } }) { id status } }""",
            )

        assertFalse(response.contains("errors"), response)
        assertEquals(KitStatus.PROCESSING_OS_INSTALLED, kits.findById(first.id).orElseThrow().status)

        // Each kit gets its OWN note row, not a shared one.
        listOf(first, second).forEach {
            val notes = notesOf(it.id)
            assertEquals(1, Regex("\"content\"").findAll(notes).count(), "kit ${it.id} should have exactly one note: $notes")
            assertEquals(true, notes.contains("Batch 42 bench check"), "kit ${it.id} note content missing: $notes")
        }
    }

    @Test
    fun `omitting the note leaves the kit without one`() {
        val kit = kit("Note Probe C")

        val response =
            graphQl(
                """mutation { updateKits(data: { ids: [${kit.id}], status: PROCESSING_OS_INSTALLED }) { id status } }""",
            )

        assertFalse(response.contains("errors"), response)
        assertEquals(false, notesOf(kit.id).contains("\"content\""), "no note should have been created")
    }

    @Test
    fun `a blank note creates nothing rather than an empty note on every kit`() {
        val kit = kit("Note Probe D")

        val response =
            graphQl(
                """mutation { updateKits(data: { ids: [${kit.id}], status: PROCESSING_OS_INSTALLED, """ +
                    """note: { content: \"   \" } }) { id status } }""",
            )

        assertFalse(response.contains("errors"), response)
        assertEquals(false, notesOf(kit.id).contains("\"content\""), "whitespace is not a note")
    }

    @Test
    fun `a note can be added without changing the status`() {
        val kit = kit("Note Probe E")

        val response =
            graphQl(
                """mutation { updateKits(data: { ids: [${kit.id}], note: { content: \"Left on the shelf\" } }) { id status } }""",
            )

        assertFalse(response.contains("errors"), response)
        assertEquals(KitStatus.PROCESSING_START, kits.findById(kit.id).orElseThrow().status, "status must be untouched")
        assertEquals(true, notesOf(kit.id).contains("Left on the shelf"), "note should still be recorded")
    }
}
