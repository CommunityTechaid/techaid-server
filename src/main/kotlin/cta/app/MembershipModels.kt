package cta.app

import cta.app.services.LONDON_ZONE
import java.time.Instant
import java.time.LocalDate

/**
 * An organisation's membership tier (#311). [cap] is the number of requests allowed per membership
 * year; null means uncapped.
 */
enum class MembershipTier(
    val cap: Int?,
) {
    FREE(10),
    TIER_1(20),
    TIER_2(40),
    TIER_3(null),
}

enum class MembershipState {
    OK,
    LAST_REQUEST,
    LIMIT_REACHED,
}

/** What happens to a request made once the allowance is used up. See MembershipAllowanceService. */
enum class MembershipOverLimitMode {
    GRACE,
    BLOCK,
}

/** One membership year: [start] to [end] inclusive, both calendar dates in Europe/London. */
data class MembershipPeriod(
    val start: LocalDate,
    val end: LocalDate,
) {
    /** First instant of the period (London midnight on [start]). */
    val startInstant: Instant get() = start.atStartOfDay(LONDON_ZONE).toInstant()

    /** First instant AFTER the period (London midnight the day after [end]); use as an exclusive bound. */
    val endExclusiveInstant: Instant get() = end.plusDays(1).atStartOfDay(LONDON_ZONE).toInstant()
}

/**
 * The membership-year arithmetic, kept pure (no Spring, no clock of its own) so every boundary
 * case is a plain unit test. All dates are London calendar dates: a request made at 00:30 BST on
 * 1 February belongs to the new year even though it is still 31 January in UTC.
 */
object MembershipPeriods {
    /** Month and day a membership year starts unless staff set another date for an organisation. */
    const val DEFAULT_START_MONTH = 2

    fun today(): LocalDate = LocalDate.now(LONDON_ZONE)

    /**
     * Parses an input date. Strictly a plain `YYYY-MM-DD`: an Instant-shaped string is refused
     * rather than truncated, because silently dropping a time-of-day is how a date ends up a day
     * out across BST.
     */
    fun parseDate(
        field: String,
        value: String,
    ): LocalDate =
        try {
            LocalDate.parse(value)
        } catch (e: java.time.format.DateTimeParseException) {
            throw IllegalArgumentException("$field must be a plain YYYY-MM-DD date, got '$value'", e)
        }

    /** 1 February of the membership year containing [today]: this February, or last if before 1 Feb. */
    fun defaultStartDate(today: LocalDate = today()): LocalDate {
        val year = if (today.monthValue < DEFAULT_START_MONTH) today.year - 1 else today.year
        return LocalDate.of(year, DEFAULT_START_MONTH, 1)
    }

    /**
     * The period containing [today], for an organisation whose membership is anchored at [start].
     * The period rolls forward by whole years from [start]; nothing is stored, so there is no
     * renewal job. A [start] in the future yields the first period (nothing has been used in it).
     *
     * Anniversaries are always taken from the ORIGINAL start (`start.plusYears(n)`), never chained,
     * so a 29 February start does not drift to the 28th for good after one non-leap year.
     */
    fun periodFor(
        start: LocalDate,
        today: LocalDate = today(),
    ): MembershipPeriod {
        var years = 0L
        while (!start.plusYears(years + 1).isAfter(today)) years++
        val periodStart = start.plusYears(years)
        return MembershipPeriod(periodStart, start.plusYears(years + 1).minusDays(1))
    }
}
