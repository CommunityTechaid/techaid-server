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
            // Deliberately open: a failed collection/delivery still needs staff action.
            // See the note on this test for the argument that this should be revisited.
            DeviceRequestStatus.REQUEST_COLLECTION_DELIVERY_FAILED,
        )

    private val closedStatuses =
        listOf(
            DeviceRequestStatus.REQUEST_COMPLETED,
            DeviceRequestStatus.REQUEST_CANCELLED,
            DeviceRequestStatus.REQUEST_DECLINED,
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
