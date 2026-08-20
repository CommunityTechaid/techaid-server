package cta.app.graphql.mutations

import com.fasterxml.jackson.databind.ObjectMapper
import cta.app.DeliveryBookingRepository
import cta.app.DeliveryWindowRepository
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Concurrency coverage for the delivery-booking locking strategy. These tests pin the two
 * invariants the booking-hardening work relies on, under genuinely parallel load against a real
 * (embedded) Postgres:
 *
 *  1. **Window capacity is never oversold.** [submitDeliveryBookingPublic] takes a pessimistic
 *     row lock on the window (`findByIdForUpdate`, PESSIMISTIC_WRITE) before the read-check-insert
 *     capacity check, so concurrent submits for one window serialise on that row: a window with
 *     capacity N accepts exactly N bookings even when many submits race.
 *  2. **One upcoming booking per CTA reference.** A Postgres advisory transaction lock keyed on the
 *     ctaReference (`acquireReferenceLock`) serialises same-ref
 *     submits — including ones aimed at *different* windows, which the per-window row lock does not
 *     cover — so the "already have an upcoming booking" check can't race: exactly one succeeds.
 *
 * Lock ordering (documented, not directly assertable from outside a transaction): the mutation
 * always takes the advisory ref lock BEFORE the pessimistic window row lock. That ordering is
 * deliberate — it avoids holding the scarce per-window row lock while blocked waiting on the ref
 * lock, and keeps a globally consistent advisory-then-row acquisition order so same-ref /
 * different-window races cannot deadlock. The tests below can only assert outcomes, but the
 * duplicate-reference test deliberately spreads its submits across *different* windows so that a
 * regression removing the advisory lock (leaving only per-window row locks, which would not
 * serialise cross-window same-ref submits) would let more than one booking through and fail.
 *
 * Genuine parallelism: each submit runs on its own thread from a fixed pool, driven through the
 * full MockMvc `/graphql` HTTP stack. Because this test class is not `@Transactional`, every
 * request opens its own Spring transaction on its own Hikari connection (no shared, serialising
 * test transaction) and commits independently — exactly like production. A [CyclicBarrier] sized to
 * the submit count makes every thread rendezvous immediately before calling the mutation, so the DB
 * work truly overlaps; if fewer threads than expected were live the barrier await would time out and
 * the test would fail. Each run also asserts that the expected number of distinct worker threads
 * participated, as a second, explicit proof the submits were not serialised.
 *
 * Bot protection is neutralised by the shared test config (turnstile disabled, feature-flag gate
 * off, rate-limit cap 1000), so all eight submits from the single MockMvc client IP (127.0.0.1)
 * exercise only the locks and never trip Turnstile or the per-IP throttle.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
@TestPropertySource(
    // Mirrors DeliveryBookingProtectionTest: a distinct property forks this class into its own
    // cached Spring context — and therefore its own embedded Postgres — so mutating window
    // capacities and leaving bookings behind here can never contaminate DeliveryMutationsTest,
    // which shares the otherwise-identical default context/datasource. The value (a high cap,
    // matching the shared test config) also documents that the eight submits from the single
    // MockMvc client IP must not trip the per-IP throttle.
    properties = ["delivery-booking.rate-limit.max-requests=1000"],
)
class DeliveryBookingConcurrencyTest {
    @MockBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var bookingRepository: DeliveryBookingRepository

    @Autowired
    lateinit var windowRepository: DeliveryWindowRepository

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    private val mapper = ObjectMapper()

    @BeforeEach
    fun reset() {
        // Each method owns the whole booking table and window capacities: no committed state leaks
        // between the two concurrency scenarios.
        bookingRepository.deleteAll()
        jdbcTemplate.update("update delivery_windows set capacity = 4")
    }

    /** The seeded delivery days are Tuesday (2) and Thursday (4) with a 1-day lead time. */
    private fun offeredDates(count: Int): List<LocalDate> {
        val dates = mutableListOf<LocalDate>()
        var d = LocalDate.now().plusDays(1)
        while (dates.size < count) {
            if (d.dayOfWeek.value in setOf(2, 4)) dates.add(d)
            d = d.plusDays(1)
        }
        return dates
    }

    private fun bookingMutation(
        date: String,
        windowId: String,
        ctaReference: Long,
        email: String,
    ): String =
        """mutation { submitDeliveryBookingPublic(input: { date: \"$date\", windowId: \"$windowId\", """ +
            """firstName: \"Test\", surname: \"Booker\", email: \"$email\", phone: \"07123456789\", """ +
            """address: \"1 Test Street, London SW9 8PR\", ctaReference: $ctaReference }) { id date } }"""

    private data class ConcurrentRun(
        val bodies: List<String>,
        val threadNames: Set<String>,
    )

    /**
     * Fires every [queries] entry as a concurrent MockMvc `/graphql` submit. All tasks rendezvous on
     * a barrier immediately before the HTTP call so their transactions genuinely overlap; the raw
     * response bodies come back in submission order alongside the set of worker threads that ran.
     */
    private fun submitConcurrently(queries: List<String>): ConcurrentRun {
        val n = queries.size
        val barrier = CyclicBarrier(n)
        val pool = Executors.newFixedThreadPool(n)
        val threadNames = ConcurrentHashMap.newKeySet<String>()
        try {
            val futures =
                queries.map { query ->
                    pool.submit<String> {
                        // Rendezvous: no thread proceeds to the mutation until all n are here, forcing
                        // the capacity check / ref lock to be contended rather than sequential.
                        barrier.await(15, TimeUnit.SECONDS)
                        threadNames.add(Thread.currentThread().name)
                        mockMvc
                            .perform(
                                post("/graphql")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("""{"query":"$query"}"""),
                            ).andReturn()
                            .response.contentAsString
                    }
                }
            val bodies = futures.map { it.get(30, TimeUnit.SECONDS) }
            return ConcurrentRun(bodies, threadNames.toSet())
        } finally {
            pool.shutdownNow()
        }
    }

    /** The GraphQL error message for a response, or null if the submit succeeded. */
    private fun errorMessage(body: String): String? {
        val errors = mapper.readTree(body).get("errors")
        return if (errors != null && !errors.isEmpty) errors[0].get("message").asText() else null
    }

    @Test
    fun `a window with capacity N accepts exactly N bookings under parallel load`() {
        val capacity = 2
        val date = offeredDates(2)[1]
        jdbcTemplate.update("update delivery_windows set capacity = ? where id = 1", capacity)

        // Eight distinct references (distinct people): the per-reference dedup never fires, so the
        // only thing that can cap acceptances is the window row lock + capacity check.
        val queries =
            (1..8).map { i ->
                bookingMutation(date.toString(), windowId = "1", ctaReference = 960000L + i, email = "cap$i@example.org")
            }

        val run = submitConcurrently(queries)

        // Proof of genuine parallelism: all eight tasks ran on distinct threads (only possible
        // because the barrier held them until all eight were live).
        assertThat(run.threadNames).hasSize(8)

        val messages = run.bodies.map { errorMessage(it) }
        val successes = messages.count { it == null }
        val fullFailures = messages.filter { it == "That delivery window is fully booked" }

        assertThat(successes).isEqualTo(capacity)
        assertThat(fullFailures).hasSize(8 - capacity)
        // No submit failed for any *other* reason (e.g. a lock timeout or dedup misfire).
        assertThat(messages.filter { it != null }).allMatch { it == "That delivery window is fully booked" }
        // The durable truth: exactly N rows persisted for that window/day.
        assertThat(bookingRepository.countByDeliveryDateAndWindowId(date, 1L)).isEqualTo(capacity.toLong())
    }

    @Test
    fun `the same cta reference gets exactly one upcoming booking under parallel load`() {
        // Ample capacity everywhere so capacity can never be the limiter — only the advisory ref
        // lock + upcoming-booking check decides the outcome.
        jdbcTemplate.update("update delivery_windows set capacity = 100")

        // One reference submitted eight times, spread across four offered days and both windows:
        // eight DISTINCT slots, so the per-window row lock never serialises them — isolating the
        // advisory reference lock as the thing that must enforce the single booking.
        val dates = offeredDates(4)
        val windows = windowRepository.findByActiveTrueOrderBySortOrderAsc()
        val raceRef = 960100L

        val queries =
            (0 until 8).map { i ->
                val date = dates[i % dates.size]
                val window = windows[i % windows.size]
                bookingMutation(
                    date.toString(),
                    windowId = window.id.toString(),
                    ctaReference = raceRef,
                    email = "dup$i@example.org",
                )
            }

        val run = submitConcurrently(queries)

        assertThat(run.threadNames).hasSize(8)

        val duplicateMessage =
            "You already have an upcoming delivery booked. If you need to change it, " +
                "please call us on 020 3488 7742."
        val messages = run.bodies.map { errorMessage(it) }
        val successes = messages.count { it == null }

        assertThat(successes).isEqualTo(1)
        assertThat(messages.filter { it != null }).hasSize(7).allMatch { it == duplicateMessage }
        // The durable truth: exactly one row persisted for that reference.
        val persisted = bookingRepository.findAll().count { it.ctaReference == raceRef }
        assertThat(persisted).isEqualTo(1)
    }
}
