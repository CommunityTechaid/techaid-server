package cta.db

import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.bean.override.mockito.MockitoBean

/**
 * gdpr.performgdprcleanup() deletes the notes of every erased referee contact (#374).
 *
 * The cleanup blanks a contact's name, email, phone and address, but before #374 it never
 * touched referring_organisation_contacts_notes, so free-text staff notes about an erased
 * referee were kept forever. Owner decisions, 2026-11-13: the notes are DELETED, not blanked,
 * and contacts erased by earlier runs are covered too.
 *
 * The deletion is scoped ONLY by the contact's own erased marker - the full_name the cleanup
 * writes, the same predicate the audit-trail scrub and the #372 read-only guard use. Never by
 * a timestamp, the organisation, or the archived flag: the 2026-08-12 incident erased 920
 * referees because a rule keyed on a timestamp that did not mean what it seemed to
 * (GdprRefereeRetentionScopeTest). The survival tests below matter as much as the deletion
 * tests: they pin that nothing outside that predicate is removed.
 *
 * Fixtures are committed (no rollback), so every id here is unique to this class.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class GdprErasedRefereeNotesTest {
    @MockitoBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    private companion object {
        const val ERASED = "Contact - Erased due to GDPR policy"

        // Out of retention: a contact this old with no requests is erased by the run.
        const val STALE = "18 months"

        // Well inside retention, and inside the 52-week device-request-notes threshold.
        const val RECENT = "3 days"
    }

    private fun insertOrganisation(id: Long) {
        jdbcTemplate.update(
            """
            INSERT INTO referring_organisations (id, name, archived, created_at, updated_at)
            VALUES ($id, 'Referring Org $id', 'N', now() - interval '$RECENT', now() - interval '$RECENT')
            """.trimIndent(),
        )
    }

    private fun insertContact(
        id: Long,
        organisationId: Long,
        age: String,
        fullName: String = "Referee $id",
        archived: Boolean = false,
    ) {
        val archivedFlag = if (archived) "Y" else "N"
        jdbcTemplate.update(
            """
            INSERT INTO referring_organisation_contacts (id, full_name, email, phone_number, address,
                                                         archived, referring_organisation_id,
                                                         created_at, updated_at)
            VALUES ($id, ?, 'referee$id@example.org', '07700900000', '1 Test Street',
                    '$archivedFlag', $organisationId, now() - interval '$age', now() - interval '$age')
            """.trimIndent(),
            fullName,
        )
    }

    /** A contact that an EARLIER run erased: the marker is already there, the other fields blank. */
    private fun insertAlreadyErasedContact(
        id: Long,
        organisationId: Long,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO referring_organisation_contacts (id, full_name, email, phone_number, address,
                                                         archived, referring_organisation_id,
                                                         created_at, updated_at)
            VALUES ($id, '$ERASED', '', '', '', 'N', $organisationId,
                    now() - interval '$RECENT', now() - interval '$RECENT')
            """.trimIndent(),
        )
    }

    private fun insertContactNote(
        id: Long,
        contactId: Long,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO referring_organisation_contacts_notes (id, referring_organisation_contact_id, content,
                                                               volunteer, created_at, updated_at)
            VALUES ($id, $contactId, 'Note $id about referee $contactId', 'volunteer@example.org',
                    now() - interval '$RECENT', now() - interval '$RECENT')
            """.trimIndent(),
        )
    }

    private fun contactNoteCount(contactId: Long): Int =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM referring_organisation_contacts_notes WHERE referring_organisation_contact_id = $contactId",
            Int::class.java,
        )!!

    private fun fullNameOf(contactId: Long): String? =
        jdbcTemplate.queryForObject(
            "SELECT full_name FROM referring_organisation_contacts WHERE id = $contactId",
            String::class.java,
        )

    private fun runCleanup(): String = jdbcTemplate.queryForObject("SELECT gdpr.performgdprcleanup()", String::class.java)!!

    @Test
    fun `a contact erased by this run loses its notes`() {
        insertOrganisation(937400)
        insertContact(937401, organisationId = 937400, age = STALE)
        insertContactNote(937411, contactId = 937401)
        insertContactNote(937412, contactId = 937401)

        runCleanup()

        assertThat(fullNameOf(937401)).isEqualTo(ERASED)
        assertThat(contactNoteCount(937401))
            .`as`("a contact erased in this run must not keep its free-text notes")
            .isZero()
    }

    @Test
    fun `a contact erased by an earlier run loses its notes on the next run`() {
        insertOrganisation(937420)
        insertAlreadyErasedContact(937421, organisationId = 937420)
        insertContactNote(937431, contactId = 937421)
        insertContactNote(937432, contactId = 937421)
        insertContactNote(937433, contactId = 937421)

        runCleanup()

        assertThat(contactNoteCount(937421))
            .`as`("contacts already carrying the erased marker are covered too (owner decision 2)")
            .isZero()
        assertThat(fullNameOf(937421))
            .`as`("the contact row itself is kept; only its notes go")
            .isEqualTo(ERASED)
    }

    @Test
    fun `notes of contacts that are not erased survive, including archived ones and erased contacts' colleagues`() {
        insertOrganisation(937440)
        // Same organisation as an erased contact: organisation must not be the scope.
        insertAlreadyErasedContact(937441, organisationId = 937440)
        insertContactNote(937451, contactId = 937441)
        insertContact(937442, organisationId = 937440, age = RECENT)
        insertContactNote(937452, contactId = 937442)
        // Archived but not erased: archived must not be the scope.
        insertContact(937443, organisationId = 937440, age = RECENT, archived = true)
        insertContactNote(937453, contactId = 937443)
        insertContactNote(937454, contactId = 937443)
        // A stale contact row kept out of retention by a recent referral. Its own updated_at is
        // 18 months old, the exact shape over-erased on 2026-08-12: timestamps must not be the scope.
        insertContact(937444, organisationId = 937440, age = STALE)
        jdbcTemplate.update(
            """
            INSERT INTO device_requests (id, referring_organisation_contact_id, status, is_sales, is_prepped,
                                         created_at, updated_at)
            VALUES (937464, 937444, 'REQUEST_COMPLETED', false, false,
                    now() - interval '1 month', now() - interval '1 month')
            """.trimIndent(),
        )
        insertContactNote(937455, contactId = 937444)

        runCleanup()

        assertThat(contactNoteCount(937441)).`as`("the erased colleague's note goes").isZero()
        assertThat(contactNoteCount(937442)).`as`("same-organisation, not erased").isEqualTo(1)
        assertThat(contactNoteCount(937443)).`as`("archived, not erased").isEqualTo(2)
        assertThat(fullNameOf(937444)).`as`("actively referring, not erased").isEqualTo("Referee 937444")
        assertThat(contactNoteCount(937444)).`as`("stale contact row, recent referral").isEqualTo(1)
    }

    @Test
    fun `a second run is a no-op`() {
        insertOrganisation(937470)
        insertAlreadyErasedContact(937471, organisationId = 937470)
        insertContactNote(937481, contactId = 937471)
        insertContact(937472, organisationId = 937470, age = RECENT)
        insertContactNote(937482, contactId = 937472)

        runCleanup()
        val totalAfterFirst =
            jdbcTemplate.queryForObject("SELECT count(*) FROM referring_organisation_contacts_notes", Int::class.java)

        runCleanup()

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM referring_organisation_contacts_notes", Int::class.java))
            .`as`("nothing is left for a second run to delete")
            .isEqualTo(totalAfterFirst)
        assertThat(contactNoteCount(937471)).isZero()
        assertThat(contactNoteCount(937472)).isEqualTo(1)
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT referring_contact_notes_count FROM gdpr_cleanup_runs ORDER BY id DESC LIMIT 1",
                Int::class.java,
            ),
        ).`as`("the second run records zero notes deleted").isZero()
    }

    @Test
    fun `the run records how many referee notes it deleted`() {
        insertOrganisation(937490)
        insertAlreadyErasedContact(937491, organisationId = 937490)
        insertContactNote(937492, contactId = 937491)
        insertContactNote(937493, contactId = 937491)

        val summary = runCleanup()

        val recorded =
            jdbcTemplate.queryForObject(
                "SELECT referring_contact_notes_count FROM gdpr_cleanup_runs ORDER BY id DESC LIMIT 1",
                Int::class.java,
            )
        // >= rather than ==: fixtures from other test classes share this database and may also
        // carry the erased marker with notes.
        assertThat(recorded).isGreaterThanOrEqualTo(2)
        assertThat(summary).contains("$recorded referring contact notes")
    }

    @Test
    fun `other notes tables are untouched`() {
        insertOrganisation(937500)
        insertAlreadyErasedContact(937501, organisationId = 937500)
        insertContactNote(937511, contactId = 937501)
        // A request raised by the erased contact, with a recent note.
        jdbcTemplate.update(
            """
            INSERT INTO device_requests (id, referring_organisation_contact_id, status, is_sales, is_prepped,
                                         created_at, updated_at)
            VALUES (937521, 937501, 'REQUEST_COMPLETED', false, false,
                    now() - interval '$RECENT', now() - interval '$RECENT')
            """.trimIndent(),
        )
        jdbcTemplate.update(
            """
            INSERT INTO device_requests_notes (id, device_request_id, content, volunteer, created_at, updated_at)
            VALUES (937531, 937521, 'Request note 937531', 'v', now() - interval '$RECENT', now() - interval '$RECENT')
            """.trimIndent(),
        )
        // A note on the erased contact's organisation.
        jdbcTemplate.update(
            """
            INSERT INTO referring_organisations_notes (id, referring_organisation_id, content, volunteer, created_at, updated_at)
            VALUES (937541, 937500, 'Organisation note 937541', 'v', now() - interval '$RECENT', now() - interval '$RECENT')
            """.trimIndent(),
        )
        // A kit note (kit_id is nullable; no kit is needed to prove the table is not touched).
        jdbcTemplate.update(
            """
            INSERT INTO note (id, kit_id, content, volunteer, created_at, updated_at)
            VALUES (937551, NULL, 'Kit note 937551', 'v', now() - interval '$RECENT', now() - interval '$RECENT')
            """.trimIndent(),
        )

        runCleanup()

        assertThat(contactNoteCount(937501)).isZero()
        assertThat(jdbcTemplate.queryForObject("SELECT content FROM device_requests_notes WHERE id = 937531", String::class.java))
            .isEqualTo("Request note 937531")
        assertThat(
            jdbcTemplate.queryForObject("SELECT content FROM referring_organisations_notes WHERE id = 937541", String::class.java),
        ).isEqualTo("Organisation note 937541")
        assertThat(jdbcTemplate.queryForObject("SELECT content FROM note WHERE id = 937551", String::class.java))
            .isEqualTo("Kit note 937551")
    }
}
