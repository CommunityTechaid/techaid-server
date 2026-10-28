package cta.app

import cta.app.services.MembershipAllowanceService
import cta.app.services.MembershipLimitReachedException
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Database-backed coverage for [MembershipAllowanceService] (#311): what counts towards the yearly
 * allowance, how the period rolls forward, and the GRACE / BLOCK enforcement at the cap.
 *
 * Shares the embedded-database context with the other unforked DB tests, so everything here adds
 * its own organisations and restores the two pieces of global state it flips (the
 * membership-support flag and the over-limit config) afterwards; leaving either changed would make
 * an unrelated suite fail later.
 *
 * `created_at` is set through JDBC because the entity's `@CreationTimestamp` always wins on insert,
 * and the period-boundary tests need requests at exact instants.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class MembershipAllowanceServiceTest {
    @MockitoBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var service: MembershipAllowanceService

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
    lateinit var jdbc: JdbcTemplate

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
        mode: MembershipOverLimitMode = MembershipOverLimitMode.GRACE,
        grace: Int = 2,
    ) {
        featureFlags.save(FeatureFlag(key = MembershipAllowanceService.FLAG_KEY, enabled = true))
        setConfig(mode, grace)
    }

    private fun org(
        tier: MembershipTier = MembershipTier.FREE,
        start: LocalDate = MembershipPeriods.defaultStartDate(),
        end: LocalDate? = null,
    ) = organisations.save(
        ReferringOrganisation(
            name = "Membership org ${System.nanoTime()}",
            membershipTier = tier,
            membershipStartDate = start,
            membershipEndDate = end,
        ),
    )

    private fun referee(
        org: ReferringOrganisation,
        archived: Boolean = false,
    ) = contacts.save(
        ReferringOrganisationContact(
            fullName = "Referee ${System.nanoTime()}",
            email = "membership-${System.nanoTime()}@example.com",
            phoneNumber = "07000000000",
            address = "1 Test Street",
            referringOrganisation = org,
            archived = archived,
        ),
    )

    private fun request(
        referee: ReferringOrganisationContact,
        status: DeviceRequestStatus = DeviceRequestStatus.NEW,
        createdAt: Instant? = null,
        graceIndex: Int? = null,
    ): DeviceRequest {
        val saved =
            deviceRequests.save(
                DeviceRequest(
                    deviceRequestItems = DeviceRequestItems(laptops = 1),
                    referringOrganisationContact = referee,
                    status = status,
                    clientRef = "MEMBERSHIP-${System.nanoTime()}",
                    borough = "Lambeth",
                    details = "Household needs a laptop",
                    deviceRequestNeeds = DeviceRequestNeeds(false, false, false),
                    membershipGraceIndex = graceIndex,
                ),
            )
        if (createdAt != null) {
            jdbc.update(
                "update device_requests set created_at = ? where id = ?",
                OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC),
                saved.id,
            )
        }
        return saved
    }

    private fun requests(
        referee: ReferringOrganisationContact,
        count: Int,
    ) = repeat(count) { request(referee) }

    private fun d(s: String) = LocalDate.parse(s)

    // --- defaults -----------------------------------------------------------------------------

    @Test
    fun `a stored organisation defaults to FREE and the current 1 February start`() {
        val saved = organisations.save(ReferringOrganisation(name = "Default ${System.nanoTime()}"))

        val reloaded = organisations.findById(saved.id).orElseThrow()
        assertThat(reloaded.membershipTier).isEqualTo(MembershipTier.FREE)
        assertThat(reloaded.membershipStartDate).isEqualTo(MembershipPeriods.defaultStartDate())
    }

    @Test
    fun `a row written without the membership columns gets the same defaults from the migration`() {
        // Existing rows were backfilled and anything written outside the app (a seed script, a
        // manual INSERT) must land in the same place: FREE, anchored at 1 Feb of the current year.
        val id = jdbc.queryForObject("select nextval('referring_organisation_sequence')", Long::class.java)!!
        jdbc.update("insert into referring_organisations (id, name, archived) values (?, ?, 'N')", id, "Raw ${System.nanoTime()}")

        val reloaded = organisations.findById(id).orElseThrow()

        assertThat(reloaded.membershipTier).isEqualTo(MembershipTier.FREE)
        assertThat(reloaded.membershipStartDate).isEqualTo(MembershipPeriods.defaultStartDate())
    }

    @Test
    fun `the membership contact flag defaults to false`() {
        val referee = referee(org())

        assertThat(contacts.findById(referee.id).orElseThrow().isMembershipContact).isFalse()
    }

    // --- usage --------------------------------------------------------------------------------

    @Test
    fun `usage counts requests across all of the organisation's referees`() {
        val org = org()
        requests(referee(org), 2)
        requests(referee(org), 3)
        requests(referee(org(MembershipTier.FREE)), 4) // another organisation: must not leak in

        val membership = service.membershipFor(org)

        assertThat(membership.used).isEqualTo(5)
        assertThat(membership.remaining).isEqualTo(5)
    }

    @Test
    fun `usage includes requests from archived referees`() {
        val org = org()
        requests(referee(org), 1)
        requests(referee(org, archived = true), 2)

        assertThat(service.membershipFor(org).used).isEqualTo(3)
    }

    @Test
    fun `declined and cancelled requests do not count but completed and failed ones do`() {
        val org = org()
        val referee = referee(org)
        request(referee, DeviceRequestStatus.REQUEST_DECLINED)
        request(referee, DeviceRequestStatus.REQUEST_CANCELLED)
        request(referee, DeviceRequestStatus.REQUEST_COMPLETED)
        request(referee, DeviceRequestStatus.REQUEST_COLLECTION_DELIVERY_FAILED)
        request(referee, DeviceRequestStatus.NEW)
        request(referee, DeviceRequestStatus.PROCESSING_ON_HOLD)

        assertThat(service.membershipFor(org).used).isEqualTo(4)
    }

    @Test
    fun `FREE only counts requests inside the 1 February default year, whatever start is stored`() {
        val org = org(start = d("2025-06-15")) // ignored for FREE
        val referee = referee(org)
        request(referee, createdAt = Instant.parse("2025-06-01T12:00:00Z"))
        request(referee, createdAt = Instant.parse("2026-01-31T12:00:00Z"))

        // 31 Jan 2026 is the last day of the 2025-26 year: both requests count.
        val lastDay = service.membershipFor(org, today = d("2026-01-31"))
        assertThat(lastDay.used).isEqualTo(2)
        assertThat(lastDay.periodEnd).isEqualTo(d("2026-01-31"))

        // 1 Feb 2026 starts a new year with nothing used.
        val newYear = service.membershipFor(org, today = d("2026-02-01"))
        assertThat(newYear.used).isEqualTo(0)
        assertThat(newYear.periodStart).isEqualTo(d("2026-02-01"))
        assertThat(newYear.periodEnd).isEqualTo(d("2027-01-31"))
    }

    @Test
    fun `an active paid tier counts only requests inside its own start and end dates`() {
        val org = org(MembershipTier.TIER_1, start = d("2026-06-15"), end = d("2027-06-14"))
        val referee = referee(org)
        request(referee, createdAt = Instant.parse("2026-06-14T10:00:00Z"))
        request(referee, createdAt = Instant.parse("2026-06-16T10:00:00Z"))

        val membership = service.membershipFor(org, today = d("2026-10-28"))

        assertThat(membership.used).isEqualTo(1)
        assertThat(membership.tier).isEqualTo(MembershipTier.TIER_1)
        assertThat(membership.periodStart).isEqualTo(d("2026-06-15"))
        assertThat(membership.periodEnd).isEqualTo(d("2027-06-14"))
        assertThat(membership.expired).isFalse()
    }

    @Test
    fun `an expired paid tier is counted as FREE over the default year and reports the lapse`() {
        val org = org(MembershipTier.TIER_2, start = d("2025-03-01"), end = d("2026-02-28"))
        val referee = referee(org)
        request(referee, createdAt = Instant.parse("2025-06-01T12:00:00Z")) // inside the lapsed term only
        request(referee, createdAt = Instant.parse("2026-05-01T12:00:00Z")) // inside the default year

        val membership = service.membershipFor(org, today = d("2026-10-28"))

        assertThat(membership.tier).isEqualTo(MembershipTier.FREE)
        assertThat(membership.cap).isEqualTo(10)
        assertThat(membership.periodStart).isEqualTo(d("2026-02-01"))
        assertThat(membership.periodEnd).isEqualTo(d("2027-01-31"))
        assertThat(membership.used).isEqualTo(1)
        assertThat(membership.expired).isTrue()
        assertThat(membership.lapsedTier).isEqualTo(MembershipTier.TIER_2)
        assertThat(membership.lapsedOn).isEqualTo(d("2026-02-28"))
        assertThat(organisations.findById(org.id).orElseThrow().membershipTier).isEqualTo(MembershipTier.TIER_2)
        assertThat(service.publicView(membership).expired).isTrue()
        assertThat(service.publicView(membership).tier).isEqualTo(MembershipTier.FREE)
    }

    @Test
    fun `a paid tier that has not started is FREE and not expired`() {
        val org = org(MembershipTier.TIER_3, start = d("2026-12-01"), end = d("2027-11-30"))

        val membership = service.membershipFor(org, today = d("2026-10-28"))

        assertThat(membership.tier).isEqualTo(MembershipTier.FREE)
        assertThat(membership.cap).isEqualTo(10)
        assertThat(membership.expired).isFalse()
        assertThat(membership.lapsedTier).isNull()
        assertThat(membership.periodStart).isEqualTo(d("2026-02-01"))
    }

    @Test
    fun `enforcement uses the effective tier, so an expired TIER_3 is capped at 10`() {
        enforce(MembershipOverLimitMode.BLOCK)
        val expired =
            org(MembershipTier.TIER_3, start = MembershipPeriods.today().minusYears(2), end = MembershipPeriods.today().minusYears(1))
        requests(referee(expired), 10)
        assertThatThrownBy { service.checkNewRequest(expired) }.isInstanceOf(MembershipLimitReachedException::class.java)

        val active =
            org(MembershipTier.TIER_3, start = MembershipPeriods.today().minusMonths(1), end = MembershipPeriods.today().plusMonths(11))
        requests(referee(active), 12)
        assertThat(service.checkNewRequest(active)).isNull()
    }

    @Test
    fun `period boundaries are London midnights, so BST shifts them an hour against UTC`() {
        val org = org(MembershipTier.TIER_1, start = d("2025-04-01"), end = d("2026-03-31"))
        val referee = referee(org)
        // BST began on 29 Mar 2026, so London is UTC+1 on these dates:
        // 22:30Z = 23:30 BST on 31 Mar (still the old year); 23:30Z = 00:30 BST on 1 Apr (new year).
        request(referee, createdAt = Instant.parse("2026-03-31T22:30:00Z"))
        request(referee, createdAt = Instant.parse("2026-03-31T23:30:00Z"))

        val oldYear = service.membershipFor(org, today = d("2026-03-31"))
        assertThat(oldYear.used).`as`("old year holds only the 23:30 BST request").isEqualTo(1)

        val renewed = org(MembershipTier.TIER_1, start = d("2026-04-01"), end = d("2027-03-31"))
        val renewedReferee = referee(renewed)
        request(renewedReferee, createdAt = Instant.parse("2026-03-31T22:30:00Z"))
        request(renewedReferee, createdAt = Instant.parse("2026-03-31T23:30:00Z"))
        val newYear = service.membershipFor(renewed, today = d("2026-04-01"))
        assertThat(newYear.used).`as`("new term holds only the 00:30 BST request").isEqualTo(1)
    }

    @Test
    fun `a page of organisations with different tiers and periods gets each its own allowance`() {
        val a = org(MembershipTier.FREE, d("2026-02-01"))
        val b = org(MembershipTier.TIER_1, d("2026-02-01"))
        val c = org(MembershipTier.TIER_3, d("2026-06-15"))
        requests(referee(a), 9)
        requests(referee(b), 2)
        requests(referee(c), 1)
        request(referee(c), createdAt = Instant.parse("2026-03-01T00:00:00Z")) // before c's 15 Jun start

        val result = service.membershipsFor(listOf(a, b, c), today = d("2026-10-28"))

        assertThat(result.getValue(a.id).used).isEqualTo(9)
        assertThat(result.getValue(a.id).state).isEqualTo(MembershipState.LAST_REQUEST)
        assertThat(result.getValue(b.id).used).isEqualTo(2)
        assertThat(result.getValue(b.id).cap).isEqualTo(20)
        assertThat(result.getValue(c.id).used).isEqualTo(1)
        assertThat(result.getValue(c.id).cap).isNull()
        assertThat(result.getValue(c.id).periodStart).isEqualTo(d("2026-06-15"))
    }

    // --- enforcement --------------------------------------------------------------------------

    @Test
    fun `nothing is enforced while the membership-support flag is off`() {
        val org = org()
        requests(referee(org), 12)

        assertThat(service.checkNewRequest(org)).isNull()
    }

    @Test
    fun `under the cap a new request is accepted with no grace index`() {
        enforce()
        val org = org()
        requests(referee(org), 9)

        assertThat(service.checkNewRequest(org)).isNull()
    }

    @Test
    fun `TIER_3 is never refused however many requests it has made`() {
        enforce(MembershipOverLimitMode.BLOCK)
        val org = org(MembershipTier.TIER_3)
        requests(referee(org), 45)

        assertThat(service.checkNewRequest(org)).isNull()
    }

    @Test
    fun `GRACE accepts N requests past the cap, numbers them from 1, then blocks`() {
        enforce(MembershipOverLimitMode.GRACE, grace = 2)
        val org = org()
        val referee = referee(org)
        requests(referee, 10) // at the cap

        val first = service.checkNewRequest(org)
        assertThat(first).isEqualTo(1)
        request(referee, graceIndex = first)

        val second = service.checkNewRequest(org)
        assertThat(second).isEqualTo(2)
        request(referee, graceIndex = second)

        assertThatThrownBy { service.checkNewRequest(org) }
            .isInstanceOf(MembershipLimitReachedException::class.java)
            .hasMessageStartingWith("Membership allowance reached:")
    }

    @Test
    fun `a declined grace request frees its slot and the next one reuses the index`() {
        enforce(MembershipOverLimitMode.GRACE, grace = 1)
        val org = org()
        val referee = referee(org)
        requests(referee, 10)
        val grace = request(referee, graceIndex = service.checkNewRequest(org))
        assertThatThrownBy { service.checkNewRequest(org) }.isInstanceOf(MembershipLimitReachedException::class.java)

        deviceRequests.save(grace.also { it.status = DeviceRequestStatus.REQUEST_DECLINED })

        assertThat(service.checkNewRequest(org)).isEqualTo(1)
    }

    @Test
    fun `BLOCK refuses the first request past the cap with the membership message`() {
        enforce(MembershipOverLimitMode.BLOCK)
        val org = org()
        requests(referee(org), 10)

        assertThatThrownBy { service.checkNewRequest(org) }
            .isInstanceOf(MembershipLimitReachedException::class.java)
            .hasMessageStartingWith(MembershipLimitReachedException.MESSAGE_PREFIX)
            .hasMessageContaining("10 requests")
    }

    @Test
    fun `GRACE with zero grace requests behaves like BLOCK`() {
        enforce(MembershipOverLimitMode.GRACE, grace = 0)
        val org = org()
        requests(referee(org), 10)

        assertThatThrownBy { service.checkNewRequest(org) }.isInstanceOf(MembershipLimitReachedException::class.java)
    }

    @Test
    fun `the staff-override seam bypasses the check without changing the default`() {
        enforce(MembershipOverLimitMode.BLOCK)
        val org = org()
        requests(referee(org), 10)

        assertThat(service.checkNewRequest(org, bypass = true)).isNull()
        assertThatThrownBy { service.checkNewRequest(org) }.isInstanceOf(MembershipLimitReachedException::class.java)
    }

    @Test
    fun `the public view reports grace remaining in GRACE mode and none in BLOCK mode`() {
        enforce(MembershipOverLimitMode.GRACE, grace = 2)
        val org = org()
        val referee = referee(org)
        requests(referee, 11) // 10 + 1 grace

        val grace = service.publicView(service.membershipFor(org))
        assertThat(grace.state).isEqualTo(MembershipState.LIMIT_REACHED)
        assertThat(grace.graceUsed).isEqualTo(1)
        assertThat(grace.graceRemaining).isEqualTo(1)

        setConfig(MembershipOverLimitMode.BLOCK, 2)
        val block = service.publicView(service.membershipFor(org))
        assertThat(block.graceUsed).isEqualTo(0)
        assertThat(block.graceRemaining).isEqualTo(0)
    }
}
