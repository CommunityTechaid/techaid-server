package cta.app.services

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory sliding-window rate limiter for the anonymous delivery-booking mutation.
 *
 * Scope note: state lives in this process only. Production runs a single Container Apps
 * replica (KEDA min 0 / max 1), so one bucket per client IP is sufficient; the state resets
 * whenever the replica scales to zero, which is accepted for a soft throttle. Do not rely on
 * this for hard quotas.
 *
 * The [Clock] is injectable so unit tests can advance time deterministically. Spring wires the
 * secondary constructor from config; tests call the primary constructor directly with a fake
 * clock and explicit limits.
 */
@Service
class BookingRateLimiter(
    private val clock: Clock,
    private val enabled: Boolean,
    private val maxRequests: Int,
    private val windowSeconds: Long,
) {
    @Autowired
    constructor(
        @Value("\${delivery-booking.rate-limit.enabled:true}") enabled: Boolean,
        @Value("\${delivery-booking.rate-limit.max-requests:5}") maxRequests: Int,
        @Value("\${delivery-booking.rate-limit.window-seconds:600}") windowSeconds: Long,
    ) : this(Clock.systemUTC(), enabled, maxRequests, windowSeconds)

    private val hits = ConcurrentHashMap<String, ArrayDeque<Instant>>()

    /**
     * Records an attempt for [key] and returns true if it is within the allowed budget for the
     * current window. Returns false (and does not record the attempt) once the budget is spent.
     */
    fun tryAcquire(key: String): Boolean {
        if (!enabled) return true

        val now = clock.instant()
        val cutoff = now.minusSeconds(windowSeconds)
        val deque = hits.computeIfAbsent(key) { ArrayDeque() }

        synchronized(deque) {
            // Evict timestamps that have slid out of the window; keeps each deque bounded to at
            // most maxRequests entries and lets a fully-idle key be pruned below.
            while (deque.isNotEmpty() && deque.first().isBefore(cutoff)) {
                deque.removeFirst()
            }

            if (deque.size >= maxRequests) return false

            deque.addLast(now)
            return true
        }
    }

    /**
     * Drops keys whose entire window has expired. Not called on the hot path; exposed so a
     * caller (or a test) can reclaim memory from IPs that went idle without waiting for a
     * scale-to-zero. Idle keys otherwise reset when the single replica recycles.
     */
    fun prune() {
        val cutoff = clock.instant().minusSeconds(windowSeconds)
        hits.forEach { (key, deque) ->
            synchronized(deque) {
                while (deque.isNotEmpty() && deque.first().isBefore(cutoff)) {
                    deque.removeFirst()
                }
                if (deque.isEmpty()) hits.remove(key, deque)
            }
        }
    }
}
