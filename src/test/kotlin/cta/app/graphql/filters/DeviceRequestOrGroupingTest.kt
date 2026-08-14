package cta.app.graphql.filters

import cta.app.DeviceRequest
import cta.app.DeviceRequestItems
import cta.app.DeviceRequestNeeds
import cta.app.DeviceRequestRepository
import cta.app.DeviceRequestStatus
import cta.app.ReferringOrganisation
import cta.app.ReferringOrganisationContact
import cta.app.ReferringOrganisationContactRepository
import cta.app.ReferringOrganisationRepository
import cta.graphql.BooleanComparison
import cta.graphql.TextComparison
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.TestPropertySource

/**
 * Database-backed regression coverage for the `OR`-swallows-scalars bug fixed in
 * [DeviceRequestWhereInput.build] and [DeviceRequestItemsWhereInput.build] (dashboard #180).
 *
 * `BooleanBuilder.or()` disjoins against everything already accumulated on the builder, so a
 * where input mixing scalar fields with `OR` used to lose the scalars entirely:
 * `{isSales: {_in:[false]}, OR: [a, b]}` built `isSales = false OR a OR b` instead of
 * `isSales = false AND (a OR b)`. This is invisible at the predicate-shape level (the predicate
 * still "has" all the right clauses, just wired with the wrong precedence) so these tests run
 * the built predicate through [DeviceRequestRepository.findAll] and assert on the rows actually
 * returned, matching how the dashboard's "Not recorded" borough filter
 * (`{isSales:..., OR:[{borough:{_in:['']}}, {borough:{_is_null:true}}]}`) broke in production.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
// Each test queries the whole device_requests table with no per-test scoping filter (that's
// the point - it's exercising exactly the where-input shapes the dashboard sends), and each
// test also clears that table first so an earlier test's rows can't leak into a later one's
// results (see the @BeforeEach below). A distinct `spring.application.name` forks this class
// its own Spring context and embedded Postgres so those deletes can't collide with data other
// test classes leave in the shared default context - the same isolation pattern used by
// BoroughAvailabilityTest/DeliveryBookingProtectionTest/DeliveryBookingConcurrencyTest.
@TestPropertySource(properties = ["spring.application.name=techaid-server-device-request-or-grouping-test"])
class DeviceRequestOrGroupingTest {
    @MockBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var referringOrganisations: ReferringOrganisationRepository

    @Autowired
    lateinit var referringOrganisationContacts: ReferringOrganisationContactRepository

    @Autowired
    lateinit var deviceRequests: DeviceRequestRepository

    @BeforeEach
    fun clean() {
        deviceRequests.deleteAll()
        referringOrganisationContacts.deleteAll()
        referringOrganisations.deleteAll()
    }

    private fun contact(prefix: String): ReferringOrganisationContact {
        val org = referringOrganisations.save(ReferringOrganisation(name = "Or Group Org $prefix"))
        return referringOrganisationContacts.save(
            ReferringOrganisationContact(
                fullName = "Or Group Contact $prefix",
                email = "or-group-$prefix@example.com",
                phoneNumber = "07000000000",
                address = "1 Or Group Street",
                referringOrganisation = org,
            ),
        )
    }

    private fun request(
        clientRef: String,
        isSales: Boolean,
        borough: String?,
    ): DeviceRequest =
        deviceRequests.save(
            DeviceRequest(
                deviceRequestItems = DeviceRequestItems(laptops = 1),
                referringOrganisationContact = contact(clientRef),
                status = DeviceRequestStatus.NEW,
                isSales = isSales,
                clientRef = clientRef,
                borough = borough,
                details = "Household needs a laptop",
                deviceRequestNeeds = DeviceRequestNeeds(false, false, false),
            ),
        )

    /** Seeds the five fixtures the plan describes and returns them keyed by their letter. */
    private fun seedFixtures(prefix: String): Map<String, DeviceRequest> =
        mapOf(
            "A" to request("$prefix-A", isSales = false, borough = "Tower Hamlets"),
            "B" to request("$prefix-B", isSales = false, borough = ""),
            "C" to request("$prefix-C", isSales = false, borough = null),
            "D" to request("$prefix-D", isSales = true, borough = ""),
            "E" to request("$prefix-E", isSales = false, borough = "Lambeth"),
        )

    private fun idsOf(vararg requests: DeviceRequest?): Set<Long> = requests.filterNotNull().map { it.id }.toSet()

    @Test
    fun `scalar filter alongside OR narrows instead of being swallowed`() {
        val fixtures = seedFixtures("regression")

        val where =
            DeviceRequestWhereInput(
                isSales = BooleanComparison(_in = mutableListOf(false)),
                OR =
                    mutableListOf(
                        DeviceRequestWhereInput(borough = TextComparison(_in = mutableListOf(""))),
                        DeviceRequestWhereInput(borough = TextComparison(_is_null = true)),
                    ),
            )

        val results = deviceRequests.findAll(where.build())

        assertThat(results.map { it.id }.toSet())
            .`as`("must be exactly B and C - not A, not D (isSales=true), not E")
            .isEqualTo(idsOf(fixtures["B"], fixtures["C"]))
    }

    @Test
    fun `named borough plus blank alternatives still respects the isSales scalar`() {
        val fixtures = seedFixtures("named")

        val where =
            DeviceRequestWhereInput(
                isSales = BooleanComparison(_in = mutableListOf(false)),
                OR =
                    mutableListOf(
                        DeviceRequestWhereInput(borough = TextComparison(_in = mutableListOf("Tower Hamlets"))),
                        DeviceRequestWhereInput(borough = TextComparison(_in = mutableListOf(""))),
                        DeviceRequestWhereInput(borough = TextComparison(_is_null = true)),
                    ),
            )

        val results = deviceRequests.findAll(where.build())

        assertThat(results.map { it.id }.toSet())
            .isEqualTo(idsOf(fixtures["A"], fixtures["B"], fixtures["C"]))
    }

    @Test
    fun `OR with no sibling scalars is still a plain disjunction`() {
        val fixtures = seedFixtures("plain-or")

        val where =
            DeviceRequestWhereInput(
                OR =
                    mutableListOf(
                        DeviceRequestWhereInput(borough = TextComparison(_in = mutableListOf("Lambeth"))),
                        DeviceRequestWhereInput(borough = TextComparison(_in = mutableListOf("Tower Hamlets"))),
                    ),
            )

        val results = deviceRequests.findAll(where.build())

        assertThat(results.map { it.id }.toSet())
            .isEqualTo(idsOf(fixtures["A"], fixtures["E"]))
    }

    @Test
    fun `AND alongside OR composes correctly`() {
        val fixtures = seedFixtures("and-or")

        val where =
            DeviceRequestWhereInput(
                AND =
                    mutableListOf(
                        DeviceRequestWhereInput(isSales = BooleanComparison(_in = mutableListOf(false))),
                    ),
                OR =
                    mutableListOf(
                        DeviceRequestWhereInput(borough = TextComparison(_in = mutableListOf(""))),
                        DeviceRequestWhereInput(borough = TextComparison(_is_null = true)),
                    ),
            )

        val results = deviceRequests.findAll(where.build())

        assertThat(results.map { it.id }.toSet())
            .isEqualTo(idsOf(fixtures["B"], fixtures["C"]))
    }

    @Test
    fun `plain scalar filters with no OR still work as a control`() {
        val fixtures = seedFixtures("no-or")

        val where = DeviceRequestWhereInput(isSales = BooleanComparison(_in = mutableListOf(true)))

        val results = deviceRequests.findAll(where.build())

        assertThat(results.map { it.id }.toSet())
            .isEqualTo(idsOf(fixtures["D"]))
    }

    @Test
    fun `an empty OR list changes nothing`() {
        val fixtures = seedFixtures("empty-or")

        val where =
            DeviceRequestWhereInput(
                isSales = BooleanComparison(_in = mutableListOf(false)),
                OR = mutableListOf(),
            )

        val results = deviceRequests.findAll(where.build())

        assertThat(results.map { it.id }.toSet())
            .isEqualTo(idsOf(fixtures["A"], fixtures["B"], fixtures["C"], fixtures["E"]))
    }
}
