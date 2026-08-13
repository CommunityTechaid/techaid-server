package cta.app

import cta.app.graphql.mutations.BulkKitAssignmentInput
import cta.app.graphql.mutations.DeviceRequestMutations
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.SessionFactory
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Pins that a kit row is only written when the kit itself actually changed (#148).
 *
 * The bug: [KitAttributes] is mapped to a `jsonb` column, and Hibernate treats a JSON-mapped
 * attribute as mutable — it deep-copies the value into the load-time snapshot and then dirty-checks
 * it. With no value-based `equals` that comparison falls through to identity, the snapshot copy is
 * never the same instance as the live value, and so EVERY kit loaded into a read-write transaction
 * is reported dirty. `attributes` is never null, so this applied to every kit without exception.
 *
 * Why it mattered: mutations that touch a device request's `kits` collection (assigning a kit,
 * clearing a kit's request or donor in `updateKit`) pull every sibling kit into the persistence
 * context. Each one was then rewritten at commit, bumping `updated_at` — so `kits.updated_at` stopped
 * meaning "when this device last changed" and became "when anything on its request last changed".
 * Reported against device request 69, where every linked kit carried the same date.
 *
 * Reads were never affected: Spring Data's query methods are `@Transactional(readOnly = true)`, which
 * sets `FlushMode.MANUAL`. Only the read-write mutation controllers flushed the spurious change.
 *
 * The first test pins the root cause directly; the second pins the behaviour operators actually see.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["spring.jpa.properties.hibernate.generate_statistics=true"],
)
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class KitTimestampIsolationTest {
    @MockBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var referringOrganisations: ReferringOrganisationRepository

    @Autowired
    lateinit var referringOrganisationContacts: ReferringOrganisationContactRepository

    @Autowired
    lateinit var deviceRequests: DeviceRequestRepository

    @Autowired
    lateinit var kits: KitRepository

    @Autowired
    lateinit var deviceRequestMutations: DeviceRequestMutations

    @Autowired
    lateinit var txManager: PlatformTransactionManager

    @Autowired
    lateinit var sessionFactory: SessionFactory

    @PersistenceContext
    lateinit var em: EntityManager

    @Test
    fun `loading a kit and changing nothing must not write it`() {
        val seeded = seed("dirty")
        val kitId = seeded.kitIds.first()

        val stats = sessionFactory.statistics
        stats.clear()
        TransactionTemplate(txManager).execute {
            em.find(Kit::class.java, kitId)
            em.flush()
        }

        assertThat(stats.entityUpdateCount)
            .`as`(
                "loading a kit into a read-write transaction and changing nothing must issue no " +
                    "writes at all. A non-zero count means Hibernate considers some property dirty " +
                    "on load — check that KitAttributes still has a value-based equals/hashCode",
            ).isZero()
    }

    @Test
    @WithMockUser(authorities = ["write:organisations"])
    fun `assigning a kit to a request must not rewrite the kits already on it`() {
        val seeded = seed("assign")
        val before = timestampsOf(seeded.kitIds)

        deviceRequestMutations.assignKitsToDeviceRequest(
            BulkKitAssignmentInput(deviceRequestId = seeded.requestId, kitIds = listOf(seeded.spareKitId)),
        )

        assertThat(
            kits
                .findById(seeded.spareKitId)
                .orElseThrow()
                .deviceRequest
                ?.id,
        ).`as`("the assignment itself must still happen")
            .isEqualTo(seeded.requestId)

        assertThat(timestampsOf(seeded.kitIds))
            .`as`(
                "the kits already on the request were not touched, so their updated_at must be " +
                    "untouched too — see #148",
            ).isEqualTo(before)
    }

    private fun timestampsOf(kitIds: List<Long>) = kitIds.associateWith { kits.findById(it).orElseThrow().updatedAt }

    private class Seeded(
        val requestId: Long,
        val kitIds: List<Long>,
        val spareKitId: Long,
    )

    /**
     * [Kit.age] is set explicitly: the column is NOT NULL in the legacy schema even though the
     * entity declares it nullable.
     */
    private fun seed(prefix: String): Seeded {
        val org = referringOrganisations.save(ReferringOrganisation(name = "Timestamp Org $prefix"))
        val contact =
            referringOrganisationContacts.save(
                ReferringOrganisationContact(
                    fullName = "Timestamp Contact $prefix",
                    email = "timestamp-$prefix@example.com",
                    phoneNumber = "07000000000",
                    address = "1 Timestamp Street",
                    referringOrganisation = org,
                ),
            )
        val request =
            deviceRequests.save(
                DeviceRequest(
                    deviceRequestItems = DeviceRequestItems(laptops = 3),
                    referringOrganisationContact = contact,
                    isSales = false,
                    clientRef = "$prefix-TS-1",
                    borough = "Southwark",
                    details = "Household needs laptops",
                    deviceRequestNeeds = DeviceRequestNeeds(false, false, false),
                ),
            )
        val existing = (1..3).map { kits.save(Kit(model = "Model $it", age = 1, deviceRequest = request)).id }
        val spare = kits.save(Kit(model = "Spare", age = 1)).id
        return Seeded(request.id, existing, spare)
    }
}
