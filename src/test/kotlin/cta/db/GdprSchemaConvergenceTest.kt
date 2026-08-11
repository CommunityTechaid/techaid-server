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
 * Pins the gdpr schema to the shape techaid_uat and techaid_prod actually have.
 *
 * This test exists because of a bug it would have caught. gdpr.archive_donor_info() — a
 * BEFORE DELETE trigger on donors — selected from gdpr.donors_to_delete, a view that had been
 * renamed to gdpr.donors_to_archive by hand in both live databases and committed nowhere. Every
 * donor delete therefore failed in UAT and production, for every role including techaid_admin,
 * and deleteDonor was broken for an unknown length of time.
 *
 * Nothing caught it because the zonky test database is built from the migrations in this repo,
 * where donors_to_delete DOES exist — so donor deletion passed in CI and failed everywhere real.
 * V26.07.21.2130__converge_gdpr_schema_with_live.sql closes that gap; these tests assert it
 * stays closed.
 *
 * Which test catches what, precisely — because it is easy to overclaim here:
 *
 *  - The two catalog assertions are the discriminating ones. Remove the convergence migration
 *    and they go red, because a fresh database reverts to donors_to_delete.
 *  - The end-to-end delete test does NOT catch the original bug, and passes with or without
 *    the migration. In a fresh database donors_to_delete exists, so the old trigger resolves
 *    happily; the failure only ever appeared in the live databases where the view had been
 *    renamed. That is the whole reason this went unnoticed. It is kept because it guards the
 *    converged schema against a FUTURE change breaking deletion, not because it would have
 *    caught this one.
 *
 * The general lesson stands: no test built from this repo's migrations can detect hand-applied
 * DDL on a live database. Only a live-schema check can do that.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class GdprSchemaConvergenceTest {
    @MockBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `the live retention view name exists and the stale one is gone`() {
        assertThat(jdbcTemplate.queryForObject("SELECT to_regclass('gdpr.donors_to_archive')::text", String::class.java))
            .`as`("gdpr.donors_to_archive is the name both live databases use")
            .isEqualTo("gdpr.donors_to_archive")

        assertThat(jdbcTemplate.queryForObject("SELECT to_regclass('gdpr.donors_to_delete')::text", String::class.java))
            .`as`("the stale name must not linger, or new code will be written against it again")
            .isNull()
    }

    @Test
    fun `archive_donor_info targets the live view and is a pinned SECURITY DEFINER`() {
        val definition =
            jdbcTemplate.queryForObject(
                """
                SELECT pg_get_functiondef(p.oid) FROM pg_proc p
                JOIN pg_namespace n ON n.oid = p.pronamespace
                WHERE n.nspname = 'gdpr' AND p.proname = 'archive_donor_info'
                """.trimIndent(),
                String::class.java,
            )
        assertThat(definition).contains("donors_to_archive")
        assertThat(definition)
            .`as`("referencing the renamed-away view is exactly what broke donor deletion")
            .doesNotContain("donors_to_delete")

        val security =
            jdbcTemplate.queryForMap(
                """
                SELECT p.prosecdef, p.proconfig::text AS proconfig FROM pg_proc p
                JOIN pg_namespace n ON n.oid = p.pronamespace
                WHERE n.nspname = 'gdpr' AND p.proname = 'archive_donor_info'
                """.trimIndent(),
            )
        assertThat(security["prosecdef"])
            .`as`("SECURITY DEFINER is what lets api_uat/api_prod delete donors with no grants on gdpr")
            .isEqualTo(true)
        assertThat(security["proconfig"].toString())
            .`as`("an unpinned SECURITY DEFINER function is a privilege-escalation vector")
            .contains("search_path=")
    }

    private fun insertDonor(
        id: Long,
        parentId: Long?,
        age: String,
        isLeadContact: Boolean = false,
        name: String? = null,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO donors (id, name, email, phone_number, post_code, referral, created_at, updated_at,
                                archived, is_lead_contact, donor_parent_id)
            VALUES ($id, '${name ?: "Expired Donor $id"}', 'expired$id@example.org', '07700900000', 'SW9 0AA',
                    'poster', now() - interval '$age', now() - interval '$age', 'N', $isLeadContact,
                    ${parentId ?: "NULL"})
            """.trimIndent(),
        )
    }

    private fun insertExpiredDonor(
        id: Long,
        parentId: Long?,
    ) = insertDonor(id, parentId, age = "18 months")

    @Test
    fun `the retention routine anonymises an expired donor in place rather than deleting it`() {
        jdbcTemplate.update(
            """
            INSERT INTO donor_parents (id, name, type, archived, created_at, updated_at)
            VALUES (900100, 'A Drop Point', 'DROPPOINT', 'N', now(), now())
            """.trimIndent(),
        )
        insertExpiredDonor(900011, parentId = 900100)

        val summary = jdbcTemplate.queryForObject("SELECT gdpr.performgdprcleanup()", String::class.java)
        assertThat(summary).contains("GDPR Cleanup: Archived")

        val row = jdbcTemplate.queryForMap("SELECT name, email, phone_number FROM donors WHERE id = 900011")
        assertThat(row["name"]).isEqualTo("Donor - Erased due to GDPR policy")
        assertThat(row["email"]).isEqualTo("")

        // The load-bearing assertion: the row SURVIVES. kits.donor_id is ON DELETE SET NULL, so
        // anonymising rather than deleting is what preserves each kit's donation provenance.
        // PR #80's first draft hard-deleted here, which would have severed that permanently.
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM donors WHERE id = 900011", Int::class.java))
            .`as`("retention anonymises in place; deleting would sever kit provenance")
            .isEqualTo(1)
    }

    /**
     * Issue #93. This test used to assert the OPPOSITE — that a parentless donor is never
     * anonymised — and that expectation was deliberate, not an oversight.
     *
     * gdpr.donors_to_archive filtered on `donor_parents.type <> 'BUSINESS'` across a LEFT JOIN.
     * For a donor with no donor parent that expression is NULL, and WHERE treats NULL as false,
     * so parentless donors were excluded from retention entirely and kept their PII indefinitely.
     * The fix was held back only because nobody knew the blast radius: it would anonymise every
     * parentless donor over 12 months old on the first run, irreversibly.
     *
     * Measured against production on 2026-07-22 (issue #93 comment): of 425 donors, ZERO have a
     * null donor_parent_id, so the corrected predicate selects zero additional rows today. The
     * hole is still real — DonorMutations accepts a null donorParentId on create and a null id on
     * update actively detaches — so the fix ships as a zero-risk correctness change, and this test
     * now pins the CORRECTED behaviour.
     *
     * V26.07.22.1200__fix_parentless_donor_retention.sql is the fix.
     */
    @Test
    fun `a donor with no donor parent is anonymised - issue 93`() {
        insertExpiredDonor(900012, parentId = null)

        jdbcTemplate.queryForObject("SELECT gdpr.performgdprcleanup()", String::class.java)

        assertThat(jdbcTemplate.queryForObject("SELECT name FROM donors WHERE id = 900012", String::class.java))
            .`as`("a parentless donor past the retention threshold must now enter donors_to_archive")
            .isEqualTo("Donor - Erased due to GDPR policy")
    }

    /**
     * The guard rail for issue #93's fix. Widening the donor-parent predicate is one character
     * away from widening retention itself, and every row this view yields gets irreversibly
     * anonymised by the Saturday pg_cron job.
     *
     * So this asserts the fix at the view — the only place the predicate exists — and asserts,
     * with the same weight, that the view's remaining conditions still exclude what they always
     * excluded. Issue #95, decided 2026-08-11: `is_lead_contact = false` used to exempt 34
     * named individuals in production with no recorded rationale; that exemption is now
     * removed by V26.08.11.1400__extend_gdpr_retention_scope.sql, so a lead contact must be
     * admitted exactly like any other individual donor past the 12-month threshold.
     */
    @Test
    fun `the corrected predicate admits parentless donors and lead contacts, and nothing else`() {
        jdbcTemplate.update(
            """
            INSERT INTO donor_parents (id, name, type, archived, created_at, updated_at)
            VALUES (900101, 'A Business', 'BUSINESS', 'N', now(), now())
            """.trimIndent(),
        )
        insertDonor(900020, parentId = null, age = "18 months")
        insertDonor(900021, parentId = 900101, age = "18 months")
        insertDonor(900022, parentId = null, age = "18 months", isLeadContact = true)
        insertDonor(900023, parentId = null, age = "3 months")
        insertDonor(900024, parentId = null, age = "18 months", name = "Donor - Erased due to GDPR policy")
        insertDonor(900025, parentId = null, age = "18 months", name = "Warehouse #business")

        val selected =
            jdbcTemplate.queryForList(
                "SELECT id FROM gdpr.donors_to_archive WHERE id BETWEEN 900020 AND 900025",
                Long::class.java,
            )

        assertThat(selected)
            .`as`("issue #93: a parentless donor past the 12-month threshold must be selected")
            .contains(900020L)
        assertThat(selected)
            .`as`("a BUSINESS-parented donor must stay excluded")
            .doesNotContain(900021L)
        assertThat(selected)
            .`as`("issue #95: a lead contact past the 12-month threshold must now be selected too")
            .contains(900022L)
        assertThat(selected)
            .`as`("a donor inside the 12-month window must stay excluded")
            .doesNotContain(900023L)
        assertThat(selected)
            .`as`("an already-erased donor must stay excluded, or every run re-anonymises it")
            .doesNotContain(900024L)
        assertThat(selected)
            .`as`("the '#business' / '#droppoint' name exclusion must survive")
            .doesNotContain(900025L)
    }

    @Test
    fun `deleting a donor succeeds and writes a PII-free archive trace`() {
        jdbcTemplate.update(
            """
            INSERT INTO donors (id, name, email, phone_number, post_code, referral, created_at, updated_at, archived)
            VALUES (900001, 'Convergence Test Donor', 'ct@example.org', '07700900000', 'SW9 0AA',
                    'word of mouth', now(), now(), 'N')
            """.trimIndent(),
        )

        val archivedBefore = jdbcTemplate.queryForObject("SELECT count(*) FROM gdpr.donors_archive", Int::class.java)!!

        // Guards the converged schema against a future change breaking deletion. Note this
        // would NOT have caught the original bug — see the class comment.
        val deleted = jdbcTemplate.update("DELETE FROM donors WHERE id = 900001")
        assertThat(deleted).isEqualTo(1)

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM gdpr.donors_archive", Int::class.java))
            .`as`("the BEFORE DELETE trigger must have archived a trace")
            .isEqualTo(archivedBefore + 1)

        val trace = jdbcTemplate.queryForMap("SELECT * FROM gdpr.donors_archive WHERE donor_id = 900001")
        assertThat(trace["referral"]).isEqualTo("word of mouth")
        assertThat(trace.keys)
            .`as`("the archive is deliberately PII-free")
            .doesNotContain("name", "email", "phone_number", "post_code")
    }

    private fun insertRev(rev: Long) {
        jdbcTemplate.update(
            "INSERT INTO custom_rev_info (id, timestamp, custom_user) VALUES ($rev, 0, 'test')",
        )
    }

    private fun insertDeviceRequest(
        id: Long,
        age: String,
        details: String,
        clientRef: String,
        collectionContactName: String,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO device_requests (id, is_prepped, is_sales, status, created_at, updated_at,
                                          details, client_ref, collection_contact_name)
            VALUES ($id, false, false, 'NEW', now() - interval '$age', now() - interval '$age',
                    '$details', '$clientRef', '$collectionContactName')
            """.trimIndent(),
        )
    }

    /**
     * Issue #127: the weekly routine used to wipe device_requests.details/client_ref in the
     * live table only. device_requests_audit_trail (14,011 rows in production at the time this
     * was measured) kept every previous version forever, including the original text behind an
     * already-"erased" live row. This asserts the audit trail is scrubbed in the same run, keyed
     * off the live row's updated_at so ordering within the function does not matter.
     */
    @Test
    fun `the retention routine scrubs device_requests_audit_trail in step with the live row - issue 127`() {
        insertDeviceRequest(
            900200,
            age = "60 weeks",
            details = "Client has a hardship case",
            clientRef = "REF-DUE",
            collectionContactName = "Jane Due",
        )
        insertRev(900200)
        jdbcTemplate.update(
            """
            INSERT INTO device_requests_audit_trail (id, rev, revtype, updated_at, details, client_ref, collection_contact_name)
            VALUES (900200, 900200, 0, now() - interval '60 weeks', 'Client has a hardship case', 'REF-DUE', 'Jane Due')
            """.trimIndent(),
        )

        insertDeviceRequest(
            900201,
            age = "3 months",
            details = "Recent request, not due yet",
            clientRef = "REF-RECENT",
            collectionContactName = "Jo Recent",
        )
        insertRev(900201)
        jdbcTemplate.update(
            """
            INSERT INTO device_requests_audit_trail (id, rev, revtype, updated_at, details, client_ref, collection_contact_name)
            VALUES (900201, 900201, 0, now() - interval '3 months', 'Recent request, not due yet', 'REF-RECENT', 'Jo Recent')
            """.trimIndent(),
        )

        jdbcTemplate.queryForObject("SELECT gdpr.performgdprcleanup()", String::class.java)

        val liveDue = jdbcTemplate.queryForMap("SELECT details, client_ref, collection_contact_name FROM device_requests WHERE id = 900200")
        assertThat(liveDue["details"]).isEqualTo("RECORD DELETED BY SYSTEM - GDPR")
        assertThat(liveDue["client_ref"]).isEqualTo("WIPED - GDPR")
        assertThat(liveDue["collection_contact_name"])
            .`as`("issue #128: collection_contact_name scrubs on the same 52-week clock as client_ref")
            .isEqualTo("WIPED - GDPR")

        val auditDue =
            jdbcTemplate.queryForMap(
                "SELECT details, client_ref, collection_contact_name FROM device_requests_audit_trail WHERE id = 900200",
            )
        assertThat(auditDue["details"])
            .`as`("issue #127: the audit trail must not keep the original text behind an erased live row")
            .isEqualTo("RECORD DELETED BY SYSTEM - GDPR")
        assertThat(auditDue["client_ref"]).isEqualTo("WIPED - GDPR")
        assertThat(auditDue["collection_contact_name"]).isEqualTo("WIPED - GDPR")

        val liveRecent =
            jdbcTemplate.queryForMap(
                "SELECT details, client_ref, collection_contact_name FROM device_requests WHERE id = 900201",
            )
        assertThat(liveRecent["details"]).isEqualTo("Recent request, not due yet")
        val auditRecent =
            jdbcTemplate.queryForMap(
                "SELECT details, client_ref, collection_contact_name FROM device_requests_audit_trail WHERE id = 900201",
            )
        assertThat(auditRecent["details"])
            .`as`("a request inside the retention window must stay untouched in the audit trail too")
            .isEqualTo("Recent request, not due yet")
    }

    /**
     * device_requests_notes.content was confirmed in scope at the 2026-08-11 team review, at
     * 12 months (52 weeks) off the note's own updated_at. Notes are written once and rarely
     * updated, so this is effectively "12 months since the note was left".
     */
    @Test
    fun `the retention routine scrubs device_requests_notes content past 52 weeks`() {
        insertDeviceRequest(900210, age = "13 months", details = "n/a", clientRef = "n/a", collectionContactName = "n/a")

        jdbcTemplate.update(
            """
            INSERT INTO device_requests_notes (id, device_request_id, created_at, updated_at, content)
            VALUES (900211, 900210, now() - interval '60 weeks', now() - interval '60 weeks', 'Client disclosed an immigration case')
            """.trimIndent(),
        )
        jdbcTemplate.update(
            """
            INSERT INTO device_requests_notes (id, device_request_id, created_at, updated_at, content)
            VALUES (900212, 900210, now() - interval '10 weeks', now() - interval '10 weeks', 'Recent note, not due yet')
            """.trimIndent(),
        )

        jdbcTemplate.queryForObject("SELECT gdpr.performgdprcleanup()", String::class.java)

        assertThat(jdbcTemplate.queryForObject("SELECT content FROM device_requests_notes WHERE id = 900211", String::class.java))
            .isEqualTo("Note content deleted due to GDPR policy")
        assertThat(jdbcTemplate.queryForObject("SELECT content FROM device_requests_notes WHERE id = 900212", String::class.java))
            .`as`("a note inside the retention window must stay untouched")
            .isEqualTo("Recent note, not due yet")
    }

    /**
     * Issue #129, decided 2026-08-11: referring_organisation_contacts (full_name/email/
     * phone_number/address) is in scope at 12 months off the contact's own updated_at, live and
     * audit trail together. The live scrub deliberately does not bump updated_at, so the audit
     * trail parity update can reuse the same predicate regardless of statement order.
     */
    @Test
    fun `the retention routine scrubs referring_organisation_contacts and its audit trail - issue 129`() {
        jdbcTemplate.update(
            """
            INSERT INTO referring_organisation_contacts (id, full_name, email, phone_number, address, archived, created_at, updated_at)
            VALUES (900220, 'Jane Referrer', 'jane@example.org', '07700900111', '1 Example St', 'N',
                    now() - interval '13 months', now() - interval '13 months')
            """.trimIndent(),
        )
        insertRev(900220)
        jdbcTemplate.update(
            """
            INSERT INTO referring_organisation_contacts_audit_trail (id, rev, revtype, full_name, email, phone_number, address, archived, updated_at)
            VALUES (900220, 900220, 0, 'Jane Referrer', 'jane@example.org', '07700900111', '1 Example St', 'N', now() - interval '13 months')
            """.trimIndent(),
        )

        jdbcTemplate.update(
            """
            INSERT INTO referring_organisation_contacts (id, full_name, email, phone_number, address, archived, created_at, updated_at)
            VALUES (900221, 'Recent Contact', 'recent@example.org', '07700900222', '2 Example St', 'N',
                    now() - interval '3 months', now() - interval '3 months')
            """.trimIndent(),
        )

        jdbcTemplate.queryForObject("SELECT gdpr.performgdprcleanup()", String::class.java)

        val liveDue =
            jdbcTemplate.queryForMap(
                "SELECT full_name, email, phone_number, address FROM referring_organisation_contacts WHERE id = 900220",
            )
        assertThat(liveDue["full_name"]).isEqualTo("Contact - Erased due to GDPR policy")
        assertThat(liveDue["email"]).isEqualTo("")
        assertThat(liveDue["phone_number"]).isEqualTo("")
        assertThat(liveDue["address"]).isEqualTo("")

        val auditDue =
            jdbcTemplate.queryForMap(
                "SELECT full_name, email, phone_number, address FROM referring_organisation_contacts_audit_trail WHERE id = 900220",
            )
        assertThat(auditDue["full_name"])
            .`as`("issue #129: audit trail must scrub in step with the live row, not lag behind it")
            .isEqualTo("Contact - Erased due to GDPR policy")
        assertThat(auditDue["email"]).isEqualTo("")

        assertThat(
            jdbcTemplate.queryForObject("SELECT full_name FROM referring_organisation_contacts WHERE id = 900221", String::class.java),
        ).`as`("a contact inside the 12-month window must stay untouched")
            .isEqualTo("Recent Contact")
    }

    /**
     * gdpr_cleanup_runs (V26.08.11.1600) is the durable, structured record of each run -
     * both for compliance statistics and as the "last successful run" marker
     * GdprDonorCleanup's startup catch-up checks. This asserts performgdprcleanup() writes
     * a row every time it runs, whether triggered by pg_cron or the in-app job.
     */
    @Test
    fun `the retention routine records its own run in gdpr_cleanup_runs`() {
        val before = jdbcTemplate.queryForObject("SELECT count(*) FROM gdpr_cleanup_runs", Int::class.java)!!

        insertExpiredDonor(900230, parentId = null)
        val summary = jdbcTemplate.queryForObject("SELECT gdpr.performgdprcleanup()", String::class.java)

        val after = jdbcTemplate.queryForObject("SELECT count(*) FROM gdpr_cleanup_runs", Int::class.java)!!
        assertThat(after)
            .`as`("every run must leave a trace, or the startup catch-up check can never tell one happened")
            .isEqualTo(before + 1)

        // >= 1, not == 1: other tests in this class share the embedded DB and don't all
        // call performgdprcleanup() themselves, so a donor left un-anonymised by an earlier
        // test can legitimately get swept up by this run too - that's correct behaviour, not
        // a bug in this test.
        val row = jdbcTemplate.queryForMap("SELECT * FROM gdpr_cleanup_runs ORDER BY id DESC LIMIT 1")
        assertThat((row["donor_count"] as Number).toInt())
            .`as`("this run must have archived at least the donor it just inserted")
            .isGreaterThanOrEqualTo(1)
        assertThat(row["summary"]).isEqualTo(summary)
        assertThat(row["ran_at"]).isNotNull()
    }
}
