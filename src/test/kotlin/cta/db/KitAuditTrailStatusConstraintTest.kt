package cta.db

import cta.app.KitStatus
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtDecoder

/**
 * Pins the kit_audit_trail.status check constraint to the KitStatus enum in FRESH databases.
 *
 * Scope, stated honestly: this constraint exists only where the baseline migration actually
 * created the table — local dev and these embedded test databases. UAT and production predate
 * Flyway, so `create table if not exists` was a no-op there and they carry no check constraint
 * at all (verified against pg_constraint on both, 2026-07-21). This test therefore proves the
 * fresh-database schema tracks the enum; it can say nothing about the live databases, where
 * V26.07.22.1000 is deliberately a no-op.
 *
 * It goes red if an enum value is added or removed without a matching migration — which is what
 * let ALLOCATION_ASSESSMENT (#85) sit in the constraint unused for the system's whole lifetime.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class KitAuditTrailStatusConstraintTest {
    @MockBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    private fun statusConstraintDefinition(): String? =
        jdbcTemplate.queryForObject(
            """
            SELECT pg_get_constraintdef(c.oid)
              FROM pg_constraint c
              JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
             WHERE c.conrelid = 'public.kit_audit_trail'::regclass
               AND c.contype = 'c'
               AND a.attname = 'status'
               AND array_length(c.conkey, 1) = 1
            """.trimIndent(),
            String::class.java,
        )

    @Test
    fun `the status check constraint admits exactly the declared KitStatus values`() {
        val definition =
            requireNotNull(statusConstraintDefinition()) {
                "a fresh database must have the baseline check constraint on kit_audit_trail.status"
            }

        val allowed = Regex("'([A-Z_]+)'").findAll(definition).map { it.groupValues[1] }.toSet()

        assertThat(allowed)
            .`as`("the constraint must track the enum, in either direction")
            .containsExactlyInAnyOrderElementsOf(KitStatus.entries.map { it.name })
    }

    @Test
    fun `the retired ALLOCATION_ASSESSMENT value is gone`() {
        assertThat(statusConstraintDefinition())
            .`as`("V26.07.22.1000 must drop the retired value from the tightened constraint")
            .doesNotContain("ALLOCATION_ASSESSMENT")
    }
}
