package cta.app.services

import mu.KotlinLogging
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}

/**
 * In-memory sliding-window rate limiter for the anonymous delivery-booking surface.
 *
 * Scope note: state lives in this process only. Production runs a single Container Apps
 * replica (KEDA min 0 / max 1), so one bucket per client IP is sufficient; the state resets
 * whenever the replica scales to zero, which is accepted for a soft throttle. Do not rely on
 * this for hard quotas.
 *
 * Memory is bounded two ways. Idle keys are pruned on the write path once per window, so a
 * long-lived replica does not accumulate every IP it has ever seen. And [maxKeys] is a hard cap:
 * if the map is full of keys whose windows are all still live (many distinct callers inside one
 * window), a NEW key is refused rather than admitted, so the limiter fails closed instead of
 * growing without bound. Keys already tracked keep their own budget.
 *
 * Two beans of this class are wired by [cta.app.config.BookingRateLimiterConfig] — one for
 * submit, one for eligibility — each with its own budget, so exhausting one can never block the
 * other. See that class for why a shared bucket was a problem.
 *
 * The [Clock] is injectable so unit tests can advance time deterministically. Spring wires this
 * via the config class's @Bean methods; tests call this constructor directly with a fake clock
 * and explicit limits.
 */
class BookingRateLimiter(
    private val clock: Clock,
    private val enabled: Boolean,
    private val maxRequests: Int,
    private val windowSeconds: Long,
    private val maxKeys: Int = DEFAULT_MAX_KEYS,
) {
    private val hits = ConcurrentHashMap<String, ArrayDeque<Instant>>()

    @Volatile
    private var lastPrune: Instant = clock.instant()

    private val capWarned = AtomicBoolean(false)

    /**
     * Records an attempt for [key] and returns true if it is within the allowed budget for the
     * current window. Returns false (and does not record the attempt) once the budget is spent,
     * or when [key] is new and the key cap is reached.
     */
    fun tryAcquire(key: String): Boolean {
        if (!enabled) return true

        val now = clock.instant()
        if (!now.isBefore(lastPrune.plusSeconds(windowSeconds))) prune(now)

        if (!hits.containsKey(key) && hits.size >= maxKeys) {
            prune(now)
            if (hits.size >= maxKeys) {
                if (capWarned.compareAndSet(false, true)) {
                    logger.warn { "Rate limiter key cap ($maxKeys) reached; refusing new clients until windows expire" }
                }
                return false
            }
        }

        val cutoff = now.minusSeconds(windowSeconds)
        while (true) {
            val deque = hits.computeIfAbsent(key) { ArrayDeque() }
            val allowed =
                synchronized(deque) {
                    // A concurrent prune may have removed this (then empty) deque from the map
                    // after we fetched it; recording into it would lose the hit, so retry.
                    if (hits[key] !== deque) return@synchronized null

                    // Evict timestamps that have slid out of the window; keeps each deque bounded
                    // to at most maxRequests entries and lets a fully-idle key be pruned.
                    while (deque.isNotEmpty() && deque.first().isBefore(cutoff)) {
                        deque.removeFirst()
                    }

                    if (deque.size >= maxRequests) {
                        false
                    } else {
                        deque.addLast(now)
                        true
                    }
                }
            if (allowed != null) return allowed
        }
    }

    /** Drops keys whose entire window has expired. Called on the write path; public for tests. */
    fun prune() = prune(clock.instant())

    private fun prune(now: Instant) {
        lastPrune = now
        val cutoff = now.minusSeconds(windowSeconds)
        hits.forEach { (key, deque) ->
            synchronized(deque) {
                while (deque.isNotEmpty() && deque.first().isBefore(cutoff)) {
                    deque.removeFirst()
                }
                if (deque.isEmpty()) hits.remove(key, deque)
            }
        }
        if (hits.size < maxKeys) capWarned.set(false)
    }

    /** Number of client keys currently tracked; exposed so tests can assert memory is bounded. */
    fun trackedKeys(): Int = hits.size

    companion object {
        /**
         * Far above any legitimate number of distinct callers inside one window (the public forms
         * see tens of users a day), while bounding the map to a few MB at worst.
         */
        const val DEFAULT_MAX_KEYS = 10_000
    }
}
