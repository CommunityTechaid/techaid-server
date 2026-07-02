package cta.db

import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtDecoder

/**
 * Verifies the secondary-index migration against a production-shaped schema.
 *
 * Most of these tables (device_requests, referring_organisation_*, note, ...) have no
 * CREATE TABLE migration — the schema predates Flyway and test databases only contain
 * the Flyway-managed subset. So the migration's column-existence guards no-op for them
 * at startup. This test builds a minimal fixture with the production table/column names
 * and re-executes the script against it, proving the SQL creates every expected index
 * where the columns exist (as they all do in production) and that the guards +
 * IF NOT EXISTS make it safe to run repeatedly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class IndexMigrationTest {
    @MockBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `index migration creates the expected indexes once the tables exist`() {
        // Minimal production-shaped fixture for the tables/columns Flyway does not manage.
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS device_requests (id int8 PRIMARY KEY, status varchar(255), referring_organisation_contact_id int8);
            CREATE TABLE IF NOT EXISTS referring_organisation_contacts (id int8 PRIMARY KEY, referring_organisation_id int8);
            CREATE TABLE IF NOT EXISTS device_requests_notes (id int8 PRIMARY KEY, device_request_id int8);
            CREATE TABLE IF NOT EXISTS referring_organisations_notes (id int8 PRIMARY KEY, referring_organisation_id int8);
            CREATE TABLE IF NOT EXISTS referring_organisation_contacts_notes (id int8 PRIMARY KEY, referring_organisation_contact_id int8);
            CREATE TABLE IF NOT EXISTS note (id int8 PRIMARY KEY, kit_id int8);
            ALTER TABLE kits ADD COLUMN IF NOT EXISTS device_request_id int8;
            ALTER TABLE kits ADD COLUMN IF NOT EXISTS serial_no varchar(255);
            ALTER TABLE donors ADD COLUMN IF NOT EXISTS donor_parent_id int8;
            """.trimIndent(),
        )

        val sql =
            ClassPathResource("db/migration/V26.07.02.1000__add_indexes.sql")
                .inputStream
                .bufferedReader()
                .use { it.readText() }

        // Safe to re-run: guarded by column existence and CREATE INDEX IF NOT EXISTS.
        jdbcTemplate.execute(sql)
        jdbcTemplate.execute(sql)

        val indexes =
            jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = current_schema()",
                String::class.java,
            )

        assertThat(indexes).contains(
            "ix_kits_donor_id",
            "ix_kits_device_request_id",
            "ix_kits_status",
            "ix_kits_type",
            "ix_kits_serial_no",
            "ix_device_requests_status",
            "ix_device_requests_referring_organisation_contact_id",
            "ix_referring_organisation_contacts_referring_organisation_id",
            "ix_donors_donor_parent_id",
            "ix_device_requests_notes_device_request_id",
            "ix_referring_organisations_notes_referring_organisation_id",
            "ix_roc_notes_referring_organisation_contact_id",
            "ix_note_kit_id",
        )
    }
}
