package cta.app.services

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Pure unit tests for the sliding-window limiter, driven by a mutable fake clock so window
 * behaviour is deterministic and needs no Spring context.
 */
class BookingRateLimiterTest {
    /** A [Clock] whose instant can be advanced by the test. */
    private class MutableClock(
        var now: Instant,
    ) : Clock() {
        override fun instant(): Instant = now

        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId): Clock = this

        fun advanceSeconds(seconds: Long) {
            now = now.plusSeconds(seconds)
        }
    }

    private val start = Instant.parse("2026-01-01T00:00:00Z")

    private fun limiter(
        clock: Clock,
        enabled: Boolean = true,
        maxRequests: Int = 3,
        windowSeconds: Long = 60,
    ) = BookingRateLimiter(clock, enabled, maxRequests, windowSeconds)

    @Test
    fun `allows up to the configured maximum then blocks`() {
        val clock = MutableClock(start)
        val limiter = limiter(clock, maxRequests = 3, windowSeconds = 60)

        assertTrue(limiter.tryAcquire("ip"))
        assertTrue(limiter.tryAcquire("ip"))
        assertTrue(limiter.tryAcquire("ip"))
        // Fourth attempt inside the same window is over budget.
        assertFalse(limiter.tryAcquire("ip"))
    }

    @Test
    fun `window slides so budget frees up once old hits expire`() {
        val clock = MutableClock(start)
        val limiter = limiter(clock, maxRequests = 2, windowSeconds = 60)

        assertTrue(limiter.tryAcquire("ip"))
        assertTrue(limiter.tryAcquire("ip"))
        assertFalse(limiter.tryAcquire("ip"))

        // Advance past the window: the earlier hits are evicted and budget is restored.
        clock.advanceSeconds(61)
        assertTrue(limiter.tryAcquire("ip"))
        assertTrue(limiter.tryAcquire("ip"))
        assertFalse(limiter.tryAcquire("ip"))
    }

    @Test
    fun `keys are throttled independently`() {
        val clock = MutableClock(start)
        val limiter = limiter(clock, maxRequests = 1, windowSeconds = 60)

        assertTrue(limiter.tryAcquire("a"))
        assertFalse(limiter.tryAcquire("a"))
        // A different key has its own budget.
        assertTrue(limiter.tryAcquire("b"))
        assertFalse(limiter.tryAcquire("b"))
    }

    @Test
    fun `disabled limiter always allows`() {
        val clock = MutableClock(start)
        val limiter = limiter(clock, enabled = false, maxRequests = 1, windowSeconds = 60)

        repeat(50) { assertTrue(limiter.tryAcquire("ip")) }
    }

    @Test
    fun `prune drops keys whose window has fully expired`() {
        val clock = MutableClock(start)
        val limiter = limiter(clock, maxRequests = 1, windowSeconds = 60)

        assertTrue(limiter.tryAcquire("ip"))
        assertFalse(limiter.tryAcquire("ip"))

        clock.advanceSeconds(61)
        limiter.prune()
        // After pruning the expired key, its budget is available again.
        assertTrue(limiter.tryAcquire("ip"))
    }
}
