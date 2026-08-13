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
 * Pins the Envers naming and strategy the schema was actually built against.
 *
 * `application.yml` once carried an Envers block under `spring.jpa.properties.hibernate.envers.*`,
 * asking for `revision_id` / `revision_type` columns and `ValidityAuditStrategy`. Spring passes
 * those through as `hibernate.envers.*`, but Envers only reads `org.hibernate.envers.*` — so the
 * block never took effect and the schema was built against Envers' defaults. Removing it changed
 * nothing, which is the point; this test is what makes that claim checkable.
 *
 * It exists to catch the tempting "fix": adding the `org.` prefix so the settings finally apply.
 * That would make Hibernate expect `revision_id`, `revision_type` and `revend` columns that do not
 * exist, and with `ddl-auto: validate` the application would fail to start. Renaming the revision
 * columns or moving to ValidityAuditStrategy requires a Flyway migration first — this test should
 * be updated in the same change as that migration, never on its own.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class EnversSchemaContractTest {
    @MockBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    private fun columnsOf(table: String): Set<String> =
        jdbcTemplate
            .queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ?",
                String::class.java,
                table,
            ).toSet()

    @Test
    fun `audit tables use Envers default revision columns, not the ones the removed config asked for`() {
        listOf("kit_audit_trail", "device_requests_audit_trail").forEach { table ->
            val columns = columnsOf(table)

            assertThat(columns)
                .`as`("$table should carry Envers' default revision columns")
                .contains("rev", "revtype")

            assertThat(columns)
                .`as`(
                    "$table must not carry revision_id/revision_type. If it does, the Envers " +
                        "settings have been made to take effect and application.yml's comment is stale",
                ).doesNotContain("revision_id", "revision_type")
        }
    }

    @Test
    fun `audit tables have no revend column, so DefaultAuditStrategy is in effect`() {
        listOf("kit_audit_trail", "device_requests_audit_trail").forEach { table ->
            assertThat(columnsOf(table))
                .`as`(
                    "$table must have no revend column. ValidityAuditStrategy adds one; its " +
                        "absence is what proves DefaultAuditStrategy is what actually runs",
                ).doesNotContain("revend", "revision_end")
        }
    }
}
