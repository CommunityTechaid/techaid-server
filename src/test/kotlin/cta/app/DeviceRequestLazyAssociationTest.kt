package cta.app

import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.Hibernate
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.security.oauth2.jwt.JwtDecoder

/**
 * Characterises the JPA session boundary under the REAL production configuration
 * (`open-in-view: false` + `enable_lazy_load_no_trans: true`, both inherited from the main
 * application.yml since issue #105).
 *
 * Why this exists: fixing #105 revealed that the suite had no test touching a lazy
 * association outside a Hibernate session, so the session-boundary settings stayed invisible
 * to it even once they applied — nothing would have noticed a regression here. This test and
 * its sibling [DeviceRequestLazyAssociationWithoutCrutchTest] close that gap, and are the
 * prerequisite for the `enable_lazy_load_no_trans` cleanup (queue item 3e).
 *
 * What it pins: after a repository call returns to code with no transaction of its own the
 * entity IS detached (collections come back uninitialised), and a lazy access nonetheless
 * succeeds — because `enable_lazy_load_no_trans` opens a fresh temporary session per access.
 * That crutch is load-bearing, not a safety net; the sibling test proves it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class DeviceRequestLazyAssociationTest {
    @MockBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var referringOrganisations: ReferringOrganisationRepository

    @Autowired
    lateinit var referringOrganisationContacts: ReferringOrganisationContactRepository

    @Autowired
    lateinit var deviceRequests: DeviceRequestRepository

    @Autowired
    lateinit var deviceRequestNotes: DeviceRequestNoteRepository

    @Autowired
    lateinit var kits: KitRepository

    @Test
    fun `lazy collections come back uninitialised once the repository transaction has ended`() {
        val id = seedDeviceRequest("uninit")

        val loaded = deviceRequests.findById(id).orElseThrow()

        assertThat(Hibernate.isInitialized(loaded.deviceRequestNotes))
            .`as`(
                "deviceRequestNotes should still be a lazy proxy here. If this is true the " +
                    "session is being held open past the repository call, and the sibling " +
                    "without-crutch test would prove nothing",
            ).isFalse()
        assertThat(Hibernate.isInitialized(loaded.kits))
            .`as`("kits should still be a lazy proxy here")
            .isFalse()
    }

    @Test
    fun `lazy access outside a session succeeds only because of enable_lazy_load_no_trans`() {
        val id = seedDeviceRequest("access")

        val loaded = deviceRequests.findById(id).orElseThrow()

        // No transaction and no open-in-view: this works purely on the temporary session the
        // crutch opens per access. Remove the crutch and it throws — see the sibling test.
        assertThat(loaded.deviceRequestNotes).hasSize(2)
        assertThat(loaded.kits).hasSize(2)
    }

    /**
     * Each save runs in SimpleJpaRepository's own transaction, so by the time this returns
     * every row is committed and nothing is left attached to a session.
     * [Kit.age] is set explicitly: the column is NOT NULL in the legacy schema even though
     * the entity declares it nullable.
     */
    private fun seedDeviceRequest(prefix: String): Long {
        val org = referringOrganisations.save(ReferringOrganisation(name = "Lazy Org $prefix"))
        val contact =
            referringOrganisationContacts.save(
                ReferringOrganisationContact(
                    fullName = "Lazy Contact $prefix",
                    email = "lazy-$prefix@example.com",
                    phoneNumber = "07000000000",
                    address = "1 Lazy Street",
                    referringOrganisation = org,
                ),
            )
        val request =
            deviceRequests.save(
                DeviceRequest(
                    deviceRequestItems = DeviceRequestItems(laptops = 1),
                    referringOrganisationContact = contact,
                    isSales = false,
                    clientRef = "$prefix-LAZY-1",
                    borough = "Southwark",
                    details = "Household needs a laptop",
                    deviceRequestNeeds = DeviceRequestNeeds(false, false, false),
                ),
            )
        (1..2).forEach { n ->
            deviceRequestNotes.save(DeviceRequestNote(content = "Note $n", deviceRequest = request))
            kits.save(Kit(model = "Model $n", age = 1, deviceRequest = request))
        }
        return request.id
    }
}
