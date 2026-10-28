package cta.app

import cta.app.services.EffectiveMembership
import cta.app.services.MembershipAllowanceService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/**
 * The membership-year arithmetic and the allowance derivation (#311), with no Spring and no
 * database. Every boundary the issue lists is pinned here: the 1 February default, a mid-year
 * start, roll-forward across a period boundary, and the tier caps (TIER_3 uncapped).
 *
 * Dates are London calendar dates. The instant bounds are checked across the GMT/BST switch
 * because that is where "created in the current period" silently goes wrong by an hour — a request
 * made at 00:30 BST on the first day of a period is still the previous day in UTC.
 */
class MembershipPeriodsTest {
    private fun d(s: String) = LocalDate.parse(s)

    // --- default start date -------------------------------------------------------------------

    @Test
    fun `the default membership year starts on 1 February of the current year`() {
        assertThat(MembershipPeriods.defaultStartDate(d("2026-10-28"))).isEqualTo(d("2026-02-01"))
        assertThat(MembershipPeriods.defaultStartDate(d("2026-02-01"))).isEqualTo(d("2026-02-01"))
    }

    @Test
    fun `before 1 February the current membership year began the previous February`() {
        assertThat(MembershipPeriods.defaultStartDate(d("2026-01-31"))).isEqualTo(d("2025-02-01"))
        assertThat(MembershipPeriods.defaultStartDate(d("2026-01-01"))).isEqualTo(d("2025-02-01"))
    }

    @Test
    fun `a new organisation defaults to the FREE tier and the current 1 February start`() {
        val org = ReferringOrganisation(name = "Default org")

        assertThat(org.membershipTier).isEqualTo(MembershipTier.FREE)
        assertThat(org.membershipStartDate).isEqualTo(MembershipPeriods.defaultStartDate())
        assertThat(org.membershipStartDate.monthValue).isEqualTo(2)
        assertThat(org.membershipStartDate.dayOfMonth).isEqualTo(1)
    }

    // --- period computation -------------------------------------------------------------------

    @Test
    fun `the period end is start plus one year minus one day and is never the next start`() {
        val period = MembershipPeriods.periodFor(d("2026-02-01"), today = d("2026-10-28"))

        assertThat(period.start).isEqualTo(d("2026-02-01"))
        assertThat(period.end).isEqualTo(d("2027-01-31"))
    }

    @Test
    fun `a mid-year start runs a year from that day`() {
        val period = MembershipPeriods.periodFor(d("2026-06-15"), today = d("2026-10-28"))

        assertThat(period.start).isEqualTo(d("2026-06-15"))
        assertThat(period.end).isEqualTo(d("2027-06-14"))
    }

    @Test
    fun `the last day of a period is still in it and the next day rolls forward`() {
        val start = d("2025-02-01")

        val lastDay = MembershipPeriods.periodFor(start, today = d("2026-01-31"))
        assertThat(lastDay.start).isEqualTo(d("2025-02-01"))
        assertThat(lastDay.end).isEqualTo(d("2026-01-31"))

        val firstDayOfNext = MembershipPeriods.periodFor(start, today = d("2026-02-01"))
        assertThat(firstDayOfNext.start).isEqualTo(d("2026-02-01"))
        assertThat(firstDayOfNext.end).isEqualTo(d("2027-01-31"))
    }

    @Test
    fun `a membership several years old rolls forward the right number of years`() {
        val period = MembershipPeriods.periodFor(d("2020-03-10"), today = d("2026-10-28"))

        assertThat(period.start).isEqualTo(d("2026-03-10"))
        assertThat(period.end).isEqualTo(d("2027-03-09"))
    }

    @Test
    fun `a leap-day start keeps its anniversary and does not drift`() {
        val start = d("2024-02-29")

        // 2025 has no 29 Feb: the year turns over on the 28th...
        val y2025 = MembershipPeriods.periodFor(start, today = d("2025-03-01"))
        assertThat(y2025.start).isEqualTo(d("2025-02-28"))
        assertThat(y2025.end).isEqualTo(d("2026-02-27"))

        // ...but it is taken from the original start each time, so the next leap year is the 29th again.
        val y2028 = MembershipPeriods.periodFor(start, today = d("2028-03-01"))
        assertThat(y2028.start).isEqualTo(d("2028-02-29"))
    }

    @Test
    fun `a start date in the future yields its first period`() {
        val period = MembershipPeriods.periodFor(d("2027-02-01"), today = d("2026-10-28"))

        assertThat(period.start).isEqualTo(d("2027-02-01"))
        assertThat(period.end).isEqualTo(d("2028-01-31"))
    }

    // --- London instants ----------------------------------------------------------------------

    @Test
    fun `a winter period starts at midnight GMT`() {
        val period = MembershipPeriods.periodFor(d("2026-02-01"), today = d("2026-10-28"))

        assertThat(period.startInstant).isEqualTo(Instant.parse("2026-02-01T00:00:00Z"))
        assertThat(period.endExclusiveInstant).isEqualTo(Instant.parse("2027-02-01T00:00:00Z"))
    }

    @Test
    fun `a summer period starts at midnight BST which is 23 00 UTC the evening before`() {
        val period = MembershipPeriods.periodFor(d("2026-04-01"), today = d("2026-10-28"))

        assertThat(period.startInstant).isEqualTo(Instant.parse("2026-03-31T23:00:00Z"))
        // The period ends in April 2027, also BST.
        assertThat(period.endExclusiveInstant).isEqualTo(Instant.parse("2027-03-31T23:00:00Z"))
    }

    // --- input parsing ------------------------------------------------------------------------

    @Test
    fun `input dates are plain YYYY-MM-DD and an Instant-shaped string is refused`() {
        assertThat(MembershipPeriods.parseDate("membershipStartDate", "2026-02-01")).isEqualTo(d("2026-02-01"))

        assertThatThrownBy { MembershipPeriods.parseDate("membershipStartDate", "2026-02-01T00:00:00Z") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("YYYY-MM-DD")
        assertThatThrownBy { MembershipPeriods.parseDate("membershipStartDate", "01/02/2026") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    // --- allowance derivation -----------------------------------------------------------------

    private val period = MembershipPeriods.periodFor(d("2026-02-01"), today = d("2026-10-28"))

    private fun compute(
        tier: MembershipTier,
        used: Int,
        mode: MembershipOverLimitMode = MembershipOverLimitMode.GRACE,
    ) = MembershipAllowanceService.compute(EffectiveMembership(tier, period), used, mode)

    @Test
    fun `the tier caps are 10 20 40 and uncapped`() {
        assertThat(MembershipTier.FREE.cap).isEqualTo(10)
        assertThat(MembershipTier.TIER_1.cap).isEqualTo(20)
        assertThat(MembershipTier.TIER_2.cap).isEqualTo(40)
        assertThat(MembershipTier.TIER_3.cap).isNull()
    }

    @Test
    fun `a capped tier is OK until one request remains then LAST_REQUEST then LIMIT_REACHED`() {
        assertThat(compute(MembershipTier.FREE, 0).state).isEqualTo(MembershipState.OK)
        assertThat(compute(MembershipTier.FREE, 8).state).isEqualTo(MembershipState.OK)

        val last = compute(MembershipTier.FREE, 9)
        assertThat(last.state).isEqualTo(MembershipState.LAST_REQUEST)
        assertThat(last.remaining).isEqualTo(1)

        val reached = compute(MembershipTier.FREE, 10)
        assertThat(reached.state).isEqualTo(MembershipState.LIMIT_REACHED)
        assertThat(reached.remaining).isEqualTo(0)
    }

    @Test
    fun `FREE TIER_1 and TIER_2 behave the same at their own caps`() {
        listOf(MembershipTier.FREE, MembershipTier.TIER_1, MembershipTier.TIER_2).forEach { tier ->
            val cap = tier.cap!!
            assertThat(compute(tier, cap - 1).state).`as`("$tier at cap-1").isEqualTo(MembershipState.LAST_REQUEST)
            assertThat(compute(tier, cap).state).`as`("$tier at cap").isEqualTo(MembershipState.LIMIT_REACHED)
        }
    }

    @Test
    fun `remaining is never negative even when over the cap`() {
        val over = compute(MembershipTier.FREE, 12)

        assertThat(over.remaining).isEqualTo(0)
        assertThat(over.state).isEqualTo(MembershipState.LIMIT_REACHED)
    }

    @Test
    fun `TIER_3 is uncapped whatever the usage`() {
        val heavy = compute(MembershipTier.TIER_3, 500)

        assertThat(heavy.cap).isNull()
        assertThat(heavy.remaining).isNull()
        assertThat(heavy.state).isEqualTo(MembershipState.OK)
        assertThat(heavy.graceUsed).isEqualTo(0)
    }

    @Test
    fun `graceUsed counts requests past the cap in GRACE mode and is zero in BLOCK mode`() {
        assertThat(compute(MembershipTier.FREE, 10).graceUsed).isEqualTo(0)
        assertThat(compute(MembershipTier.FREE, 11).graceUsed).isEqualTo(1)
        assertThat(compute(MembershipTier.FREE, 12).graceUsed).isEqualTo(2)
        assertThat(compute(MembershipTier.FREE, 12, MembershipOverLimitMode.BLOCK).graceUsed).isEqualTo(0)
    }

    @Test
    fun `the period is carried through to the allowance`() {
        val m = compute(MembershipTier.TIER_1, 3)

        assertThat(m.periodStart).isEqualTo(d("2026-02-01"))
        assertThat(m.periodEnd).isEqualTo(d("2027-01-31"))
        assertThat(m.cap).isEqualTo(20)
        assertThat(m.used).isEqualTo(3)
        assertThat(m.remaining).isEqualTo(17)
    }

    @Test
    fun `completed requests count towards membership but declined and cancelled do not`() {
        // The predicate is deliberately not CLOSED_REQUEST_STATUSES: a completed request is closed
        // for the open-request cap but it still used the organisation's yearly allowance.
        assertThat(MEMBERSHIP_NON_COUNTING_STATUSES)
            .containsExactlyInAnyOrder(DeviceRequestStatus.REQUEST_DECLINED, DeviceRequestStatus.REQUEST_CANCELLED)
        assertThat(CLOSED_REQUEST_STATUSES).contains(DeviceRequestStatus.REQUEST_COMPLETED)
        assertThat(MEMBERSHIP_NON_COUNTING_STATUSES).doesNotContain(DeviceRequestStatus.REQUEST_COMPLETED)
        assertThat(MEMBERSHIP_NON_COUNTING_STATUSES).doesNotContain(DeviceRequestStatus.REQUEST_COLLECTION_DELIVERY_FAILED)
    }
}
