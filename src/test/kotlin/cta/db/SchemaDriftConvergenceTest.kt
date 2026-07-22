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
 * Pins the five schema divergences between techaid_uat and techaid_prod that
 * V26.07.22.1300__converge_uat_prod_schema_drift.sql closes (issue #91, findings 1, 4, 5, 6, 7).
 *
 * What these tests can and cannot prove — because it is easy to overclaim here:
 *
 *  - The default assertions (findings 1 and 5) and the object assertions (finding 6) ARE
 *    discriminating on a fresh database. V26.07.03.0900 creates all three `archived` columns
 *    and admin_config.can_public_request_desktop NOT NULL with NO default, and no migration
 *    creates fuzzystrmatch or public.iif — so removing the convergence migration turns them red.
 *  - The widening test (finding 4) and the orphan-column test (finding 7) have to manufacture
 *    the drift first. A fresh database gets device_requests_audit_trail.details as TEXT (the
 *    entity declares columnDefinition = "TEXT") and has never had the four orphan columns, so
 *    the migration correctly no-ops on both. Reproducing UAT's shape and re-running the script
 *    is the only way to exercise those branches in CI.
 *  - Nothing here proves anything about the live databases. No test built from this repo's
 *    migrations can see hand-applied DDL — that is the whole subject of issue #91. The real
 *    verification is a rehearsal against a restored production replica.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class SchemaDriftConvergenceTest {
    @MockBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    private val migrationSql: String
        get() =
            ClassPathResource("db/migration/V26.07.22.1300__converge_uat_prod_schema_drift.sql")
                .inputStream
                .bufferedReader()
                .use { it.readText() }

    private fun columnDefault(
        table: String,
        column: String,
    ): String? =
        jdbcTemplate.queryForObject(
            """
            SELECT column_default FROM information_schema.columns
            WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?
            """.trimIndent(),
            String::class.java,
            table,
            column,
        )

    private fun columnExists(
        table: String,
        column: String,
    ): Boolean =
        jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1 FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?
            )
            """.trimIndent(),
            Boolean::class.java,
            table,
            column,
        )!!

    private fun detailsType(): String =
        jdbcTemplate.queryForObject(
            """
            SELECT coalesce(character_maximum_length::text, data_type)
            FROM information_schema.columns
            WHERE table_schema = current_schema()
              AND table_name = 'device_requests_audit_trail' AND column_name = 'details'
            """.trimIndent(),
            String::class.java,
        )!!

    @Test
    fun `every archived column defaults to N so an insert omitting it cannot fail`() {
        // Production had no default on any of these and NOT NULL on the first two, so an
        // INSERT omitting `archived` raised a not-null violation there and succeeded on UAT.
        listOf("donor_parents", "referring_organisation_contacts", "referring_organisations").forEach { table ->
            assertThat(columnDefault(table, "archived"))
                .`as`("%s.archived must default to 'N' (issue #91 finding 1)", table)
                .isEqualTo("'N'::bpchar")
        }
    }

    @Test
    fun `the archived columns keep the char type the YesNoConverter writes`() {
        // The entities map `archived` as a non-nullable Kotlin Boolean, but only through
        // org.hibernate.type.YesNoConverter — the column itself is character(1) holding 'Y'/'N'.
        // If it ever became a real boolean column the 'N' default above would be nonsense.
        listOf("donor_parents", "referring_organisation_contacts", "referring_organisations").forEach { table ->
            val type =
                jdbcTemplate.queryForObject(
                    """
                    SELECT data_type FROM information_schema.columns
                    WHERE table_schema = current_schema() AND table_name = ? AND column_name = 'archived'
                    """.trimIndent(),
                    String::class.java,
                    table,
                )
            assertThat(type).`as`("%s.archived is char(1), not boolean", table).isEqualTo("character")
        }
    }

    @Test
    fun `can_public_request_desktop defaults to false`() {
        assertThat(columnDefault("admin_config", "can_public_request_desktop"))
            .`as`("issue #91 finding 5 — UAT had this default, production did not")
            .isEqualTo("false")
    }

    @Test
    fun `nullability is left exactly as it was — convergence does not tighten constraints`() {
        // Adding NOT NULL to referring_organisations.archived (nullable in both live databases)
        // would be a separate decision with a backfill attached, not a convergence. Guard against
        // a future edit quietly folding it into this migration.
        val nullable =
            jdbcTemplate.queryForObject(
                """
                SELECT is_nullable FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = 'referring_organisations' AND column_name = 'archived'
                """.trimIndent(),
                String::class.java,
            )
        assertThat(nullable)
            .`as`("the baseline made this NOT NULL; the convergence migration must not change it either way")
            .isEqualTo("NO")
    }

    @Test
    fun `the fuzzystrmatch extension and public iif exist, as they already do in production`() {
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM pg_extension WHERE extname = 'fuzzystrmatch'", Int::class.java))
            .`as`("issue #91 finding 6 — production has this extension and UAT did not")
            .isEqualTo(1)
        assertThat(jdbcTemplate.queryForObject("SELECT soundex('Tymczak')", String::class.java)).isNotBlank()

        val iif =
            jdbcTemplate.queryForObject(
                "SELECT to_regprocedure('public.iif(boolean,anyelement,anyelement)')::text",
                String::class.java,
            )
        assertThat(iif)
            .`as`("issue #91 finding 6 — public.iif is hand-written, in production only, and created by no migration")
            .isNotNull()
        // The casts are required, not cosmetic: `anyelement` cannot be resolved from an untyped
        // literal. Production's copy behaves identically — this is the function reproduced verbatim.
        assertThat(jdbcTemplate.queryForObject("SELECT public.iif(true, 'yes'::text, 'no'::text)", String::class.java))
            .isEqualTo("yes")
        assertThat(jdbcTemplate.queryForObject("SELECT public.iif(false, 1, 2)", Int::class.java)).isEqualTo(2)
    }

    @Test
    fun `the four UAT-only orphan columns are dropped and the counts are reported`() {
        val orphans =
            listOf(
                "referring_organisations" to "address",
                "referring_organisations" to "domain",
                "referring_organisation_contacts" to "first_name",
                "kit_audit_trail" to "organisation_id",
            )

        orphans.forEach { (table, column) ->
            assertThat(columnExists(table, column))
                .`as`("a fresh database never had %s.%s — the migration no-ops here", table, column)
                .isFalse()
        }

        try {
            // Reproduce UAT's ddl-auto residue, then let the migration clean it up.
            orphans.forEach { (table, column) ->
                val type = if (column == "organisation_id") "int8" else "varchar(255)"
                jdbcTemplate.execute("ALTER TABLE $table ADD COLUMN IF NOT EXISTS $column $type")
            }
            orphans.forEach { (table, column) -> assertThat(columnExists(table, column)).isTrue() }

            jdbcTemplate.execute(migrationSql)

            orphans.forEach { (table, column) ->
                assertThat(columnExists(table, column))
                    .`as`("issue #91 finding 7 — %s.%s is declared by no entity and no migration", table, column)
                    .isFalse()
            }
        } finally {
            orphans.forEach { (table, column) ->
                jdbcTemplate.execute("ALTER TABLE $table DROP COLUMN IF EXISTS $column")
            }
        }
    }

    @Test
    fun `re-running the migration changes nothing`() {
        jdbcTemplate.execute(migrationSql)
        jdbcTemplate.execute(migrationSql)

        assertThat(columnDefault("donor_parents", "archived")).isEqualTo("'N'::bpchar")
        assertThat(columnDefault("admin_config", "can_public_request_desktop")).isEqualTo("false")
        assertThat(detailsType()).`as`("TEXT is wider than varchar(4096) and must not be narrowed").isEqualTo("text")
        assertThat(jdbcTemplate.queryForObject("SELECT public.iif(true, 1, 2)", Int::class.java))
            .`as`("re-creating public.iif would fail in production, where api_prod does not own it")
            .isEqualTo(1)
    }

    @Test
    fun `a narrow details column is widened to 4096 and a wider one is left alone`() {
        assertThat(detailsType())
            .`as`("a fresh database gets TEXT from the entity's columnDefinition, so the migration no-ops")
            .isEqualTo("text")

        try {
            // Reproduce UAT's shape. `USING left(...)` only so an unrelated future test writing an
            // audit row cannot make this one fail; the table is empty in this context today.
            jdbcTemplate.execute(
                "ALTER TABLE device_requests_audit_trail ALTER COLUMN details TYPE varchar(255) USING left(details, 255)",
            )
            assertThat(detailsType()).isEqualTo("255")

            jdbcTemplate.execute(migrationSql)

            assertThat(detailsType())
                .`as`("issue #91 finding 4 — UAT was the more restrictive side and rejected strings production accepts")
                .isEqualTo("4096")
        } finally {
            jdbcTemplate.execute("ALTER TABLE device_requests_audit_trail ALTER COLUMN details TYPE TEXT")
        }
    }
}
