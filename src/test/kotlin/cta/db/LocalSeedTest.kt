package cta.db

import cta.app.AdminConfigRepository
import cta.app.DeviceRequestRepository
import cta.app.DonorParentRepository
import cta.app.DonorRepository
import cta.app.KitRepository
import cta.app.ReferringOrganisationContactRepository
import cta.app.ReferringOrganisationRepository
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.annotation.Transactional

/**
 * The local-profile synthetic seed (db/local/R__local_seed.sql): enough rows of every kind that a
 * fresh local database exercises the app, idempotent, and accepted by the entity mappings.
 * The same file is run by hand against UAT, so it must stay plain SQL.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "spring.flyway.locations=classpath:db/migration,classpath:db/local",
        "spring.jpa.hibernate.ddl-auto=validate",
    ],
)
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class LocalSeedTest {
    @MockitoBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired lateinit var jdbc: JdbcTemplate

    @Autowired lateinit var kits: KitRepository

    @Autowired lateinit var adminConfig: AdminConfigRepository

    @Autowired lateinit var deviceRequests: DeviceRequestRepository

    @Autowired lateinit var donors: DonorRepository

    @Autowired lateinit var donorParents: DonorParentRepository

    @Autowired lateinit var orgs: ReferringOrganisationRepository

    @Autowired lateinit var contacts: ReferringOrganisationContactRepository

    // Seeded rows only (ids from 900001), so the check also holds on a database with other data.
    private val minimumRows =
        mapOf(
            "referring_organisations" to 36,
            "referring_organisation_contacts" to 36,
            "device_requests" to 36,
            "device_requests_notes" to 36,
            "donor_parents" to 36,
            "donors" to 36,
            "kits" to 36,
            "note" to 36,
            "delivery_bookings" to 10,
            "posts" to 10,
            "referring_organisation_contacts_notes" to 10,
            "referring_organisations_notes" to 10,
            "referrer_limit_exceptions" to 10,
            "delivery_blocked_dates" to 10,
            "delivery_booking_overrides" to 10,
            "delivery_day_boroughs" to 10,
            "custom_rev_info" to 10,
            "device_requests_audit_trail" to 10,
            "donor_parents_audit_trail" to 10,
            "donors_audit_trail" to 10,
            "kit_audit_trail" to 10,
            "note_aud" to 10,
            "referring_organisation_contacts_audit_trail" to 10,
            "referring_organisations_audit_trail" to 10,
        )

    private fun seededCount(table: String): Int = jdbc.queryForObject("select count(*) from $table where id >= 900001", Int::class.java)!!

    private fun distinct(sql: String): Set<String> = jdbc.queryForList(sql, String::class.java).filterNotNull().toSet()

    @Test
    fun `seed provides the target row count for every seeded table`() {
        minimumRows.forEach { (table, min) ->
            assertThat(seededCount(table)).describedAs(table).isGreaterThanOrEqualTo(min)
        }
    }

    // No migration creates the admin_config singleton, and getAdminConfig() cannot return null, so a
    // local database without the row fails the adminConfig query the public request form depends on.
    @Test
    fun `seed provides the admin config singleton`() {
        val config = adminConfig.getAdminConfig()
        assertThat(config.id).isEqualTo(1L)
        assertThat(config.canPublicRequestLaptop).isTrue()
    }

    @Test
    fun `seed covers every enum value and a mix of archived flags`() {
        assertThat(distinct("select distinct status from kits where id >= 900001"))
            .containsExactlyInAnyOrderElementsOf(
                cta.app.KitStatus.entries
                    .map { it.name },
            )
        assertThat(distinct("select distinct type from kits where id >= 900001"))
            .containsExactlyInAnyOrderElementsOf(
                cta.app.KitType.entries
                    .map { it.name },
            )
        assertThat(distinct("select distinct status from device_requests where id >= 900001"))
            .containsExactlyInAnyOrderElementsOf(
                cta.app.DeviceRequestStatus.entries
                    .map { it.name },
            )
        assertThat(distinct("select distinct collection_method from device_requests where id >= 900001"))
            .containsExactlyInAnyOrderElementsOf(
                cta.app.CollectionMethod.entries
                    .map { it.name },
            )
        assertThat(distinct("select distinct membership_tier from referring_organisations where id >= 900001"))
            .containsExactlyInAnyOrderElementsOf(
                cta.app.MembershipTier.entries
                    .map { it.name },
            )
        assertThat(distinct("select distinct type from donor_parents where id >= 900001"))
            .containsExactlyInAnyOrderElementsOf(
                cta.app.DonorParentType.entries
                    .map { it.name },
            )
        assertThat(distinct("select distinct type_of_storage from kits where id >= 900001"))
            .containsAll(
                cta.app.KitStorageType.entries
                    .map { it.name },
            )

        listOf("referring_organisations", "referring_organisation_contacts", "donor_parents", "donors", "kits").forEach {
            assertThat(distinct("select distinct archived from $it where id >= 900001"))
                .describedAs(it)
                .containsExactlyInAnyOrder("Y", "N")
        }
    }

    @Test
    fun `seed respects the membership, wipe-cert and linkage invariants`() {
        fun n(sql: String) = jdbc.queryForObject(sql, Int::class.java)!!

        val orgs900 = "select count(*) from referring_organisations where id >= 900001 and "
        assertThat(n(orgs900 + "membership_tier <> 'FREE' and membership_end_date is null")).isZero
        assertThat(n(orgs900 + "membership_tier = 'FREE' and membership_end_date is not null")).isZero
        assertThat(n("select count(*) from kits where id >= 900001 and age is null")).isZero
        // Drive-bearing kits past the wipe stage, or assigned to a request, carry a cert or exemption.
        assertThat(
            n(
                """select count(*) from kits where id >= 900001
                   and type in ('LAPTOP','DESKTOP','ALLINONE')
                   and (status not in ('DONATION_NEW','PROCESSING_START') or device_request_id is not null)
                   and wipe_cert_reference is null and wipe_cert_exemption is null""",
            ),
        ).isZero
        assertThat(n("select count(*) from kits where id >= 900001 and donor_id is null")).isPositive
        assertThat(n("select count(*) from kits where id >= 900001 and donor_id is not null")).isPositive
        assertThat(n("select count(*) from kits where id >= 900001 and device_request_id is null")).isPositive
        assertThat(n("select count(*) from kits where id >= 900001 and device_request_id is not null")).isPositive
        assertThat(n("select count(*) from donors where id >= 900001 and donor_parent_id is null")).isPositive
        assertThat(n("select count(*) from donors where id >= 900001 and donor_parent_id is not null")).isPositive
        // Every booking points at a seeded device request.
        assertThat(
            n(
                """select count(*) from delivery_bookings b
                   where b.id >= 900001 and not exists (select 1 from device_requests d where d.id = b.cta_reference)""",
            ),
        ).isZero
        assertThat(n("select count(distinct borough) from device_requests where id >= 900001")).isGreaterThanOrEqualTo(3)
    }

    @Test
    fun `rerunning the seed changes nothing`() {
        val tables = minimumRows.keys
        val before = tables.associateWith { jdbc.queryForObject("select count(*) from $it", Int::class.java) }

        jdbc.execute(ClassPathResource("db/local/R__local_seed.sql").getContentAsString(Charsets.UTF_8))

        assertThat(tables.associateWith { jdbc.queryForObject("select count(*) from $it", Int::class.java) })
            .isEqualTo(before)
    }

    @Test
    @Transactional
    fun `entity mappings load every seeded row`() {
        val allKits = kits.findAll().filter { it.id >= 900001 }
        assertThat(allKits).hasSizeGreaterThanOrEqualTo(36)
        allKits.forEach {
            assertThat(it.attributes).isNotNull
            it.donor?.name
            it.deviceRequest?.id
            it.notes.size
        }

        val requests = deviceRequests.findAll().filter { it.id >= 900001 }
        assertThat(requests).hasSizeGreaterThanOrEqualTo(36)
        requests.forEach {
            it.referringOrganisationContact.referringOrganisation.name
            it.deviceRequestNotes.size
            it.kits.size
        }

        assertThat(donors.findAll().filter { it.id >= 900001 }).hasSizeGreaterThanOrEqualTo(36)
        assertThat(donorParents.findAll().filter { it.id >= 900001 }).hasSizeGreaterThanOrEqualTo(36)
        assertThat(contacts.findAll().filter { it.id >= 900001 }).hasSizeGreaterThanOrEqualTo(36)
        val allOrgs = orgs.findAll().filter { it.id >= 900001 }
        assertThat(allOrgs).hasSizeGreaterThanOrEqualTo(36)
        allOrgs.forEach {
            it.referringOrganisationNotes.size
            it.referringOrganisationContacts.size
        }
    }
}
