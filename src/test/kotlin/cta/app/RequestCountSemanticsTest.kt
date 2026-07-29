package cta.app

import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.security.oauth2.jwt.JwtDecoder

/**
 * Pins that the two `requestCount` @Formula fields mean the SAME thing at both levels of the
 * organisation hierarchy: the number of OPEN device requests.
 *
 * Incident: `ReferringOrganisation.requestCount` counted `status = 'NEW'` while its
 * identically-named sibling `ReferringOrganisationContact.requestCount` counted
 * "not in the terminal statuses". `NEW` is a transient intake state — a request is born NEW
 * with a correlationId and leaves it within minutes, either via markRequestStepsCompleted or
 * the 20-minute decline sweeper — so nothing rests in it. Measured in production 2026-07-28:
 * `NEW` matched ZERO of 6,839 rows, meaning the organisation-level count rendered 0 for all
 * 585 organisations on the dashboard.
 *
 * Both fields are exposed in the GraphQL schema (referringOrganisations.graphqls:6 and
 * referringOrganisationContact.graphqls:11), so this was user-visible.
 *
 * ## REQUEST_COLLECTION_DELIVERY_FAILED is CLOSED (decided 2026-07-29)
 *
 * It was never chosen — the two @Formula fields inherited a three-status closed set while the
 * project's other two definitions of "open" already excluded it:
 *
 *  - `DeviceRequestRepository.requestCount()` native query — excluded it
 *  - `DeliveryAdminQueries.CLOSED_REQUEST_STATUSES` — excluded it
 *  - the two @Formula fields — counted it as open
 *
 * The contact-level field gates `DEVICE_REQUEST_LIMIT`, so counting it meant a referrer whose
 * delivery had failed had that failure held against their 3-open-request allowance — they
 * could be blocked from requesting again because of an operational failure on our side rather
 * than anything they did. The cap fired 13 times in the 30 days to 2026-07-29 in production.
 *
 * All four definitions now agree. If a new status is added to the enum, decide which side it
 * falls on in ALL FOUR places — the lists here will fail to compile until it is classified.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class RequestCountSemanticsTest {
    @MockBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var referringOrganisations: ReferringOrganisationRepository

    @Autowired
    lateinit var referringOrganisationContacts: ReferringOrganisationContactRepository

    @Autowired
    lateinit var deviceRequests: DeviceRequestRepository

    /**
     * Every status the enum defines, exactly once, so the open/closed split is stated
     * exhaustively rather than by example. Adding a status to the enum without deciding which
     * side it falls on will fail to compile here — which is the point.
     */
    private val openStatuses =
        listOf(
            DeviceRequestStatus.NEW,
            DeviceRequestStatus.PROCESSING_EQUALITIES_DATA_COMPLETE,
            DeviceRequestStatus.PROCESSING_COLLECTION_DELIVERY_ARRANGED,
            DeviceRequestStatus.PROCESSING_ON_HOLD,
        )

    private val closedStatuses =
        listOf(
            DeviceRequestStatus.REQUEST_COMPLETED,
            DeviceRequestStatus.REQUEST_CANCELLED,
            DeviceRequestStatus.REQUEST_DECLINED,
            // Closed as of 2026-07-29 — see the note on this test. It was open at the two
            // @Formula sites and closed at the other two definitions of "open"; this is the
            // side the majority already took.
            DeviceRequestStatus.REQUEST_COLLECTION_DELIVERY_FAILED,
        )

    @Test
    fun `both levels count open requests and agree with each other`() {
        val (orgId, contactId) = seedOneRequestPerStatus("agree")

        val org = referringOrganisations.findById(orgId).orElseThrow()
        val contact = referringOrganisationContacts.findById(contactId).orElseThrow()

        assertThat(org.requestCount)
            .`as`(
                "ReferringOrganisation.requestCount must count OPEN requests. Counting " +
                    "status='NEW' renders 0 for every organisation, because NEW is a transient " +
                    "intake state that nothing rests in",
            ).isEqualTo(openStatuses.size)

        assertThat(contact.requestCount)
            .`as`("ReferringOrganisationContact.requestCount must count OPEN requests")
            .isEqualTo(openStatuses.size)

        assertThat(org.requestCount)
            .`as`("the two identically-named fields must not diverge in meaning")
            .isEqualTo(contact.requestCount)
    }

    /**
     * The terminal statuses must not be counted at either level — the complement of the
     * assertion above, so a formula that simply counted everything would still fail.
     */
    @Test
    fun `terminal statuses are excluded at both levels`() {
        val (orgId, contactId) = seedOnly("closed", closedStatuses)

        assertThat(referringOrganisations.findById(orgId).orElseThrow().requestCount).isZero()
        assertThat(referringOrganisationContacts.findById(contactId).orElseThrow().requestCount).isZero()
    }

    /**
     * NEW must still be counted after the fix. It is rare in a production snapshot but it is
     * a legitimate open state, and the fix must not swap one wrong predicate for another.
     */
    @Test
    fun `a request still in NEW counts as open at both levels`() {
        val (orgId, contactId) = seedOnly("new", listOf(DeviceRequestStatus.NEW))

        assertThat(referringOrganisations.findById(orgId).orElseThrow().requestCount).isEqualTo(1)
        assertThat(referringOrganisationContacts.findById(contactId).orElseThrow().requestCount).isEqualTo(1)
    }

    private fun seedOneRequestPerStatus(prefix: String): Pair<Long, Long> = seedOnly(prefix, openStatuses + closedStatuses)

    private fun seedOnly(
        prefix: String,
        statuses: List<DeviceRequestStatus>,
    ): Pair<Long, Long> {
        val org = referringOrganisations.save(ReferringOrganisation(name = "Count Org $prefix"))
        val contact =
            referringOrganisationContacts.save(
                ReferringOrganisationContact(
                    fullName = "Count Contact $prefix",
                    email = "count-$prefix@example.com",
                    phoneNumber = "07000000000",
                    address = "1 Count Street",
                    referringOrganisation = org,
                ),
            )
        statuses.forEachIndexed { index, status ->
            deviceRequests.save(
                DeviceRequest(
                    deviceRequestItems = DeviceRequestItems(laptops = 1),
                    referringOrganisationContact = contact,
                    status = status,
                    isSales = false,
                    clientRef = "$prefix-CR-$index",
                    borough = "Southwark",
                    details = "Household needs a laptop",
                    deviceRequestNeeds = DeviceRequestNeeds(false, false, false),
                ),
            )
        }
        return org.id to contact.id
    }
}
