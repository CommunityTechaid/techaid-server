package cta.app.graphql

import cta.app.DeviceRequest
import cta.app.DeviceRequestItems
import cta.app.DeviceRequestNeeds
import cta.app.DeviceRequestRepository
import cta.app.MembershipOverLimitMode
import cta.app.MembershipPeriod
import cta.app.MembershipState
import cta.app.MembershipTier
import cta.app.MembershipUsageBand
import cta.app.ReferringOrganisation
import cta.app.ReferringOrganisationContact
import cta.app.ReferringOrganisationContactRepository
import cta.app.ReferringOrganisationRepository
import cta.app.services.EffectiveMembership
import cta.app.services.MembershipAllowanceService
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate

/**
 * Two additions to the organisation API that follow the membership feature (#311), as the
 * dashboard calls them through /graphql:
 *
 * - `ReferringOrganisationWhereInput.membershipUsage`: the organisation list's "near or at the
 *   limit" filter. Usage is derived on read, so this pins that the filter matches the same numbers
 *   `membership` reports, and that it composes with the dashboard's nested search filter.
 * - `referringOrganisationAudits`: an organisation's audit history (membership tier and dates
 *   over time), and its SERVER-side admin gate.
 *
 * The database is shared with other suites, so every assertion is scoped to organisations whose
 * name carries this run's marker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class ReferringOrganisationUsageFilterAndAuditTest {
    @MockitoBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var organisations: ReferringOrganisationRepository

    @Autowired
    lateinit var contacts: ReferringOrganisationContactRepository

    @Autowired
    lateinit var deviceRequests: DeviceRequestRepository

    @Autowired
    lateinit var jdbc: JdbcTemplate

    private val staff = listOf("read:organisations", "write:organisations")
    private val admin = listOf("app:admin")

    private fun JsonNode.items(): List<JsonNode> = (0 until size()).map { get(it) }

    /** POSTs a GraphQL document; [authorities] null means anonymous. Returns the parsed response body. */
    private fun gql(
        query: String,
        variables: Map<String, Any?> = emptyMap(),
        authorities: List<String>? = staff,
    ): JsonNode {
        var request =
            post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("query" to query, "variables" to variables)))
        if (authorities != null) {
            request = request.with(jwt().authorities(authorities.map { SimpleGrantedAuthority(it) }))
        }
        return objectMapper.readTree(
            mockMvc
                .perform(request)
                .andReturn()
                .response.contentAsString,
        )
    }

    private fun org(
        name: String,
        tier: MembershipTier = MembershipTier.FREE,
    ) = organisations.save(
        ReferringOrganisation(
            name = name,
            membershipTier = tier,
            // A paid tier counts only inside its own dates; these always contain today.
            membershipStartDate = LocalDate.now().minusMonths(1),
            membershipEndDate = LocalDate.now().plusMonths(11),
        ),
    )

    /** [count] requests counting towards the allowance, two per referee to stay under the per-referee cap. */
    private fun fill(
        org: ReferringOrganisation,
        count: Int,
    ) {
        var left = count
        while (left > 0) {
            val referee =
                contacts.save(
                    ReferringOrganisationContact(
                        fullName = "Referee ${System.nanoTime()}",
                        email = "usage-filter-${System.nanoTime()}@example.com",
                        phoneNumber = "07000000000",
                        address = "1 Test Street",
                        referringOrganisation = org,
                    ),
                )
            repeat(minOf(2, left)) {
                deviceRequests.save(
                    DeviceRequest(
                        deviceRequestItems = DeviceRequestItems(laptops = 1),
                        referringOrganisationContact = referee,
                        clientRef = "USAGE-FILTER-${System.nanoTime()}",
                        borough = "Lambeth",
                        details = "Household needs a laptop",
                        deviceRequestNeeds = DeviceRequestNeeds(false, false, false),
                    ),
                )
            }
            left -= 2
        }
    }

    /** Names of this run's organisations that the dashboard's list query returns for [bands]. */
    private fun listed(
        marker: String,
        bands: List<String>,
    ): List<String> {
        val body =
            gql(
                DASHBOARD_LIST,
                mapOf(
                    "term" to marker,
                    "filter" to mapOf("archived" to mapOf("_in" to listOf(false)), "membershipUsage" to mapOf("_in" to bands)),
                ),
            )
        assertThat(body.has("errors")).`as`(body.toString()).isFalse()
        return body["data"]["referringOrganisationsConnection"]["content"].items().map { it["name"].asString().removePrefix("$marker ") }
    }

    // --- membershipUsage filter ---------------------------------------------------------------

    @Test
    fun `the usage filter returns the organisations near or at their limit, and only those`() {
        val marker = "usage-${System.nanoTime()}"
        fill(org("$marker free-7"), 7)
        fill(org("$marker free-8"), 8)
        fill(org("$marker free-10"), 10)
        fill(org("$marker free-11"), 11)
        fill(org("$marker tier1-10", MembershipTier.TIER_1), 10)
        fill(org("$marker tier1-16", MembershipTier.TIER_1), 16)
        fill(org("$marker tier3-12", MembershipTier.TIER_3), 12)

        assertThat(listed(marker, listOf("NEAR_LIMIT"))).containsExactlyInAnyOrder("free-8", "tier1-16")
        assertThat(listed(marker, listOf("LIMIT_REACHED"))).containsExactlyInAnyOrder("free-10", "free-11")
        assertThat(listed(marker, listOf("NEAR_LIMIT", "LIMIT_REACHED")))
            .containsExactlyInAnyOrder("free-8", "tier1-16", "free-10", "free-11")
    }

    @Test
    fun `the usage filter agrees with the allowance the same query reports`() {
        val marker = "usage-agree-${System.nanoTime()}"
        fill(org("$marker a"), 9)
        fill(org("$marker b"), 3)

        val body =
            gql(
                "query(\$term: String) { referringOrganisations(where: { name: { _contains: \$term } " +
                    "membershipUsage: { _in: [NEAR_LIMIT] } }) { name membership { used cap state } } }",
                mapOf("term" to marker),
            )

        assertThat(body.has("errors")).`as`(body.toString()).isFalse()
        val rows = body["data"]["referringOrganisations"].items()
        assertThat(rows.map { it["name"].asString() }).containsExactly("$marker a")
        assertThat(rows[0]["membership"]["used"].asInt()).isEqualTo(9)
        assertThat(rows[0]["membership"]["state"].asString()).isEqualTo("LAST_REQUEST")
    }

    @Test
    fun `the usage filter is refused, not ignored, where it cannot be resolved`() {
        val body =
            gql(
                "{ referringOrganisationContactsConnection(where: { referringOrganisation: " +
                    "{ membershipUsage: { _in: [LIMIT_REACHED] } } }) { totalElements } }",
            )

        assertThat(body.has("errors")).`as`(body.toString()).isTrue()
    }

    @Test
    fun `the bands follow the cap - near from 80 percent, reached at the cap, none when uncapped`() {
        fun band(
            tier: MembershipTier,
            used: Int,
        ): MembershipUsageBand? {
            val period = MembershipPeriod(LocalDate.of(2026, 2, 1), LocalDate.of(2027, 1, 31))
            val membership = MembershipAllowanceService.compute(EffectiveMembership(tier, period), used, MembershipOverLimitMode.GRACE)
            return MembershipAllowanceService.usageBand(membership)
        }

        assertThat(band(MembershipTier.FREE, 7)).isNull()
        assertThat(band(MembershipTier.FREE, 8)).isEqualTo(MembershipUsageBand.NEAR_LIMIT)
        assertThat(band(MembershipTier.FREE, 9)).isEqualTo(MembershipUsageBand.NEAR_LIMIT)
        assertThat(band(MembershipTier.FREE, 10)).isEqualTo(MembershipUsageBand.LIMIT_REACHED)
        assertThat(band(MembershipTier.FREE, 12)).isEqualTo(MembershipUsageBand.LIMIT_REACHED)
        assertThat(band(MembershipTier.TIER_1, 15)).isNull()
        assertThat(band(MembershipTier.TIER_1, 16)).isEqualTo(MembershipUsageBand.NEAR_LIMIT)
        assertThat(band(MembershipTier.TIER_2, 31)).isNull()
        assertThat(band(MembershipTier.TIER_2, 32)).isEqualTo(MembershipUsageBand.NEAR_LIMIT)
        assertThat(band(MembershipTier.TIER_2, 40)).isEqualTo(MembershipUsageBand.LIMIT_REACHED)
        assertThat(band(MembershipTier.TIER_3, 500)).isNull()
        // LIMIT_REACHED is the existing state, not a second definition of "at the cap".
        assertThat(MembershipState.LIMIT_REACHED.name).isEqualTo(MembershipUsageBand.LIMIT_REACHED.name)
    }

    // --- referringOrganisationAudits ----------------------------------------------------------

    private fun update(
        org: ReferringOrganisation,
        fields: String,
    ) {
        val body =
            gql(
                "mutation(\$id: ID!, \$name: String) { updateReferringOrganisation(data: { id: \$id name: \$name $fields }) { id } }",
                mapOf("id" to org.id.toString(), "name" to org.name),
            )
        assertThat(body.has("errors")).`as`(body.toString()).isFalse()
    }

    @Test
    fun `the audit history shows how an organisation's membership changed, oldest first`() {
        val org = organisations.save(ReferringOrganisation(name = "Audit org ${System.nanoTime()}"))
        update(org, "membershipTier: TIER_1 membershipStartDate: \"2026-03-01\"")
        update(org, "membershipTier: TIER_2 membershipEndDate: \"2027-06-30\"")

        val body = gql(AUDITS, mapOf("id" to org.id), authorities = admin)

        assertThat(body.has("errors")).`as`(body.toString()).isFalse()
        val revisions = body["data"]["referringOrganisationAudits"].items()
        assertThat(revisions.map { it["type"].asString() }).containsExactly("ADD", "MOD", "MOD")
        assertThat(revisions.map { it["entity"]["membershipTier"].asString() }).containsExactly("FREE", "TIER_1", "TIER_2")
        assertThat(revisions[1]["entity"]["membershipStartDate"].asString()).isEqualTo("2026-03-01")
        assertThat(revisions[1]["entity"]["membershipEndDate"].asString()).isEqualTo("2027-02-28")
        assertThat(revisions[2]["entity"]["membershipEndDate"].asString()).isEqualTo("2027-06-30")
        assertThat(revisions[2]["entity"]["name"].asString()).isEqualTo(org.name)
        assertThat(revisions[2]["entity"]["archived"].asBoolean()).isFalse()
        assertThat(revisions.map { it["revision"]["timestamp"].asLong() }).isSorted().allMatch { it > 0 }
        assertThat(revisions[2]["revision"]["customUser"].isNull).isFalse()
    }

    @Test
    fun `a revision written before membership existed reads back with no membership, not an error`() {
        val org = organisations.save(ReferringOrganisation(name = "Legacy audit org ${System.nanoTime()}"))
        // Negative so it can never meet the revision sequence, and sorts before the real ADD.
        val rev = -System.nanoTime()
        jdbc.update(
            "insert into custom_rev_info (id, timestamp, custom_user) values (?, ?, ?)",
            rev,
            1700000000000L,
            "old@example.com|Old User",
        )
        jdbc.update(
            "insert into referring_organisations_audit_trail (id, rev, revtype, name, archived) values (?, ?, 1, ?, 'N')",
            org.id,
            rev,
            "Old name",
        )

        val body = gql(AUDITS, mapOf("id" to org.id), authorities = admin)

        assertThat(body.has("errors")).`as`(body.toString()).isFalse()
        val legacy = body["data"]["referringOrganisationAudits"][0]
        assertThat(legacy["entity"]["name"].asString()).isEqualTo("Old name")
        assertThat(legacy["entity"]["membershipTier"].isNull).isTrue()
        assertThat(legacy["entity"]["membershipStartDate"].isNull).isTrue()
        assertThat(legacy["revision"]["customUser"].asString()).isEqualTo("old@example.com|Old User")
    }

    @Test
    fun `the audit history is checked on the server - admin permissions only`() {
        val org = organisations.save(ReferringOrganisation(name = "Gated audit org ${System.nanoTime()}"))

        listOf(null, staff, listOf("read:organisations", "read:requests", "admin:kits")).forEach { authorities ->
            val body = gql(AUDITS, mapOf("id" to org.id), authorities)
            assertThat(body["errors"].toString()).`as`("authorities $authorities").contains("Access Denied")
            assertThat(body["data"]["referringOrganisationAudits"].isNull).isTrue()
        }

        listOf(admin, listOf("admin:organisations")).forEach { authorities ->
            val body = gql(AUDITS, mapOf("id" to org.id), authorities)
            assertThat(body.has("errors")).`as`("authorities $authorities: $body").isFalse()
            assertThat(body["data"]["referringOrganisationAudits"]).hasSize(1)
        }
    }

    companion object {
        /** The dashboard's organisation list document (referring-organisation-index), trimmed to what is asserted. */
        private const val DASHBOARD_LIST = """
            query findAllReferringOrgs(${'$'}page: PaginationInput, ${'$'}term: String, ${'$'}filter: ReferringOrganisationWhereInput!) {
              referringOrganisationsConnection(page: ${'$'}page, where: {
                AND: {
                  OR: [
                    { name: { _contains: ${'$'}term } AND: [${'$'}filter] },
                    { website: { _contains: ${'$'}term } AND: [${'$'}filter] }
                  ]
                }
              }) {
                totalElements
                content { id name membership { used cap state } }
              }
            }
        """

        private const val AUDITS = """
            query(${'$'}id: Long!) {
              referringOrganisationAudits(where: ${'$'}id) {
                type
                revision { id timestamp customUser }
                entity { id name website phoneNumber archived membershipTier membershipStartDate membershipEndDate }
              }
            }
        """
    }
}
