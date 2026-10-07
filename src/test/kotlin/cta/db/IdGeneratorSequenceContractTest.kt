package cta.db

import cta.app.Donor
import cta.app.DonorRepository
import cta.app.Kit
import cta.app.KitRepository
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.bean.override.mockito.MockitoBean

/**
 * Pins how Hibernate assigns ids for the core entities: one `nextval` on the NAMED Flyway
 * sequence per insert (allocationSize = 1, no pooled optimizer, no implicit `<entity>_seq`).
 *
 * Written for the 2026-10-07 UAT `donors_pkey` collision (id 1663). If a Hibernate/Boot upgrade
 * ever switched an entity to a different sequence name or to a pooled block, the id would stop
 * being exactly `last_value + 1` and this goes red. It cannot see the DATA condition that caused
 * the UAT collision (a sequence sitting below max(id)) - that is per-environment state; see
 * db/admin/2026-10-07__readonly_sequence_vs_maxid_*.sql.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class IdGeneratorSequenceContractTest {
    @MockitoBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var donors: DonorRepository

    @Autowired
    lateinit var kits: KitRepository

    @Autowired
    lateinit var jdbc: JdbcTemplate

    private fun lastValue(seq: String): Long = jdbc.queryForObject("select last_value from $seq", Long::class.java)!!

    private fun newDonor() =
        donors.save(
            Donor(
                name = "Seq Donor",
                email = "seq@example.com",
                phoneNumber = "07000000000",
                postCode = "SE1 1AA",
                referral = "test",
            ),
        )

    @Test
    fun `donor ids come from donor_sequence one step at a time`() {
        jdbc.execute("select setval('donor_sequence', 5000, true)")

        val first = newDonor()
        val second = newDonor()

        assertThat(listOf(first.id, second.id)).containsExactly(5001L, 5002L)
        assertThat(lastValue("donor_sequence"))
            .`as`("a pooled optimizer would have jumped the DB sequence by its allocation size")
            .isEqualTo(5002L)
    }

    @Test
    fun `kit ids come from kit_sequence one step at a time`() {
        jdbc.execute("select setval('kit_sequence', 7000, true)")

        val first = kits.save(Kit(model = "Seq kit 1", age = 1))
        val second = kits.save(Kit(model = "Seq kit 2", age = 1))

        assertThat(listOf(first.id, second.id)).containsExactly(7001L, 7002L)
        assertThat(lastValue("kit_sequence")).isEqualTo(7002L)
    }

    @Test
    fun `flyway sequences increment by one, matching allocationSize = 1`() {
        val increments =
            jdbc.queryForList(
                "select sequencename, increment_by from pg_sequences where schemaname = 'public' " +
                    "and sequencename in ('donor_sequence','kit_sequence','donor_parent_sequence'," +
                    "'device_requests_sequence','referring_organisation_sequence'," +
                    "'referring_organisation_contacts_sequence','note_sequence')",
            )
        assertThat(increments).hasSize(7)
        assertThat(increments.map { (it["increment_by"] as Number).toLong() }).containsOnly(1L)
    }
}
