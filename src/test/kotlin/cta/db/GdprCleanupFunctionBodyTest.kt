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
 * Pins the shape of gdpr.performgdprcleanup() as the migrations actually build it.
 *
 * Two things are asserted, and they are related.
 *
 * 1. The three correction-4 OR branches are present. Each audit-trail UPDATE must fire when
 *    the LIVE row already carries its sentinel, not only when the live row is past its
 *    threshold. Without that branch, an audit row behind a request edited after being
 *    scrubbed becomes permanently unreachable: the only thing that would clean it is keyed
 *    to a timestamp that keeps moving away. 1,069 details + 471 client_ref rows were in that
 *    state in production before it was fixed (#127).
 *
 * 2. No TEMP-REVERT marker survives in the body. V26.08.12.1100 shipped two of them sitting
 *    directly above the UPDATEs in (1), reading "drop the OR branch to prove B3
 *    discriminates" — an instruction to delete exactly the logic (1) exists to protect.
 *    V26.08.12.1200 replaces the body with the clean version production runs.
 *
 * A file-based check cannot express this: V26.08.12.1100 is applied and therefore immutable
 * (editing it fails Flyway checksum validation on the next boot), so its text keeps the
 * markers forever. What matters is the function the migrations leave behind, which is what
 * this reads.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class GdprCleanupFunctionBodyTest {
    @MockBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    private fun functionBody(): String =
        jdbcTemplate.queryForObject(
            """
            SELECT p.prosrc
              FROM pg_proc p
              JOIN pg_namespace n ON n.oid = p.pronamespace
             WHERE n.nspname = 'gdpr' AND p.proname = 'performgdprcleanup'
            """.trimIndent(),
            String::class.java,
        ) ?: error("gdpr.performgdprcleanup() does not exist in the test database")

    @Test
    fun `the audit-trail updates keep their already-erased-live-row branch`() {
        val body = functionBody()

        assertThat(body)
            .describedAs("device_requests_audit_trail.details must also fire on an already-wiped live row (#127)")
            .contains("OR d.details = 'RECORD DELETED BY SYSTEM - GDPR'")

        assertThat(body)
            .describedAs("device_requests_audit_trail.client_ref must also fire on an already-wiped live row (#127)")
            .contains("OR d.client_ref = 'WIPED - GDPR'")

        assertThat(body)
            .describedAs("referring_organisation_contacts_audit_trail must also fire on an already-erased live row (#129)")
            .contains("OR c.full_name = 'Contact - Erased due to GDPR policy'")
    }

    @Test
    fun `no temporary diagnostic marker survives in the deployed function`() {
        assertThat(functionBody())
            .describedAs(
                "A TEMP-REVERT marker in the function body tells the next reader to delete the " +
                    "correction-4 OR branch. It cannot be removed by editing the migration that " +
                    "introduced it, only by superseding the function body.",
            ).doesNotContain("TEMP-REVERT")
    }

    /**
     * #161 dropped donors.coordinates and kits.coordinates. A plpgsql body resolves column names
     * at EXECUTION, so a function that still references them would not fail this suite's
     * migrations, nor a deploy - it would fail the next Friday retention run, which since the
     * 2026-08-18 switchover is the only thing performing retention and has no alert on it yet
     * (#174). Cheap to assert, and the failure it prevents is a silent stop to GDPR erasure.
     */
    @Test
    fun `the body no longer references the dropped coordinates columns`() {
        assertThat(functionBody().lowercase())
            .describedAs("gdpr.performgdprcleanup() must not touch a column that no longer exists (#161)")
            .doesNotContain("coordinates")
    }
}
