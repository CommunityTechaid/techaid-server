package cta.app

import cta.app.services.BoroughAvailabilityRules
import cta.app.services.DEFAULT_REQUEST_LIMIT
import cta.app.services.RefereeRequestLimitService
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.security.oauth2.jwt.JwtDecoder

/**
 * Database-backed coverage for [RefereeRequestLimitService] (dashboard #179): the limit comes
 * from the borough group governing the request, an organisation-level exception overrides that
 * group's value, and — the key regression guard — the open-request count is scoped to the same
 * group's boroughs rather than counted globally. Getting the count global while the limit went
 * per-group would make Tower Hamlets' limit of 1 unreachable for any referrer with an open
 * Lambeth request.
 *
 * Runs against the shared embedded-database Spring context (no forked `spring.application.name`,
 * unlike BoroughAvailabilityTest): every test here only reads the seeded borough groups and adds
 * new organisations/contacts/requests/exceptions, never mutating or deleting the seed rows those
 * other tests depend on.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class RefereeRequestLimitTest {
    @MockBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var boroughGroups: BoroughGroupRepository

    @Autowired
    lateinit var limitExceptions: ReferrerLimitExceptionRepository

    @Autowired
    lateinit var referringOrganisations: ReferringOrganisationRepository

    @Autowired
    lateinit var referringOrganisationContacts: ReferringOrganisationContactRepository

    @Autowired
    lateinit var deviceRequests: DeviceRequestRepository

    @Autowired
    lateinit var limitService: RefereeRequestLimitService

    @Autowired
    lateinit var featureFlags: FeatureFlagRepository

    /**
     * Per-group limits only apply while borough-availability-rules is on, and Flyway seeds it OFF
     * so production promotes without a behaviour change. Every test below that asserts per-group
     * behaviour therefore has to switch it on first; the one that asserts the flag-off fallback
     * switches it back within its own body.
     *
     * Restored to the seeded default afterwards because this class shares a Spring context — and
     * so a database — with the other unforked DB-backed tests. Leaving a flag flipped is exactly
     * the kind of cross-test bleed that makes an unrelated suite fail a week later.
     */
    @BeforeEach
    fun enableBoroughRules() = setBoroughRules(enabled = true)

    @AfterEach
    fun restoreSeededDefault() = setBoroughRules(enabled = false)

    private fun setBoroughRules(enabled: Boolean) {
        featureFlags.save(FeatureFlag(key = BoroughAvailabilityRules.FLAG_KEY, enabled = enabled))
    }

    @Test
    fun `limit comes from the group governing the borough`() {
        val referee = contact("Org limit-source", "Referee limit-source")

        val towerHamlets = limitService.resolve(referee, "Tower Hamlets")
        assertThat(towerHamlets.limit).isEqualTo(1)
        assertThat(towerHamlets.scope).isEqualTo("Tower Hamlets")

        val lambeth = limitService.resolve(referee, "Lambeth")
        assertThat(lambeth.limit).isEqualTo(3)
        assertThat(lambeth.scope).isEqualTo("Lambeth & Southwark")
    }

    @Test
    fun `open count is scoped to the resolved group, not global`() {
        val referee = contact("Org scoped-count", "Referee scoped-count")
        repeat(3) { i -> request(referee, DeviceRequestStatus.NEW, "Lambeth", "SC-L-$i") }

        val result = limitService.resolve(refetch(referee), "Tower Hamlets")
        assertThat(result.open).isEqualTo(0)
        assertThat(result.exceeded).isFalse()
    }

    @Test
    fun `within the group the count still bites`() {
        val referee = contact("Org within-group", "Referee within-group")
        repeat(3) { i -> request(referee, DeviceRequestStatus.NEW, "Lambeth", "WG-L-$i") }

        val result = limitService.resolve(refetch(referee), "Lambeth")
        assertThat(result.open).isEqualTo(3)
        assertThat(result.limit).isEqualTo(3)
        assertThat(result.exceeded).isTrue()
    }

    @Test
    fun `Lambeth and Southwark count together as one group`() {
        val referee = contact("Org lambeth-southwark", "Referee lambeth-southwark")
        repeat(2) { i -> request(referee, DeviceRequestStatus.NEW, "Lambeth", "LS-L-$i") }
        request(referee, DeviceRequestStatus.NEW, "Southwark", "LS-S-0")

        val fresh = refetch(referee)
        assertThat(limitService.resolve(fresh, "Lambeth").open).isEqualTo(3)
        assertThat(limitService.resolve(fresh, "Southwark").open).isEqualTo(3)
    }

    @Test
    fun `closed statuses do not count, and a failed collection or delivery does`() {
        val closedReferee = contact("Org closed-statuses", "Referee closed-statuses")
        CLOSED_REQUEST_STATUSES.forEachIndexed { i, status ->
            request(closedReferee, status, "Tower Hamlets", "CS-$i")
        }
        assertThat(limitService.resolve(refetch(closedReferee), "Tower Hamlets").open)
            .`as`("closed statuses must not count as open")
            .isEqualTo(0)

        val failedReferee = contact("Org failed-delivery", "Referee failed-delivery")
        request(failedReferee, DeviceRequestStatus.REQUEST_COLLECTION_DELIVERY_FAILED, "Tower Hamlets", "FD-0")
        assertThat(limitService.resolve(refetch(failedReferee), "Tower Hamlets").open)
            .`as`("a failed collection/delivery must count as open")
            .isEqualTo(1)
    }

    @Test
    fun `an organisation exception overrides the group value`() {
        val towerHamletsGroup = boroughGroups.findAll().first { it.name == "Tower Hamlets" }

        val withoutException = contact("Org no-exception", "Referee no-exception")
        assertThat(limitService.resolve(withoutException, "Tower Hamlets").limit).isEqualTo(1)

        val organisationWithException = referringOrganisations.save(ReferringOrganisation(name = "Org with-exception"))
        limitExceptions.save(
            ReferrerLimitException(
                referringOrganisation = organisationWithException,
                group = towerHamletsGroup,
                maxPerReferee = 5,
            ),
        )
        val withException =
            referringOrganisationContacts.save(
                ReferringOrganisationContact(
                    fullName = "Referee with-exception",
                    email = "referee-with-exception@example.com",
                    phoneNumber = "07000000000",
                    address = "1 Test Street",
                    referringOrganisation = organisationWithException,
                ),
            )
        assertThat(limitService.resolve(withException, "Tower Hamlets").limit).isEqualTo(5)
    }

    @Test
    fun `the exception is scoped to its organisation and its group`() {
        val towerHamletsGroup = boroughGroups.findAll().first { it.name == "Tower Hamlets" }

        val organisationWithException = referringOrganisations.save(ReferringOrganisation(name = "Org scoped-exception"))
        limitExceptions.save(
            ReferrerLimitException(
                referringOrganisation = organisationWithException,
                group = towerHamletsGroup,
                maxPerReferee = 5,
            ),
        )

        // A contact from a DIFFERENT organisation must get the group value, not the exception.
        val otherOrgReferee = contact("Org other-org", "Referee other-org")
        assertThat(limitService.resolve(otherOrgReferee, "Tower Hamlets").limit).isEqualTo(1)

        // The exception is on the Tower Hamlets group; it must not change the Lambeth limit for
        // the same organisation.
        val exceptionOrgReferee =
            referringOrganisationContacts.save(
                ReferringOrganisationContact(
                    fullName = "Referee scoped-exception",
                    email = "referee-scoped-exception@example.com",
                    phoneNumber = "07000000000",
                    address = "1 Test Street",
                    referringOrganisation = organisationWithException,
                ),
            )
        assertThat(limitService.resolve(exceptionOrgReferee, "Lambeth").limit).isEqualTo(3)
        assertThat(limitService.resolve(exceptionOrgReferee, "Tower Hamlets").limit).isEqualTo(5)
    }

    @Test
    fun `unknown or blank borough falls back to the default limit and the contact's total open count`() {
        val referee = contact("Org fallback", "Referee fallback")
        repeat(2) { i -> request(referee, DeviceRequestStatus.NEW, "Lambeth", "FB-L-$i") }
        request(referee, DeviceRequestStatus.NEW, "Tower Hamlets", "FB-T-0")
        request(referee, DeviceRequestStatus.NEW, "Lewisham", "FB-U-0")

        val fresh = refetch(referee)
        val expectedOpen = fresh.requestCount.toLong()
        assertThat(expectedOpen).isEqualTo(4)

        for (borough in listOf(null, "", "Lewisham")) {
            val result = limitService.resolve(fresh, borough)
            assertThat(result.limit).`as`("borough=$borough").isEqualTo(DEFAULT_REQUEST_LIMIT)
            assertThat(result.scope).`as`("borough=$borough").isEqualTo("overall")
            assertThat(result.open).`as`("borough=$borough").isEqualTo(expectedOpen)
        }
    }

    @Test
    fun `borough matching is case- and whitespace-insensitive`() {
        val referee = contact("Org case-insensitive", "Referee case-insensitive")

        for (borough in listOf("tower hamlets", "  Tower Hamlets  ", "TOWER HAMLETS")) {
            val result = limitService.resolve(referee, borough)
            assertThat(result.scope).`as`("borough=[$borough]").isEqualTo("Tower Hamlets")
            assertThat(result.limit).`as`("borough=[$borough]").isEqualTo(1)
        }
    }

    @Test
    fun `exceeded is a greater-than-or-equal boundary`() {
        val referee = contact("Org boundary", "Referee boundary")

        assertThat(limitService.resolve(refetch(referee), "Tower Hamlets").exceeded).isFalse()

        request(referee, DeviceRequestStatus.NEW, "Tower Hamlets", "BD-0")
        assertThat(limitService.resolve(refetch(referee), "Tower Hamlets").exceeded).isTrue()
    }

    @Test
    fun `with the flag off the pre-config global cap applies, borough or no borough`() {
        val referee = contact("Org flag-off", "Referee flag-off")
        repeat(2) { i -> request(referee, DeviceRequestStatus.NEW, "Lambeth", "OFF-L-$i") }
        request(referee, DeviceRequestStatus.NEW, "Tower Hamlets", "OFF-T-0")

        setBoroughRules(enabled = false)
        val fresh = refetch(referee)

        // Tower Hamlets' group says limit 1 and would count only its own borough. Off, none of
        // that is allowed to reach a referrer: the answer is the global cap counted against every
        // open request the referee holds, which is what production does today.
        val towerHamlets = limitService.resolve(fresh, "Tower Hamlets")
        assertThat(towerHamlets.limit).isEqualTo(DEFAULT_REQUEST_LIMIT)
        assertThat(towerHamlets.scope).isEqualTo("overall")
        assertThat(towerHamlets.open).isEqualTo(3)
        assertThat(towerHamlets.exceeded).isTrue()

        // Lambeth resolves to a group whose limit happens to also be 3, so the limit alone cannot
        // tell the two paths apart — the scope and the global count are what prove which ran.
        val lambeth = limitService.resolve(fresh, "Lambeth")
        assertThat(lambeth.scope).isEqualTo("overall")
        assertThat(lambeth.open).isEqualTo(3)
    }

    @Test
    fun `a borough stored with odd casing or padding still counts towards its group`() {
        val referee = contact("Org stored-casing", "Referee stored-casing")
        request(referee, DeviceRequestStatus.NEW, "lambeth", "SCS-0")
        request(referee, DeviceRequestStatus.NEW, "  SOUTHWARK  ", "SCS-1")
        request(referee, DeviceRequestStatus.NEW, "Lambeth", "SCS-2")

        // groupFor already resolves these to the Lambeth & Southwark group; the count has to agree
        // with it, or a referee gains headroom purely from how their borough happened to be typed.
        val result = limitService.resolve(refetch(referee), "Lambeth")
        assertThat(result.scope).isEqualTo("Lambeth & Southwark")
        assertThat(result.open).isEqualTo(3)
        assertThat(result.exceeded).isTrue()
    }

    private fun contact(
        organisationName: String,
        contactName: String,
    ): ReferringOrganisationContact {
        val organisation = referringOrganisations.save(ReferringOrganisation(name = organisationName))
        return referringOrganisationContacts.save(
            ReferringOrganisationContact(
                fullName = contactName,
                email = "${contactName.lowercase().replace(" ", "-")}@example.com",
                phoneNumber = "07000000000",
                address = "1 Test Street",
                referringOrganisation = organisation,
            ),
        )
    }

    private fun request(
        contact: ReferringOrganisationContact,
        status: DeviceRequestStatus,
        borough: String,
        clientRef: String,
    ): DeviceRequest =
        deviceRequests.save(
            DeviceRequest(
                deviceRequestItems = DeviceRequestItems(laptops = 1),
                referringOrganisationContact = contact,
                status = status,
                isSales = false,
                clientRef = clientRef,
                borough = borough,
                details = "Household needs a laptop",
                deviceRequestNeeds = DeviceRequestNeeds(false, false, false),
            ),
        )

    /** Re-fetches the contact so its `requestCount` `@Formula` reflects requests saved since. */
    private fun refetch(contact: ReferringOrganisationContact): ReferringOrganisationContact =
        referringOrganisationContacts.findById(contact.id).orElseThrow()
}
