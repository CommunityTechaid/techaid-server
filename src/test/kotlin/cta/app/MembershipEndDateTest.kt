package cta.app

import cta.app.graphql.mutations.UpdateReferringOrganisationInput
import cta.app.services.MembershipAllowanceService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Pure (no Spring, no DB) coverage of the membership end date (#311 follow-up): which tier and
 * period apply today, and how an organisation update defaults and validates the dates.
 */
class MembershipEndDateTest {
    private fun d(s: String) = LocalDate.parse(s)

    private val today = d("2026-10-28")
    private val defaultYear = MembershipPeriod(d("2026-02-01"), d("2027-01-31"))

    private fun org(
        tier: MembershipTier,
        start: String = "2026-02-01",
        end: String? = null,
    ) = ReferringOrganisation(
        name = "Pure org",
        membershipTier = tier,
        membershipStartDate = d(start),
        membershipEndDate = end?.let { d(it) },
    )

    private fun effective(
        org: ReferringOrganisation,
        on: LocalDate = today,
    ) = MembershipAllowanceService.effectiveMembership(org, on)

    // --- effective membership -----------------------------------------------------------------

    @Test
    fun `FREE always uses the 1 February default year whatever start date is stored`() {
        val e = effective(org(MembershipTier.FREE, start = "2025-06-15", end = "2026-06-14"))

        assertThat(e.tier).isEqualTo(MembershipTier.FREE)
        assertThat(e.period).isEqualTo(defaultYear)
        assertThat(e.expired).isFalse()
    }

    @Test
    fun `FREE before 1 February uses the year that began last February`() {
        assertThat(effective(org(MembershipTier.FREE), on = d("2026-01-31")).period)
            .isEqualTo(MembershipPeriod(d("2025-02-01"), d("2026-01-31")))
    }

    @Test
    fun `an active paid tier counts exactly its own dates with no rolling`() {
        val e = effective(org(MembershipTier.TIER_2, start = "2026-06-15", end = "2027-03-31"))

        assertThat(e.tier).isEqualTo(MembershipTier.TIER_2)
        assertThat(e.period).isEqualTo(MembershipPeriod(d("2026-06-15"), d("2027-03-31")))
        assertThat(e.expired).isFalse()
        assertThat(e.lapsedTier).isNull()
        assertThat(e.lapsedOn).isNull()
    }

    @Test
    fun `the start and end dates are both inclusive`() {
        val o = org(MembershipTier.TIER_1, start = "2026-06-15", end = "2026-10-28")

        assertThat(effective(o, on = d("2026-06-15")).tier).isEqualTo(MembershipTier.TIER_1)
        assertThat(effective(o, on = d("2026-10-28")).tier).isEqualTo(MembershipTier.TIER_1)
        assertThat(effective(o, on = d("2026-10-29")).tier).isEqualTo(MembershipTier.FREE)
        assertThat(effective(o, on = d("2026-06-14")).tier).isEqualTo(MembershipTier.FREE)
    }

    @Test
    fun `an expired paid tier falls back to FREE on the default year and reports what lapsed`() {
        val e = effective(org(MembershipTier.TIER_2, start = "2025-03-01", end = "2026-02-28"))

        assertThat(e.tier).isEqualTo(MembershipTier.FREE)
        assertThat(e.period).isEqualTo(defaultYear)
        assertThat(e.expired).isTrue()
        assertThat(e.lapsedTier).isEqualTo(MembershipTier.TIER_2)
        assertThat(e.lapsedOn).isEqualTo(d("2026-02-28"))
        assertThat(MembershipAllowanceService.compute(e, 0, MembershipOverLimitMode.GRACE).cap).isEqualTo(10)
    }

    @Test
    fun `a lapse does not change the stored tier`() {
        val o = org(MembershipTier.TIER_3, start = "2025-03-01", end = "2026-02-28")

        effective(o)

        assertThat(o.membershipTier).isEqualTo(MembershipTier.TIER_3)
    }

    @Test
    fun `a paid tier that has not started yet is FREE on the default year and not expired`() {
        val e = effective(org(MembershipTier.TIER_3, start = "2026-11-01", end = "2027-10-31"))

        assertThat(e.tier).isEqualTo(MembershipTier.FREE)
        assertThat(e.period).isEqualTo(defaultYear)
        assertThat(e.expired).isFalse()
        assertThat(e.lapsedTier).isNull()
    }

    @Test
    fun `a paid tier with no end date is treated as ending a year less a day after its start`() {
        val active = effective(org(MembershipTier.TIER_1, start = "2026-06-15", end = null))
        assertThat(active.tier).isEqualTo(MembershipTier.TIER_1)
        assertThat(active.period).isEqualTo(MembershipPeriod(d("2026-06-15"), d("2027-06-14")))

        val lapsed = effective(org(MembershipTier.TIER_1, start = "2025-06-15", end = null))
        assertThat(lapsed.expired).isTrue()
        assertThat(lapsed.lapsedOn).isEqualTo(d("2026-06-14"))
    }

    @Test
    fun `TIER_3 is uncapped while active`() {
        val e = effective(org(MembershipTier.TIER_3, start = "2026-06-15", end = "2027-06-14"))

        assertThat(MembershipAllowanceService.compute(e, 500, MembershipOverLimitMode.BLOCK).cap).isNull()
    }

    // --- update input: defaulting and validation ----------------------------------------------

    private fun update(
        tier: MembershipTier? = null,
        start: String? = null,
        end: String? = null,
    ) = UpdateReferringOrganisationInput(
        id = 1,
        name = "Pure org",
        membershipTier = tier,
        membershipStartDate = start,
        membershipEndDate = end,
    )

    @Test
    fun `setting a start with no end defaults the end to a year less a day later`() {
        val o = update(start = "2026-06-15").apply(org(MembershipTier.TIER_1, "2026-02-01", "2027-01-31"), today)

        assertThat(o.membershipStartDate).isEqualTo(d("2026-06-15"))
        assertThat(o.membershipEndDate).isEqualTo(d("2027-06-14"))
    }

    @Test
    fun `an explicit end is kept and is editable on its own`() {
        val o = update(end = "2027-03-31").apply(org(MembershipTier.TIER_1, "2026-02-01", "2027-01-31"), today)

        assertThat(o.membershipStartDate).isEqualTo(d("2026-02-01"))
        assertThat(o.membershipEndDate).isEqualTo(d("2027-03-31"))
    }

    @Test
    fun `an explicit end wins over the default when a start is also given`() {
        val o = update(start = "2026-06-15", end = "2026-12-31").apply(org(MembershipTier.TIER_1), today)

        assertThat(o.membershipEndDate).isEqualTo(d("2026-12-31"))
    }

    @Test
    fun `upgrading from FREE with no dates starts today and ends a year less a day later`() {
        val o = update(tier = MembershipTier.TIER_2).apply(org(MembershipTier.FREE, "2026-02-01", null), today)

        assertThat(o.membershipTier).isEqualTo(MembershipTier.TIER_2)
        assertThat(o.membershipStartDate).isEqualTo(today)
        assertThat(o.membershipEndDate).isEqualTo(d("2027-10-27"))
    }

    @Test
    fun `upgrading from FREE with a start but no end keeps that start and defaults the end from it`() {
        val o = update(tier = MembershipTier.TIER_1, start = "2026-09-01").apply(org(MembershipTier.FREE), today)

        assertThat(o.membershipStartDate).isEqualTo(d("2026-09-01"))
        assertThat(o.membershipEndDate).isEqualTo(d("2027-08-31"))
    }

    @Test
    fun `moving a lapsed paid organisation to another paid tier renews it from today`() {
        val o = update(tier = MembershipTier.TIER_3).apply(org(MembershipTier.TIER_1, "2025-03-01", "2026-02-28"), today)

        assertThat(o.membershipStartDate).isEqualTo(today)
        assertThat(o.membershipEndDate).isEqualTo(d("2027-10-27"))
    }

    @Test
    fun `moving between paid tiers mid-period keeps the running dates`() {
        val o = update(tier = MembershipTier.TIER_3).apply(org(MembershipTier.TIER_1, "2026-06-15", "2027-06-14"), today)

        assertThat(o.membershipTier).isEqualTo(MembershipTier.TIER_3)
        assertThat(o.membershipStartDate).isEqualTo(d("2026-06-15"))
        assertThat(o.membershipEndDate).isEqualTo(d("2027-06-14"))
    }

    @Test
    fun `re-sending the same lapsed tier does not silently renew it`() {
        val o = update(tier = MembershipTier.TIER_2).apply(org(MembershipTier.TIER_2, "2025-03-01", "2026-02-28"), today)

        assertThat(o.membershipEndDate).isEqualTo(d("2026-02-28"))
        assertThat(effective(o).expired).isTrue()
    }

    @Test
    fun `moving to FREE leaves the stored dates alone`() {
        val o = update(tier = MembershipTier.FREE).apply(org(MembershipTier.TIER_2, "2026-06-15", "2027-06-14"), today)

        assertThat(o.membershipTier).isEqualTo(MembershipTier.FREE)
        assertThat(o.membershipEndDate).isEqualTo(d("2027-06-14"))
    }

    @Test
    fun `a partial update leaves the end date and start date unchanged`() {
        val o = update().apply(org(MembershipTier.TIER_2, "2026-06-15", "2027-03-31"), today)

        assertThat(o.membershipStartDate).isEqualTo(d("2026-06-15"))
        assertThat(o.membershipEndDate).isEqualTo(d("2027-03-31"))
        assertThat(o.membershipTier).isEqualTo(MembershipTier.TIER_2)
    }

    @Test
    fun `an end before the start is rejected and nothing is changed`() {
        val target = org(MembershipTier.TIER_2, "2026-06-15", "2027-06-14")

        assertThatThrownBy { update(end = "2026-06-14").apply(target, today) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("membershipEndDate")
        assertThatThrownBy { update(start = "2026-07-01", end = "2026-06-30").apply(target, today) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(target.membershipEndDate).isEqualTo(d("2027-06-14"))
        assertThat(target.membershipStartDate).isEqualTo(d("2026-06-15"))
    }

    @Test
    fun `an end equal to the start is a valid one-day membership`() {
        val o = update(start = "2026-06-15", end = "2026-06-15").apply(org(MembershipTier.TIER_1), today)

        assertThat(o.membershipEndDate).isEqualTo(d("2026-06-15"))
    }

    @Test
    fun `an Instant-shaped end date is refused`() {
        assertThatThrownBy { update(end = "2027-06-14T00:00:00Z").apply(org(MembershipTier.TIER_1), today) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("YYYY-MM-DD")
    }
}
