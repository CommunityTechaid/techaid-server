package cta.app.services

import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtDecoder

/**
 * Pins the in-app GDPR donor cleanup that supersedes the pg_cron job gdpr-weekly-cleanup,
 * which is server-side state silently lost on any DB restore (ops SCHEDULED-JOBS-REGISTER).
 *
 * The retention rules are Flyway-managed (V22.20.13.1517__gdpr.sql): gdpr.donors_to_delete
 * selects individual donors whose latest kit donation (or record creation, if kit-less) is
 * over 12 months old, excluding business/droppoint donors; a BEFORE DELETE trigger archives
 * a PII-free trace into gdpr.donors_archive; kits.donor_id is ON DELETE SET NULL. This test
 * proves the service deletes exactly that set, archives the trace, detaches kits, and is
 * idempotent — so running it alongside the pg_cron job is safe.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class GdprDonorCleanupServiceTest {
    @MockBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var service: GdprDonorCleanupService

    @Test
    fun `deletes expired individual donors, archives a trace, detaches kits, leaves the rest`() {
        jdbcTemplate.execute(
            """
            INSERT INTO donors (id, name, created_at, referral, archived) VALUES
              (9001, 'Old Individual', now() - interval '13 months', 'Search engine', 'N'),
              (9002, 'Recent Individual', now() - interval '1 month', null, 'N'),
              (9003, 'Acme #business', now() - interval '13 months', null, 'N'),
              (9004, 'Donor With Recent Kit', now() - interval '13 months', null, 'N'),
              (9005, 'Donor With Old Kit', now() - interval '14 months', null, 'N');
            INSERT INTO kits (id, age, donor_id, created_at, archived) VALUES
              (9101, 0, 9004, now() - interval '2 months', 'N'),
              (9102, 0, 9005, now() - interval '13 months', 'N');
            """.trimIndent(),
        )

        val deleted = service.deleteExpiredDonors()

        assertThat(deleted).isEqualTo(2)

        val remainingDonors =
            jdbcTemplate.queryForList("SELECT id FROM donors WHERE id BETWEEN 9001 AND 9005", Long::class.java)
        assertThat(remainingDonors).containsExactlyInAnyOrder(9002L, 9003L, 9004L)

        val archived =
            jdbcTemplate.queryForList(
                "SELECT donor_id FROM gdpr.donors_archive WHERE donor_id BETWEEN 9001 AND 9005",
                Long::class.java,
            )
        assertThat(archived).containsExactlyInAnyOrder(9001L, 9005L)
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT referral FROM gdpr.donors_archive WHERE donor_id = 9001",
                String::class.java,
            ),
        ).isEqualTo("Search engine")

        // The old kit survives its donor's deletion, detached by ON DELETE SET NULL.
        assertThat(
            jdbcTemplate.queryForObject("SELECT donor_id IS NULL FROM kits WHERE id = 9102", Boolean::class.java),
        ).isTrue()

        assertThat(service.deleteExpiredDonors()).isEqualTo(0)
    }
}
