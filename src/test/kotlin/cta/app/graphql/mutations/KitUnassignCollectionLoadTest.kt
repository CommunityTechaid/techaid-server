package cta.app.graphql.mutations

import cta.app.Donor
import cta.app.DonorRepository
import cta.app.Kit
import cta.app.KitRepository
import cta.app.KitStatus
import cta.app.KitType
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.SessionFactory
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.context.support.WithMockUser

/**
 * Pins that unassigning a kit does not drag its siblings into the session (#153).
 *
 * `updateKit` used to clear an association by calling `Donor.removeKit` / `DeviceRequest.removeKit`,
 * which run `removeIf` over the owning collection and therefore force it to load. Editing one device
 * SELECTed every other kit belonging to the same donor, and every other kit on the same request,
 * only to throw them away. Under #148 that was worse than wasteful — the siblings were then reported
 * dirty and rewritten — but even with #148 fixed it is N pointless reads per edit, and donors can
 * hold a lot of kits.
 *
 * The fix sets the kit's own FK to null instead. What this test guards is the thing that made the
 * change safe: nothing reads the owning collection from this mutation's response. The dashboard's
 * `updateKit` document selects `donor { id name email phoneNumber }` and `deviceRequest { id ... }`,
 * never their `kits`. If a future change starts returning the collection, this test will keep passing
 * while the response silently changes shape — so check the client, not just this file.
 *
 * (The sibling call site, `assignKitsToDeviceRequest`, is deliberately NOT changed: the dashboard
 * reads `kits { id }` from that response to decide whether an assignment worked, so dropping the
 * in-memory update there would report "Device not found" for every successful assignment.)
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["spring.jpa.properties.hibernate.generate_statistics=true"],
)
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class KitUnassignCollectionLoadTest {
    @MockBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var donors: DonorRepository

    @Autowired
    lateinit var kits: KitRepository

    @Autowired
    lateinit var kitMutations: KitMutations

    @Autowired
    lateinit var sessionFactory: SessionFactory

    @Test
    @WithMockUser(authorities = ["write:kits"])
    fun `clearing a kit's donor must not load the donor's other kits`() {
        val donor =
            donors.save(
                Donor(
                    name = "Sibling Donor",
                    email = "siblings@example.com",
                    phoneNumber = "07000000000",
                    postCode = "SE1 1AA",
                    referral = "test",
                ),
            )
        val target = kits.save(Kit(model = "Target", age = 1, donor = donor))
        val siblings = (1..3).map { kits.save(Kit(model = "Sibling $it", age = 1, donor = donor)).id }

        val stats = sessionFactory.statistics
        stats.clear()

        kitMutations.updateKit(unassignInput(target.id))

        assertThat(stats.getCollectionStatistics("cta.app.Donor.kits").fetchCount)
            .`as`(
                "unassigning one kit must not fetch the donor's kits collection — going through " +
                    "Donor.removeKit loads every sibling just to discard them (#153)",
            ).isZero()

        assertThat(kits.findById(target.id).orElseThrow().donor)
            .`as`("the unassignment itself must still happen")
            .isNull()

        assertThat(
            siblings.map {
                kits
                    .findById(it)
                    .orElseThrow()
                    .donor
                    ?.id
            },
        ).`as`("the donor's other kits must be untouched")
            .containsOnly(donor.id)
    }

    /**
     * A full-replace update that clears both associations. `location` is blank on purpose: a
     * non-blank location makes `updateKit` call out to the geocoding service.
     */
    private fun unassignInput(kitId: Long) =
        UpdateKitInput(
            id = kitId,
            type = KitType.OTHER,
            status = KitStatus.DONATION_NEW,
            model = "Target",
            location = "",
            age = 1,
            donorId = null,
            deviceRequestId = null,
        )
}
