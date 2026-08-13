package cta.db

import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtDecoder

/**
 * Pins referring-contact retention to ACTIVITY rather than to the contact row's own timestamp.
 *
 * This test exists because of a bug it would have caught, and the bug reached production.
 *
 * V26.08.11.1400 introduced referee retention as:
 *
 *     WHERE c.updated_at <= (CURRENT_DATE - '12 months'::interval)
 *
 * referring_organisation_contacts.updated_at only moves when somebody edits the CONTACT
 * RECORD. Referring activity lands on device_requests, a child table, and never touches the
 * parent row. A referee who has been referring continuously for years, but whose name, email
 * and phone have not been retyped since 2024, therefore looks a year stale on every run.
 *
 * The run that committed at 2026-08-12 16:54:22 UTC erased 920 contacts in production on that
 * basis. 17 were active; 5 of those had device requests sitting in
 * PROCESSING_COLLECTION_DELIVERY_ARRANGED, i.e. deliveries in flight to people whose referrer
 * had just become uncontactable. UAT was hit the night before: 1,358 of 1,366, 13 of them
 * active. Both were restored from point-in-time backups, which were the only surviving copy —
 * the audit trail is scrubbed in the same transaction, so it could not be used for recovery.
 *
 * V26.08.13.1200 moves the predicate into gdpr.referring_contacts_to_archive, which takes
 * GREATEST(contact.updated_at, max(device request activity)) — the same shape
 * gdpr.donors_to_archive already used for donors, and which was right all along.
 *
 * The first test below is the regression. The remaining three assert that the fix only ever
 * NARROWS erasure: a contact with no recent activity is still caught, whether it has stale
 * requests or none at all. That direction matters as much as the fix — a retention rule that
 * quietly stops erasing is a GDPR failure in the opposite direction, and would be much harder
 * to notice than 920 blanked rows.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class GdprRefereeRetentionScopeTest {
    @MockBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    private companion object {
        const val ERASED = "Contact - Erased due to GDPR policy"

        // Stale enough that the OLD, broken predicate would always have caught these rows.
        const val STALE = "18 months"
    }

    private fun insertOrganisation(id: Long) {
        jdbcTemplate.update(
            """
            INSERT INTO referring_organisations (id, name, archived, created_at, updated_at)
            VALUES ($id, 'Referring Org $id', 'N', now() - interval '$STALE', now() - interval '$STALE')
            """.trimIndent(),
        )
    }

    /** A contact whose own row has not been touched for [age] - exactly what the old rule keyed on. */
    private fun insertContact(
        id: Long,
        organisationId: Long,
        age: String = STALE,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO referring_organisation_contacts (id, full_name, email, phone_number, address,
                                                         archived, referring_organisation_id,
                                                         created_at, updated_at)
            VALUES ($id, 'Active Referee $id', 'referee$id@example.org', '07700900000', '1 Test Street',
                    'N', $organisationId, now() - interval '$age', now() - interval '$age')
            """.trimIndent(),
        )
    }

    private fun insertDeviceRequest(
        id: Long,
        contactId: Long,
        age: String,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO device_requests (id, referring_organisation_contact_id, status,
                                         is_sales, is_prepped, created_at, updated_at)
            VALUES ($id, $contactId, 'REQUEST_COMPLETED', false, false,
                    now() - interval '$age', now() - interval '$age')
            """.trimIndent(),
        )
    }

    private fun isSelectedForErasure(contactId: Long): Boolean =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM gdpr.referring_contacts_to_archive WHERE id = $contactId",
            Int::class.java,
        ) == 1

    private fun fullNameOf(contactId: Long): String? =
        jdbcTemplate.queryForObject(
            "SELECT full_name FROM referring_organisation_contacts WHERE id = $contactId",
            String::class.java,
        )

    @Test
    fun `a referee with a stale contact row but recent referrals is spared`() {
        insertOrganisation(900500)
        insertContact(900501, organisationId = 900500)
        // The contact row is 18 months old, but this referee raised a request last month.
        insertDeviceRequest(900511, contactId = 900501, age = "1 month")

        assertThat(isSelectedForErasure(900501))
            .`as`("activity on a child device_request must keep the referee out of retention")
            .isFalse()

        jdbcTemplate.queryForObject("SELECT gdpr.performgdprcleanup()", String::class.java)

        // The load-bearing assertion: this is the exact row shape that was erased in production.
        assertThat(fullNameOf(900501))
            .`as`("an actively-referring contact must survive the retention routine intact")
            .isEqualTo("Active Referee 900501")
    }

    @Test
    fun `a referee whose most recent referral is itself out of retention is still erased`() {
        insertOrganisation(900520)
        insertContact(900521, organisationId = 900520)
        insertDeviceRequest(900531, contactId = 900521, age = "20 months")

        assertThat(isSelectedForErasure(900521))
            .`as`("stale contact plus stale referrals means genuinely out of retention")
            .isTrue()

        jdbcTemplate.queryForObject("SELECT gdpr.performgdprcleanup()", String::class.java)

        assertThat(fullNameOf(900521)).isEqualTo(ERASED)
    }

    @Test
    fun `a referee who never referred anyone is still erased on the contact row alone`() {
        insertOrganisation(900540)
        insertContact(900541, organisationId = 900540)

        assertThat(isSelectedForErasure(900541))
            .`as`("with no device requests the contact's own timestamp is the only signal there is")
            .isTrue()

        jdbcTemplate.queryForObject("SELECT gdpr.performgdprcleanup()", String::class.java)

        assertThat(fullNameOf(900541)).isEqualTo(ERASED)
    }

    @Test
    fun `a newly created referee who has not referred anyone yet is spared`() {
        insertOrganisation(900560)
        insertContact(900561, organisationId = 900560, age = "3 days")

        // created_at is the floor in the view, so a brand-new contact is never caught before it
        // has had any chance to raise a request.
        assertThat(isSelectedForErasure(900561)).isFalse()

        jdbcTemplate.queryForObject("SELECT gdpr.performgdprcleanup()", String::class.java)

        assertThat(fullNameOf(900561)).isEqualTo("Active Referee 900561")
    }
}
