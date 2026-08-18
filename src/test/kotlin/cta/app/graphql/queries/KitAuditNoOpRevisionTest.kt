package cta.app.graphql.queries

import cta.app.Kit
import cta.app.KitRepository
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Gives the device history enough information to tell a real change from #148 collateral.
 *
 * 83,179 rows in production's `kit_audit_trail` are revisions where a device was rewritten
 * untouched, because it happened to be a sibling in someone else's transaction. Rendered in the
 * Devices > History tab they are indistinguishable from real edits: same revision number, same
 * timestamp, same values. The trail is 60% noise and the noise arrives in bursts of up to 2,927.
 *
 * The naive fix - have the dashboard compare consecutive rows - is wrong, and that is the reason
 * this lives on the server. That component queries only model, status, serialNo and subStatus, so
 * a revision that changed `location`, `lotId`, `wipeCertReference`, the donor or the device
 * request would compare equal and be hidden. Assignments are the most interesting thing in a
 * device's history and would be the first casualty. Here every column is in hand.
 *
 * TWO FIELDS, NOT ONE, and the second is what makes it safe to hide anything:
 *
 *  - `changedNothingAudited` alone cannot separate collateral from a genuine KitAttributes edit.
 *    KitAttributes is @NotAudited, so editing notes or credentials bumps updated_at and lands a
 *    revision that looks identical to collateral. Production runs 2-55 of those a day - they are
 *    real work by real people and hiding them would be a regression, not a cleanup.
 *  - `siblingKitsInRevision` is the discriminator. Collateral always arrives with siblings; a
 *    lone attributes edit never does.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class KitAuditNoOpRevisionTest {
    @MockBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var kits: KitRepository

    @Autowired
    lateinit var auditQueries: KitAuditTrailQueries

    @Autowired
    lateinit var txManager: PlatformTransactionManager

    @Test
    @WithMockUser(authorities = ["read:kits"])
    fun `a kit rewritten as a sibling is flagged as changing nothing, with the burst size`() {
        val target = kits.save(Kit(model = "Target", age = 1))
        val bystander = kits.save(Kit(model = "Bystander", age = 1))

        // One transaction, one Envers revision, two kits: exactly the shape #148 produced.
        // The bystander's only edit is to KitAttributes, which is @NotAudited - so its audit row
        // records a write with nothing to show for it.
        inOneTransaction {
            kits.findById(target.id).orElseThrow().model = "Target renamed"
            kits
                .findById(bystander.id)
                .orElseThrow()
                .attributes.notes = "touched, but not audited"
        }

        val bystanderLatest = auditQueries.kitAudits(bystander.id).last()
        assertThat(bystanderLatest.changedNothingAudited)
            .`as`("nothing but updated_at moved on the bystander")
            .isTrue()
        assertThat(bystanderLatest.siblingKitsInRevision)
            .`as`("the discriminator: it was written alongside the kit someone actually edited")
            .isEqualTo(1)

        val targetLatest = auditQueries.kitAudits(target.id).last()
        assertThat(targetLatest.changedNothingAudited)
            .`as`("the target's model really did change - this row must never be hidden")
            .isFalse()
        assertThat(targetLatest.siblingKitsInRevision).isEqualTo(1)
    }

    @Test
    @WithMockUser(authorities = ["read:kits"])
    fun `a lone attributes edit changes nothing audited but has no siblings`() {
        val kit = kits.save(Kit(model = "Solo", age = 1))

        inOneTransaction {
            kits
                .findById(kit.id)
                .orElseThrow()
                .attributes.notes = "a real note by a real person"
        }

        val latest = auditQueries.kitAudits(kit.id).last()
        assertThat(latest.changedNothingAudited)
            .`as`("KitAttributes is @NotAudited, so this looks the same as collateral")
            .isTrue()
        assertThat(latest.siblingKitsInRevision)
            .`as`("but nothing else was written with it, so it is somebody's real edit - keep it")
            .isZero()
    }

    @Test
    @WithMockUser(authorities = ["read:kits"])
    fun `the first revision of a kit is never reported as changing nothing`() {
        val kit = kits.save(Kit(model = "Fresh", age = 1))

        val first = auditQueries.kitAudits(kit.id).first()
        assertThat(first.changedNothingAudited)
            .`as`("there is no predecessor to compare against; a creation is not a no-op")
            .isFalse()
        assertThat(first.siblingKitsInRevision).isZero()
    }

    private fun inOneTransaction(block: () -> Unit) {
        TransactionTemplate(txManager).execute { block() }
    }
}
