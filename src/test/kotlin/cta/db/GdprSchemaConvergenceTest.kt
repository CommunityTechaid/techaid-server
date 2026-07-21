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
}
