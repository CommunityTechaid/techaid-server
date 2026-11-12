package cta.app.graphql.mutations

import cta.app.QReferringOrganisationContactNote
import cta.app.ReferringOrganisation
import cta.app.ReferringOrganisationContact
import cta.app.ReferringOrganisationContactNoteRepository
import cta.app.ReferringOrganisationContactRepository
import cta.app.ReferringOrganisationRepository
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

/**
 * gdpr.performgdprcleanup() erases a stale referee by renaming it to the erased marker and
 * blanking email, phone and address, and from then on skips the row BY THAT NAME. Anything typed
 * into an erased referee afterwards would therefore never be scrubbed. Erased referees are
 * read-only except for archiving, and archiving must work with the payload the dashboard's
 * detail form actually sends: the stored values echoed back, including email "".
 *
 * Moving the email rule off the input annotation (so it can skip erased rows) must leave live
 * referees exactly as before: a blank email is still rejected with the same message.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class ErasedRefereeReadOnlyTest {
    @MockitoBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var referringOrganisations: ReferringOrganisationRepository

    @Autowired
    lateinit var referringOrganisationContacts: ReferringOrganisationContactRepository

    @Autowired
    lateinit var referringOrganisationContactNotes: ReferringOrganisationContactNoteRepository

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    private fun graphQl(
        query: String,
        variables: String,
    ): String =
        mockMvc
            .perform(
                post("/graphql")
                    .with(jwt().authorities(SimpleGrantedAuthority("write:referees")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"query":"$query","variables":$variables}"""),
            ).andReturn()
            .response
            .contentAsString

    private fun org(name: String = "Erased Referee Org") = referringOrganisations.save(ReferringOrganisation(name = name))

    private fun erased(
        org: ReferringOrganisation,
        archived: Boolean = false,
    ) = referringOrganisationContacts.save(
        ReferringOrganisationContact(
            fullName = ERASED,
            email = "",
            phoneNumber = "",
            address = "",
            archived = archived,
            referringOrganisation = org,
        ),
    )

    private fun live(org: ReferringOrganisation) =
        referringOrganisationContacts.save(
            ReferringOrganisationContact(
                fullName = "Live Referee",
                email = "live.referee@example.org",
                phoneNumber = "02034887742",
                address = "1 Test Street",
                referringOrganisation = org,
            ),
        )

    /** Mirrors the detail form: every field sent, stored values echoed back unless edited. */
    private fun update(
        contact: ReferringOrganisationContact,
        fullName: String = contact.fullName,
        email: String = contact.email,
        phoneNumber: String = contact.phoneNumber,
        address: String = contact.address,
        orgId: Long = contact.referringOrganisation.id!!,
        archived: Boolean = contact.archived,
        isMembershipContact: Boolean = contact.isMembershipContact,
        note: String = "",
    ): String =
        graphQl(
            "mutation(\$data: UpdateReferringOrganisationContactInput!) " +
                "{ updateReferringOrganisationContact(data: \$data) { id archived } }",
            """
            {"data": {"id": ${contact.id}, "fullName": "$fullName", "email": "$email",
              "phoneNumber": "$phoneNumber", "address": "$address", "referringOrganisationId": $orgId,
              "archived": $archived, "isMembershipContact": $isMembershipContact, "note": {"content": "$note"}}}
            """.trimIndent(),
        )

    private fun reload(contact: ReferringOrganisationContact) = referringOrganisationContacts.findById(contact.id).get()

    private fun assertSaved(body: String) {
        assertFalse(body.contains("\"errors\""), body)
        assertTrue(body.contains("\"updateReferringOrganisationContact\":{"), body)
    }

    private fun assertErasedRejection(body: String) {
        assertTrue(body.contains("\"errors\""), body)
        assertFalse(body.contains("\"updateReferringOrganisationContact\":{"), body)
        assertTrue(body.contains("erased under the GDPR retention policy"), body)
        assertTrue(body.contains(RefereeErasedException.CODE), body)
    }

    private fun assertStillErased(
        contact: ReferringOrganisationContact,
        archived: Boolean = false,
    ) {
        val stored = reload(contact)
        assertEquals(ERASED, stored.fullName)
        assertEquals("", stored.email)
        assertEquals("", stored.phoneNumber)
        assertEquals("", stored.address)
        assertEquals(archived, stored.archived)
        assertEquals(contact.referringOrganisation.id, stored.referringOrganisation.id)
        assertFalse(stored.isMembershipContact)
    }

    @Test
    fun `the guard's marker is the name the deployed cleanup function writes`() {
        val body =
            jdbcTemplate.queryForObject(
                """
                SELECT p.prosrc
                  FROM pg_proc p
                  JOIN pg_namespace n ON n.oid = p.pronamespace
                 WHERE n.nspname = 'gdpr' AND p.proname = 'performgdprcleanup'
                """.trimIndent(),
                String::class.java,
            ) ?: error("gdpr.performgdprcleanup() does not exist in the test database")

        assertTrue(body.contains("SET full_name = '$ERASED'"), "cleanup and guard must agree on the erased marker")
    }

    @Test
    fun `an erased referee can be archived with the payload the dashboard sends`() {
        val contact = erased(org())

        assertSaved(update(contact, archived = true))
        assertStillErased(contact, archived = true)
    }

    @Test
    fun `an erased referee can be un-archived`() {
        val contact = erased(org(), archived = true)

        assertSaved(update(contact, archived = false))
        assertStillErased(contact, archived = false)
    }

    @Test
    fun `adding a phone number to an erased referee is rejected`() {
        val contact = erased(org())

        assertErasedRejection(update(contact, phoneNumber = "02034887742", archived = true))
        assertStillErased(contact)
    }

    @Test
    fun `adding an email to an erased referee is rejected`() {
        val contact = erased(org())

        assertErasedRejection(update(contact, email = "someone@example.org"))
        assertStillErased(contact)
    }

    @Test
    fun `adding an address or renaming an erased referee is rejected`() {
        val contact = erased(org())

        assertErasedRejection(update(contact, address = "2 Other Street"))
        assertErasedRejection(update(contact, fullName = "A Real Name"))
        assertStillErased(contact)
    }

    @Test
    fun `adding a note to an erased referee is rejected`() {
        val contact = erased(org())

        assertErasedRejection(update(contact, note = "Spoke to them on the phone"))
        val notes = QReferringOrganisationContactNote.referringOrganisationContactNote
        assertEquals(0L, referringOrganisationContactNotes.count(notes.referringOrganisationContact.id.eq(contact.id)))
    }

    @Test
    fun `moving an erased referee or flagging it as a membership contact is rejected`() {
        val contact = erased(org())
        val other = org("Another Org")

        assertErasedRejection(update(contact, orgId = other.id!!))
        assertErasedRejection(update(contact, isMembershipContact = true))
        assertStillErased(contact)
    }

    @Test
    fun `a live referee with a blank email is still rejected with the same message`() {
        val contact = live(org())

        val body = update(contact, email = "")
        assertTrue(body.contains("\"errors\""), body)
        assertTrue(body.contains("updateReferringOrganisationContact.data.email: must not be blank"), body)
        assertFalse(body.contains(RefereeErasedException.CODE), body)
        assertEquals("live.referee@example.org", reload(contact).email)
    }

    @Test
    fun `a live referee with a whitespace-only email is still rejected`() {
        val contact = live(org())

        val body = update(contact, email = "   ")
        assertTrue(body.contains("\"errors\""), body)
        assertEquals("live.referee@example.org", reload(contact).email)
    }

    @Test
    fun `a live referee with a malformed email is still rejected`() {
        val contact = live(org())

        val body = update(contact, email = "not-an-email")
        assertTrue(body.contains("well-formed email"), body)
        assertEquals("live.referee@example.org", reload(contact).email)
    }

    @Test
    fun `a live referee can still be edited normally`() {
        val contact = live(org())

        assertSaved(update(contact, email = "new.referee@example.org", phoneNumber = "07000000000", archived = true))
        val stored = reload(contact)
        assertEquals("new.referee@example.org", stored.email)
        assertEquals("07000000000", stored.phoneNumber)
        assertTrue(stored.archived)
    }

    companion object {
        private const val ERASED = ReferringOrganisationContact.GDPR_ERASED_NAME
    }
}
