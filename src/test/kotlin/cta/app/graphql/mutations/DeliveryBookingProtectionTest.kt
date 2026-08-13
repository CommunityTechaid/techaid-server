package cta.app.graphql.mutations

import cta.app.services.TurnstileService
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate

/**
 * Covers the bot-protection layers added to submitDeliveryBookingPublic: Turnstile rejection,
 * the per-IP throttle, and server-side feature-flag enforcement.
 *
 * Distinct test properties (max-requests=3, enforce-feature-flag=true) give this class an
 * isolated Spring context from DeliveryMutationsTest. The rate limiter is a real singleton whose
 * state persists across methods in that context, so each method uses a distinct CF-Connecting-IP
 * bucket, and bookings stay within the four Flyway-seeded offered days (two windows, capacity 4).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
@TestPropertySource(
    properties = [
        "delivery-booking.rate-limit.max-requests=3",
        "delivery-booking.enforce-feature-flag=true",
    ],
)
class DeliveryBookingProtectionTest {
    @MockBean
    lateinit var jwtDecoder: JwtDecoder

    // @MockBean is deprecated in Boot 3.4 but is the established pattern across this repo's tests.
    @MockBean
    lateinit var turnstile: TurnstileService

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun enableFlag() {
        // Default state per method: flag on, so rate-limit/turnstile tests reach the booking logic.
        setFlag(true)
    }

    private fun setFlag(enabled: Boolean) {
        jdbcTemplate.update("update feature_flags set enabled = ? where flag_key = 'delivery-booking'", enabled)
    }

    private fun graphQl(
        query: String,
        clientIp: String? = null,
    ): ResultActions {
        val request =
            post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query":"$query"}""")
        if (clientIp != null) request.header("CF-Connecting-IP", clientIp)
        return mockMvc.perform(request)
    }

    private fun bookingMutation(
        date: String,
        windowId: String = "1",
        email: String = "test@example.org",
        address: String = "1 Test Street, London SW9 8PR",
        ctaReference: Long = 4298,
    ): String =
        """mutation { submitDeliveryBookingPublic(input: { date: \"$date\", windowId: \"$windowId\", """ +
            """firstName: \"Test\", surname: \"Booker\", email: \"$email\", phone: \"07123456789\", """ +
            """address: \"$address\", ctaReference: $ctaReference }) { id date } }"""

    /** The seeded delivery days are Tuesday (2) and Thursday (4); advanceDays=4 caps the bookable set. */
    private fun offeredDates(count: Int): List<LocalDate> {
        val dates = mutableListOf<LocalDate>()
        var d = LocalDate.now().plusDays(1)
        while (dates.size < count) {
            if (d.dayOfWeek.value in setOf(2, 4)) dates.add(d)
            d = d.plusDays(1)
        }
        return dates
    }

    @Test
    fun `turnstile rejection is surfaced as a BAD_REQUEST error`() {
        Mockito
            .doThrow(DeliveryBookingException("We couldn't verify your browser. Please refresh the page and try again."))
            .`when`(turnstile)
            .verifyOrThrow(ArgumentMatchers.any(), ArgumentMatchers.any())

        graphQl(bookingMutation(offeredDates(1)[0].toString(), windowId = "2"), clientIp = "203.0.113.1")
            .andExpect(status().isOk)
            .andExpect(
                jsonPath("$.errors[0].message")
                    .value("We couldn't verify your browser. Please refresh the page and try again."),
            )
            // Pins the contract a later dashboard PR relies on to distinguish expected rejections.
            .andExpect(jsonPath("$.errors[0].extensions.classification").value("BAD_REQUEST"))
    }

    @Test
    fun `throttles submits from one ip but not a different ip`() {
        val dates = offeredDates(4)
        // Three fresh slots consume the budget (max-requests=3) for this bucket. Distinct refs:
        // these represent three different people, not repeat submits of the same booking.
        listOf(dates[0], dates[1], dates[2]).forEachIndexed { i, date ->
            graphQl(bookingMutation(date.toString(), windowId = "1", ctaReference = 970000L + i), clientIp = "203.0.113.2")
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.errors").doesNotExist())
        }

        // Fourth attempt from the same bucket is over budget and rejected before any DB work.
        graphQl(bookingMutation(dates[0].toString(), windowId = "1", ctaReference = 970004L), clientIp = "203.0.113.2")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errors[0].message").value("Too many booking attempts. Please wait a few minutes and try again."))

        // A different client IP has its own bucket and is not throttled.
        graphQl(bookingMutation(dates[3].toString(), windowId = "1", ctaReference = 970005L), clientIp = "203.0.113.9")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.submitDeliveryBookingPublic.id").isNotEmpty)
    }

    @Test
    fun `feature flag gate blocks bookings until the flag is enabled`() {
        val date = offeredDates(1)[0].toString()

        setFlag(false)
        graphQl(bookingMutation(date, windowId = "2", ctaReference = 970010L), clientIp = "203.0.113.3")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errors[0].message").value("Delivery booking is not currently available."))

        setFlag(true)
        graphQl(bookingMutation(date, windowId = "2", ctaReference = 970010L), clientIp = "203.0.113.3")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.submitDeliveryBookingPublic.id").isNotEmpty)
    }
}
