package cta.app.schedulingtasks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.support.CronExpression
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The retention slot is constrained by infrastructure, not preference: production's KEDA rule
 * keeps the container warm only Mon-Fri 08:00-20:00 London and scales to zero outside it, and
 * Spring never backfills a firing that had no container to run on. A cron edit that drifts
 * outside that window would not fail loudly — retention would simply stop happening.
 */
class GdprDonorCleanupScheduleTest {
    private val london = ZoneId.of("Europe/London")

    private val scheduled: Scheduled =
        GdprDonorCleanup::class.java
            .getDeclaredMethod("runRetentionCleanup")
            .getAnnotation(Scheduled::class.java)

    @Test
    fun `the retention cron fires Friday 18-00 London`() {
        assertEquals("Europe/London", scheduled.zone)

        val expression = CronExpression.parse(scheduled.cron)
        val next = expression.next(ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, london))!!

        assertEquals(DayOfWeek.FRIDAY, next.dayOfWeek)
        assertEquals(18, next.hour)
        assertEquals(0, next.minute)
    }

    @Test
    fun `every firing for a year lands inside the KEDA warm window`() {
        val expression = CronExpression.parse(scheduled.cron)
        var at = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, london)

        repeat(52) {
            at = expression.next(at)!!
            assertTrue(
                at.dayOfWeek.value <= DayOfWeek.FRIDAY.value,
                "$at falls at the weekend, when the container is scaled to zero",
            )
            assertTrue(
                at.hour >= 8 && at.hour < 20,
                "$at is outside the KEDA warm window of 08:00-20:00 London",
            )
        }
    }
}
