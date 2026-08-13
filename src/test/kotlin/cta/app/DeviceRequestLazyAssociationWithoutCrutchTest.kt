package cta.app

import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hibernate.LazyInitializationException
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.security.oauth2.jwt.JwtDecoder

/**
 * The sibling of [DeviceRequestLazyAssociationTest], with
 * `hibernate.enable_lazy_load_no_trans` forced OFF.
 *
 * This is the guard for queue item 3e. The cleanup's whole premise is that the crutch is
 * removable; this test states precisely what removing it costs today, so the work starts from
 * a measured red rather than an assumption. It also documents WHY the crutch cannot simply be
 * deleted: with `open-in-view: false` the entity is detached the moment the repository call
 * returns, so every lazy access on the read path depends on it.
 *
 * The crutch also structurally defeats `@BatchSize`: each access opens its OWN temporary
 * session, and a session holding one collection has nothing to batch against. That inverts
 * the obvious fix order — removing this setting is the PREREQUISITE for batching, not the
 * follow-up to it.
 *
 * If a future change makes the read path fetch inside the transaction (join fetch, entity
 * graph, or a DTO projection), this test is what tells you the dependency is actually gone:
 * it will start failing because no exception is thrown, and that failure is the signal to
 * retire the crutch.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["spring.jpa.properties.hibernate.enable_lazy_load_no_trans=false"],
)
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class DeviceRequestLazyAssociationWithoutCrutchTest {
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
    fun `touching a lazy collection outside a session throws once the crutch is removed`() {
        val id = seedDeviceRequest("nocrutch")

        val loaded = deviceRequests.findById(id).orElseThrow()

        assertThatThrownBy { loaded.deviceRequestNotes.size }
            .`as`(
                "with open-in-view false and the crutch off, a detached lazy access must fail — " +
                    "if this stops throwing, the read path no longer depends on the crutch and " +
                    "it can be retired (queue item 3e)",
            ).isInstanceOf(LazyInitializationException::class.java)
    }

    /**
     * The eager association is the control: it is fetched with the row, so it stays readable
     * when detached. This isolates the failure above to laziness rather than detachment in
     * general — without it, a broken fixture would produce the same red.
     */
    @Test
    fun `the eager referring organisation contact is still readable when detached`() {
        val id = seedDeviceRequest("eager")

        val loaded = deviceRequests.findById(id).orElseThrow()

        assertThat(loaded.referringOrganisationContact.fullName).isEqualTo("Lazy Contact eager")
    }

    /** See [DeviceRequestLazyAssociationTest.seedDeviceRequest] — same shape, own context. */
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
