package cta.app.graphql

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cta.app.AdminConfigRepository
import cta.app.DeviceRequest
import cta.app.DeviceRequestItems
import cta.app.DeviceRequestNeeds
import cta.app.DeviceRequestRepository
import cta.app.FeatureFlag
import cta.app.FeatureFlagRepository
import cta.app.MembershipOverLimitMode
import cta.app.MembershipPeriods
import cta.app.MembershipTier
import cta.app.ReferringOrganisation
import cta.app.ReferringOrganisationContact
import cta.app.ReferringOrganisationContactRepository
import cta.app.ReferringOrganisationRepository
import cta.app.services.MembershipAllowanceService
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * The membership API (#311) as the dashboard calls it, through /graphql: field names and shapes,
 * the public allowance's email gate, partial-update null semantics, the BLOCK error's machine
 * code, and the batching of `ReferringOrganisation.membership`.
 *
 * Staff calls carry `app:admin`/`write:organisations`/`read:organisations`, which also exempts
 * `createDeviceRequest` from the per-IP public throttle (these tests create many requests).
 * Global state flipped here (the membership-support flag, the over-limit config) is restored
 * afterwards because the Spring context and database are shared with other suites.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class MembershipGraphQlTest {
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
    lateinit var featureFlags: FeatureFlagRepository

    @Autowired
    lateinit var adminConfigs: AdminConfigRepository

    @Autowired
    lateinit var jdbc: org.springframework.jdbc.core.JdbcTemplate

    private fun JsonNode.items(): List<JsonNode> = (0 until size()).map { get(it) }

    private val staff = listOf("app:admin", "read:organisations", "write:organisations")

    private val sqlLogger = LoggerFactory.getLogger("org.hibernate.SQL") as Logger
    private val sqlAppender = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun attachSqlAppender() {
        sqlAppender.start()
        sqlLogger.addAppender(sqlAppender)
        sqlLogger.level = Level.DEBUG
    }

    // Nothing in Flyway seeds the singleton admin_config row (it is created by hand per
    // environment), so the test database has none. Insert it for the test and remove it again only
    // if this test created it, leaving the shared database as other suites expect it.
    private var createdConfigRow = false

    @BeforeEach
    fun ensureAdminConfigRow() {
        createdConfigRow =
            jdbc.update(
                "insert into admin_config (id, can_public_request_broadband_hub, can_public_request_desktop, " +
                    "can_public_request_laptop, can_public_request_phone, can_public_request_tablet, can_public_requestsimcard) " +
                    "values (1, true, true, true, true, true, true) on conflict (id) do nothing",
            ) == 1
    }

    @AfterEach
    fun restoreGlobalState() {
        sqlLogger.detachAppender(sqlAppender)
        sqlAppender.stop()
        featureFlags.save(FeatureFlag(key = MembershipAllowanceService.FLAG_KEY, enabled = false))
        setConfig(MembershipOverLimitMode.GRACE, 2)
        if (createdConfigRow) jdbc.update("delete from admin_config where id = 1")
    }

    private fun setConfig(
        mode: MembershipOverLimitMode,
        grace: Int,
    ) {
        val config = adminConfigs.getAdminConfig()
        config.membershipOverLimitMode = mode
        config.membershipGraceRequests = grace
        adminConfigs.save(config)
    }

    private fun enforce(
        mode: MembershipOverLimitMode,
        grace: Int = 2,
    ) {
        featureFlags.save(FeatureFlag(key = MembershipAllowanceService.FLAG_KEY, enabled = true))
        setConfig(mode, grace)
    }

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
        tier: MembershipTier = MembershipTier.FREE,
        start: String? = null,
    ) = organisations.save(
        ReferringOrganisation(
            name = "GraphQL membership org ${System.nanoTime()}",
            membershipTier = tier,
            membershipStartDate = start?.let { java.time.LocalDate.parse(it) } ?: MembershipPeriods.defaultStartDate(),
        ),
    )

    private fun referee(
        org: ReferringOrganisation,
        email: String = "gql-membership-${System.nanoTime()}@example.com",
        membership: Boolean = false,
    ) = contacts.save(
        ReferringOrganisationContact(
            fullName = "Referee ${System.nanoTime()}",
            email = email,
            phoneNumber = "07000000000",
            address = "1 Test Street",
            referringOrganisation = org,
            isMembershipContact = membership,
        ),
    )

    private fun requests(
        referee: ReferringOrganisationContact,
        count: Int,
    ) = repeat(count) {
        deviceRequests.save(
            DeviceRequest(
                deviceRequestItems = DeviceRequestItems(laptops = 1),
                referringOrganisationContact = referee,
                clientRef = "GQL-MEMBERSHIP-${System.nanoTime()}",
                borough = "Lambeth",
                details = "Household needs a laptop",
                deviceRequestNeeds = DeviceRequestNeeds(false, false, false),
            ),
        )
    }

    /** Spreads [count] open requests over several referees so none reaches the per-referee cap (3). */
    private fun fill(
        org: ReferringOrganisation,
        count: Int,
    ) {
        var left = count
        while (left > 0) {
            val n = minOf(2, left)
            requests(referee(org), n)
            left -= n
        }
    }

    private fun create(refereeId: Long): JsonNode = gql(CREATE_REQUEST, mapOf("contactId" to refereeId.toString()))

    // --- public allowance ---------------------------------------------------------------------

    @Test
    fun `the public lookup returns the allowance for a matching email and organisation`() {
        val org = org(MembershipTier.TIER_1)
        val referee = referee(org)
        requests(referee, 4)

        val body = gql(PUBLIC_LOOKUP, mapOf("email" to referee.email, "orgId" to org.id), authorities = null)

        assertThat(body.has("errors")).`as`(body.toString()).isFalse()
        val allowance = body["data"]["referringOrganisationContactsPublic"][0]["membershipAllowance"]
        assertThat(allowance["tier"].asString()).isEqualTo("TIER_1")
        assertThat(allowance["state"].asString()).isEqualTo("OK")
        assertThat(allowance["cap"].asInt()).isEqualTo(20)
        assertThat(allowance["used"].asInt()).isEqualTo(4)
        assertThat(allowance["remaining"].asInt()).isEqualTo(16)
        assertThat(allowance["periodEnd"].asString()).matches("\\d{4}-\\d{2}-\\d{2}")
        assertThat(allowance["graceUsed"].asInt()).isEqualTo(0)
        assertThat(allowance["graceRemaining"].asInt()).isEqualTo(2) // grace available if the cap is reached
    }

    @Test
    fun `the public allowance is not reachable with a wrong email or an organisation that does not match`() {
        val org = org()
        val other = org()
        val referee = referee(org)

        val wrongEmail = gql(PUBLIC_LOOKUP, mapOf("email" to "nobody-${System.nanoTime()}@example.com", "orgId" to org.id), null)
        assertThat(wrongEmail["data"]["referringOrganisationContactsPublic"]).isEmpty()

        val wrongOrg = gql(PUBLIC_LOOKUP, mapOf("email" to referee.email, "orgId" to other.id), null)
        assertThat(wrongOrg["data"]["referringOrganisationContactsPublic"]).isEmpty()
    }

    @Test
    fun `an uncapped organisation reports no cap or remaining on the public allowance`() {
        val org = org(MembershipTier.TIER_3)
        val referee = referee(org)
        requests(referee, 12)

        val allowance =
            gql(PUBLIC_LOOKUP, mapOf("email" to referee.email, "orgId" to org.id), null)["data"]["referringOrganisationContactsPublic"][0][
                "membershipAllowance",
            ]

        assertThat(allowance["cap"].isNull).isTrue()
        assertThat(allowance["remaining"].isNull).isTrue()
        assertThat(allowance["state"].asString()).isEqualTo("OK")
        assertThat(allowance["used"].asInt()).isEqualTo(12)
    }

    // --- admin config -------------------------------------------------------------------------

    @Test
    fun `adminConfig is readable anonymously with the over-limit settings`() {
        val body = gql("{ adminConfig { membershipOverLimitMode membershipGraceRequests } }", authorities = null)

        assertThat(body.has("errors")).`as`(body.toString()).isFalse()
        assertThat(body["data"]["adminConfig"]["membershipOverLimitMode"].asString()).isEqualTo("GRACE")
        assertThat(body["data"]["adminConfig"]["membershipGraceRequests"].asInt()).isEqualTo(2)
    }

    @Test
    fun `updating only the can-public-request flags leaves the membership settings alone`() {
        setConfig(MembershipOverLimitMode.BLOCK, 5)

        val body = gql(SIX_FLAG_UPDATE)

        assertThat(body.has("errors")).`as`(body.toString()).isFalse()
        val config = adminConfigs.getAdminConfig()
        assertThat(config.membershipOverLimitMode).isEqualTo(MembershipOverLimitMode.BLOCK)
        assertThat(config.membershipGraceRequests).isEqualTo(5)
    }

    @Test
    fun `the membership settings can be changed, and an explicit null leaves them unchanged`() {
        gql(
            "mutation { updateAdminConfig(data: { $SIX_FLAGS membershipOverLimitMode: BLOCK membershipGraceRequests: 4 }) { id } }",
        )
        assertThat(adminConfigs.getAdminConfig().membershipOverLimitMode).isEqualTo(MembershipOverLimitMode.BLOCK)
        assertThat(adminConfigs.getAdminConfig().membershipGraceRequests).isEqualTo(4)

        gql(
            "mutation { updateAdminConfig(data: { $SIX_FLAGS membershipOverLimitMode: null membershipGraceRequests: null }) { id } }",
        )
        assertThat(adminConfigs.getAdminConfig().membershipOverLimitMode).isEqualTo(MembershipOverLimitMode.BLOCK)
        assertThat(adminConfigs.getAdminConfig().membershipGraceRequests).isEqualTo(4)
    }

    @Test
    fun `a negative grace count is rejected`() {
        val body =
            gql("mutation { updateAdminConfig(data: { $SIX_FLAGS membershipGraceRequests: -1 }) { id } }")

        assertThat(body.has("errors")).isTrue()
        assertThat(adminConfigs.getAdminConfig().membershipGraceRequests).isEqualTo(2)
    }

    // --- createDeviceRequest enforcement ------------------------------------------------------

    @Test
    fun `BLOCK refuses with the membership message and a machine-readable code`() {
        enforce(MembershipOverLimitMode.BLOCK)
        val org = org()
        fill(org, 10)

        val body = create(referee(org).id)

        val error = body["errors"].single()
        assertThat(error["message"].asString()).startsWith("Membership allowance reached:")
        assertThat(error["extensions"]["code"].asString()).isEqualTo("MEMBERSHIP_LIMIT_REACHED")
        assertThat(error["extensions"]["classification"].asString()).isEqualTo("BAD_REQUEST")
        // The mutation is nullable, so the refusal is the only error and nothing was created.
        assertThat(body["data"]["createDeviceRequest"].isNull).isTrue()
    }

    @Test
    fun `GRACE accepts requests past the cap with grace indexes then refuses`() {
        enforce(MembershipOverLimitMode.GRACE, grace = 2)
        val org = org()
        fill(org, 10)

        // A fresh referee each time keeps every one of them under the per-referee cap of 3, so
        // only the membership allowance can be what answers.
        val first = create(referee(org).id)
        assertThat(first.has("errors")).`as`(first.toString()).isFalse()
        assertThat(first["data"]["createDeviceRequest"]["membershipGraceIndex"].asInt()).isEqualTo(1)

        val second = create(referee(org).id)
        assertThat(second["data"]["createDeviceRequest"]["membershipGraceIndex"].asInt()).isEqualTo(2)

        val third = create(referee(org).id)
        assertThat(third["errors"].single()["extensions"]["code"].asString()).isEqualTo("MEMBERSHIP_LIMIT_REACHED")
    }

    @Test
    fun `a request within the allowance has a null grace index`() {
        enforce(MembershipOverLimitMode.GRACE)
        val referee = referee(org())

        val body = create(referee.id)

        assertThat(body.has("errors")).`as`(body.toString()).isFalse()
        assertThat(body["data"]["createDeviceRequest"]["membershipGraceIndex"].isNull).isTrue()
    }

    @Test
    fun `the referee cap is checked first and its error is unchanged apart from a code`() {
        enforce(MembershipOverLimitMode.BLOCK)
        val org = org()
        fill(org, 10) // the organisation is at its cap...
        val referee = referee(org)
        requests(referee, 3) // ...AND this referee holds 3 open requests (the per-referee cap)

        val body = create(referee.id)

        val error = body["errors"].single()
        assertThat(error["message"].asString()).contains("requests open").doesNotStartWith("Membership allowance reached")
        assertThat(error["extensions"]["code"].asString()).isEqualTo("REFEREE_LIMIT_REACHED")
    }

    @Test
    fun `nothing is enforced while the membership-support flag is off`() {
        // Flag is seeded false by the migration; deliberately not enabled here.
        val org = org()
        fill(org, 10) // at the FREE cap, but nothing is enforced

        val body = create(referee(org).id)

        assertThat(body.has("errors")).`as`(body.toString()).isFalse()
        assertThat(body["data"]["createDeviceRequest"]["membershipGraceIndex"].isNull).isTrue()
    }

    @Test
    fun `the membership-support flag is seeded off`() {
        val flag = featureFlags.findById(MembershipAllowanceService.FLAG_KEY)

        assertThat(flag).isPresent
        assertThat(flag.get().enabled).isFalse()
    }

    // --- organisation + referee fields and partial updates ------------------------------------

    @Test
    fun `an organisation update that omits the membership fields leaves tier and start date unchanged`() {
        val org = org(MembershipTier.TIER_2, "2026-06-15")

        val body = gql(UPDATE_ORG_WITHOUT_MEMBERSHIP, mapOf("id" to org.id))

        assertThat(body.has("errors")).`as`(body.toString()).isFalse()
        val reloaded = organisations.findById(org.id).orElseThrow()
        assertThat(reloaded.membershipTier).isEqualTo(MembershipTier.TIER_2)
        assertThat(reloaded.membershipStartDate.toString()).isEqualTo("2026-06-15")
        assertThat(body["data"]["updateReferringOrganisation"]["membershipTier"].asString()).isEqualTo("TIER_2")
        assertThat(body["data"]["updateReferringOrganisation"]["membershipStartDate"].asString()).isEqualTo("2026-06-15")
    }

    @Test
    fun `explicit nulls also leave the membership fields unchanged`() {
        val org = org(MembershipTier.TIER_1, "2026-03-01")

        gql(
            "mutation(\$id: ID!) { updateReferringOrganisation(data: { id: \$id name: \"Renamed\" " +
                "membershipTier: null membershipStartDate: null }) { id } }",
            mapOf("id" to org.id),
        )

        val reloaded = organisations.findById(org.id).orElseThrow()
        assertThat(reloaded.membershipTier).isEqualTo(MembershipTier.TIER_1)
        assertThat(reloaded.membershipStartDate.toString()).isEqualTo("2026-03-01")
    }

    @Test
    fun `staff can set the tier and a plain-date start`() {
        val org = org()

        val body =
            gql(
                "mutation(\$id: ID!) { updateReferringOrganisation(data: { id: \$id name: \"Upgraded\" " +
                    "membershipTier: TIER_2 membershipStartDate: \"2026-06-15\" }) " +
                    "{ membershipTier membershipStartDate membership { tier periodStart periodEnd cap } } }",
                mapOf("id" to org.id),
            )

        val updated = body["data"]["updateReferringOrganisation"]
        assertThat(updated["membershipTier"].asString()).isEqualTo("TIER_2")
        assertThat(updated["membershipStartDate"].asString()).isEqualTo("2026-06-15")
        assertThat(updated["membership"]["periodStart"].asString()).isEqualTo("2026-06-15")
        assertThat(updated["membership"]["periodEnd"].asString()).isEqualTo("2027-06-14")
        assertThat(updated["membership"]["cap"].asInt()).isEqualTo(40)
    }

    @Test
    fun `an Instant-shaped start date is refused and nothing changes`() {
        val org = org(MembershipTier.TIER_1, "2026-03-01")

        val body =
            gql(
                "mutation(\$id: ID!) { updateReferringOrganisation(data: { id: \$id name: \"X\" " +
                    "membershipTier: TIER_3 membershipStartDate: \"2026-06-15T00:00:00Z\" }) { id } }",
                mapOf("id" to org.id),
            )

        assertThat(body["errors"].single()["message"].asString()).contains("YYYY-MM-DD")
        val reloaded = organisations.findById(org.id).orElseThrow()
        assertThat(reloaded.membershipTier).isEqualTo(MembershipTier.TIER_1)
        assertThat(reloaded.membershipStartDate.toString()).isEqualTo("2026-03-01")
    }

    @Test
    fun `a referee update that omits isMembershipContact leaves it unchanged, and it can be set`() {
        val org = org()
        val referee = referee(org, membership = true)

        gql(UPDATE_REFEREE_WITHOUT_FLAG, mapOf("id" to referee.id, "orgId" to org.id))
        assertThat(contacts.findById(referee.id).orElseThrow().isMembershipContact).isTrue()

        gql(
            "mutation(\$id: ID!, \$orgId: ID!) { updateReferringOrganisationContact(data: { id: \$id fullName: \"R\" " +
                "email: \"r@example.com\" phoneNumber: \"07\" referringOrganisationId: \$orgId isMembershipContact: null }) { id } }",
            mapOf("id" to referee.id, "orgId" to org.id),
        )
        assertThat(contacts.findById(referee.id).orElseThrow().isMembershipContact).isTrue()

        val cleared =
            gql(
                "mutation(\$id: ID!, \$orgId: ID!) { updateReferringOrganisationContact(data: { id: \$id fullName: \"R\" " +
                    "email: \"r@example.com\" phoneNumber: \"07\" referringOrganisationId: \$orgId isMembershipContact: false }) " +
                    "{ isMembershipContact } }",
                mapOf("id" to referee.id, "orgId" to org.id),
            )
        assertThat(cleared["data"]["updateReferringOrganisationContact"]["isMembershipContact"].asBoolean()).isFalse()
        assertThat(contacts.findById(referee.id).orElseThrow().isMembershipContact).isFalse()
    }

    @Test
    fun `creating a referee without isMembershipContact defaults it to false, and it can be set true`() {
        val org = org()

        fun createReferee(flag: String) =
            gql(
                "mutation(\$orgId: ID!) { createReferringOrganisationContact(data: { fullName: \"New\" " +
                    "email: \"new-${System.nanoTime()}@example.com\" phoneNumber: \"07\" referringOrganisation: \$orgId $flag }) { id } }",
                mapOf("orgId" to org.id),
            )["data"]["createReferringOrganisationContact"]["id"].asString().toLong()

        assertThat(contacts.findById(createReferee("")).orElseThrow().isMembershipContact).isFalse()
        assertThat(contacts.findById(createReferee("isMembershipContact: true")).orElseThrow().isMembershipContact).isTrue()
    }

    @Test
    fun `referees can be filtered by isMembershipContact and organisations by tier`() {
        val org = org(MembershipTier.TIER_2)
        val member = referee(org, membership = true)
        val plain = referee(org)

        val referees =
            gql(
                "query(\$orgId: Long) { referringOrganisationContacts(where: { referringOrganisation: { id: { _eq: \$orgId } } " +
                    "isMembershipContact: { _eq: true } }) { id isMembershipContact } }",
                mapOf("orgId" to org.id),
            )["data"]["referringOrganisationContacts"]
        assertThat(referees.items().map { it["id"].asString().toLong() }).containsExactly(member.id)
        assertThat(referees.items().map { it["id"].asString().toLong() }).doesNotContain(plain.id)

        val tier2Only =
            gql(
                "query(\$id: Long) { referringOrganisations(where: { id: { _eq: \$id } membershipTier: { _in: [TIER_2, TIER_3] } }) { id } }",
                mapOf("id" to org.id),
            )["data"]["referringOrganisations"]
        assertThat(tier2Only).hasSize(1)

        val freeOnly =
            gql(
                "query(\$id: Long) { referringOrganisations(where: { id: { _eq: \$id } membershipTier: { _eq: FREE } }) { id } }",
                mapOf("id" to org.id),
            )["data"]["referringOrganisations"]
        assertThat(freeOnly).isEmpty()
    }

    @Test
    fun `membershipContacts lists the organisation's non-archived membership referees`() {
        val org = org()
        val member = referee(org, membership = true)
        referee(org)

        val contactsField =
            gql(
                "query(\$id: Long) { referringOrganisation(where: { id: { _eq: \$id } }) { membershipContacts { id fullName } } }",
                mapOf("id" to org.id),
            )["data"]["referringOrganisation"]["membershipContacts"]

        assertThat(contactsField.items().map { it["id"].asString().toLong() }).containsExactly(member.id)
    }

    // --- batching -----------------------------------------------------------------------------

    @Test
    fun `membership on a page of organisations costs one grouped count, not one per row`() {
        val orgs = (1..6).map { org() }
        orgs.forEach { requests(referee(it), 1) }
        val ids = orgs.map { it.id }
        sqlAppender.list.clear()

        val body =
            gql(
                "query(\$ids: [Long]) { referringOrganisations(where: { id: { _in: \$ids } }) { id membership { used cap state } } }",
                mapOf("ids" to ids),
            )

        assertThat(body.has("errors")).`as`(body.toString()).isFalse()
        val rows = body["data"]["referringOrganisations"]
        assertThat(rows).hasSize(6)
        rows.forEach { assertThat(it["membership"]["used"].asInt()).isEqualTo(1) }
        val groupedCounts =
            sqlAppender.list
                .map { it.formattedMessage.lowercase() }
                .filter { "group by" in it && "device_requests" in it }
        assertThat(groupedCounts).`as`("one grouped usage query for the whole page").hasSize(1)
    }

    @Test
    fun `membership is selectable from the request table through the referee and organisation`() {
        val org = org(MembershipTier.TIER_1)
        val referee = referee(org)
        requests(referee, 2)

        val body =
            gql(
                "query(\$id: Long) { deviceRequests(where: { referringOrganisationContact: { id: { _eq: \$id } } }) " +
                    "{ id membershipGraceIndex referringOrganisationContact { referringOrganisation { membership { used cap } } } } }",
                mapOf("id" to referee.id),
            )

        assertThat(body.has("errors")).`as`(body.toString()).isFalse()
        val row = body["data"]["deviceRequests"][0]
        assertThat(row["referringOrganisationContact"]["referringOrganisation"]["membership"]["used"].asInt()).isEqualTo(2)
        assertThat(row["referringOrganisationContact"]["referringOrganisation"]["membership"]["cap"].asInt()).isEqualTo(20)
    }

    companion object {
        private const val SIX_FLAGS =
            "canPublicRequestSIMCard: true canPublicRequestLaptop: true canPublicRequestPhone: true " +
                "canPublicRequestBroadbandHub: true canPublicRequestTablet: true canPublicRequestDesktop: true"

        private const val SIX_FLAG_UPDATE = "mutation { updateAdminConfig(data: { $SIX_FLAGS }) { id } }"

        private const val CREATE_REQUEST =
            "mutation(\$contactId: ID!) { createDeviceRequest(data: { referringOrganisationContact: \$contactId " +
                "deviceRequestItems: { laptops: 1 } clientRef: \"GQL-MEMBERSHIP\" borough: \"Lambeth\" " +
                "details: \"Household needs a laptop\" }) { id membershipGraceIndex } }"

        private const val PUBLIC_LOOKUP =
            "query(\$email: String!, \$orgId: Long) { referringOrganisationContactsPublic(where: { " +
                "email: { _ilike: \$email } referringOrganisation: { id: { _eq: \$orgId } } archived: { _eq: false } }) { " +
                "id fullName membershipAllowance { tier state cap used remaining periodEnd graceUsed graceRemaining } } }"

        private const val UPDATE_ORG_WITHOUT_MEMBERSHIP =
            "mutation(\$id: ID!) { updateReferringOrganisation(data: { id: \$id name: \"Renamed org\" }) " +
                "{ id membershipTier membershipStartDate } }"

        private const val UPDATE_REFEREE_WITHOUT_FLAG =
            "mutation(\$id: ID!, \$orgId: ID!) { updateReferringOrganisationContact(data: { id: \$id fullName: \"R\" " +
                "email: \"r@example.com\" phoneNumber: \"07\" referringOrganisationId: \$orgId }) { id } }"
    }
}
