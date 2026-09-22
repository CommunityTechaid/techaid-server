package cta.app.graphql.queries

import cta.app.Donor
import cta.app.DonorRepository
import cta.app.Kit
import cta.app.KitRepository
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Pins what the device history shows for a relation whose **target** is not audited.
 *
 * `Kit.donor` and `Kit.deviceRequest` carry
 * `@Audited(targetAuditMode = RelationTargetAuditMode.NOT_AUDITED)`, as do seven other sites
 * across Kit, Donor, DonorParent, ReferringOrganisation, ReferringOrganisationContact,
 * DeviceRequest and Note. `kitAudits` hands the whole `Kit` entity to GraphQL
 * (`KitRevision.entity: Kit`), so whatever Envers materialises for those associations is directly
 * visible in the dashboard's Devices > History tab.
 *
 * Nothing covered this before, and a green suite proves nothing about it: `NOT_AUDITED` changes
 * what an audit query RETURNS, not whether it compiles or runs. The Hibernate 7 upgrade was
 * flagged as possibly flipping it from ignored to respected.
 *
 * THE SEMANTICS, measured rather than assumed - the two halves pull in opposite directions:
 *
 *  - The foreign key IS audited, so each revision reports the donor that was assigned AT that
 *    revision. Reassigning a device is visible in its history, which is the thing that matters
 *    most about a device's past.
 *  - The TARGET is not audited, so the Donor is then loaded from the live `donors` table. Its own
 *    fields are present-day values. Rename a donor and every historic revision shows the new name.
 *
 * So the history answers "which donor was this assigned to at the time" correctly, and answers
 * "what was that donor called at the time" with today's answer. Anything built on this trail must
 * not imply the second.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class KitAuditDonorRelationTest {
    @MockitoBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var kits: KitRepository

    @Autowired
    lateinit var donors: DonorRepository

    @Autowired
    lateinit var auditQueries: KitAuditTrailQueries

    @Autowired
    lateinit var txManager: PlatformTransactionManager

    private fun donor(name: String) =
        donors.save(
            Donor(
                postCode = "SE1 1AA",
                phoneNumber = "07000000000",
                email = name.lowercase().replace(" ", "") + "@example.com",
                name = name,
                referral = "test",
            ),
        )

    /**
     * Read associations INSIDE a transaction deliberately. `Kit.donor` is LAZY, so touching it
     * after `kitAudits()` returns drags in `open-in-view` (false in the shipped config) and
     * `hibernate.enable_lazy_load_no_trans` - a different question from this one. Holding a
     * transaction open removes that variable.
     */
    private fun donorNamesPerRevision(kitId: Long): List<String?> =
        inOneTransactionReturning {
            auditQueries.kitAudits(kitId).map { it.entity.donor?.name }
        }

    @Test
    @WithMockUser(authorities = ["read:kits"])
    fun `each revision reports the donor assigned at that revision, because the foreign key is audited`() {
        val first = donor("Donor One")
        val second = donor("Donor Two")

        val kit = kits.save(Kit(model = "Reassigned", age = 1, donor = first))
        inOneTransaction {
            kits.findById(kit.id).orElseThrow().donor = donors.findById(second.id).orElseThrow()
        }

        assertThat(donorNamesPerRevision(kit.id))
            .`as`(
                "the donor_id column is audited, so reassignment is visible in the device history - " +
                    "if this ever collapses to one repeated name, the trail has silently stopped " +
                    "recording assignments and the History tab is lying",
            ).containsExactly("Donor One", "Donor Two")
    }

    @Test
    @WithMockUser(authorities = ["read:kits"])
    fun `a renamed donor is shown with its present-day name on every historic revision`() {
        val original = donor("Before Rename")
        val kit = kits.save(Kit(model = "Renamed donor", age = 1, donor = original))

        inOneTransaction {
            donors.findById(original.id).orElseThrow().name = "After Rename"
        }

        assertThat(donorNamesPerRevision(kit.id))
            .`as`(
                "RelationTargetAuditMode.NOT_AUDITED: the Donor is resolved from the live table, so " +
                    "its own fields are current, never historic. The device history cannot answer " +
                    "what a donor was called at the time, and nothing built on it should claim to.",
            ).containsOnly("After Rename")
    }

    private fun inOneTransaction(block: () -> Unit) {
        TransactionTemplate(txManager).execute { block() }
    }

    private fun <T> inOneTransactionReturning(block: () -> T): T = TransactionTemplate(txManager).execute { block() }!!
}
