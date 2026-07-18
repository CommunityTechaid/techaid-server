package cta.app.graphql.mutations

import cta.app.DeliveryBooking
import cta.app.DeliveryBookingRepository
import cta.app.DeliveryWindowRepository
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate

/**
 * Behaviour of the public submitDeliveryBookingPublic mutation against the Flyway-seeded
 * config (enabled, Tue/Thu, 1-day lead, 4 upcoming days, two windows of capacity 4).
 * Emails are disabled by default in tests, so submits have no mail side effects.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class DeliveryMutationsTest {
    @MockBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var bookingRepository: DeliveryBookingRepository

    @Autowired
    lateinit var windowRepository: DeliveryWindowRepository

    private fun graphQl(query: String): ResultActions =
        mockMvc.perform(
            post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query":"$query"}"""),
        )

    private fun bookingMutation(
        date: String,
        windowId: String = "1",
        email: String = "test@example.org",
        address: String = "1 Test Street, London SW9 8PR",
        ctaReference: String = "4298",
    ): String =
        """mutation { submitDeliveryBookingPublic(input: { date: \"$date\", windowId: \"$windowId\", """ +
            """firstName: \"Test\", surname: \"Booker\", email: \"$email\", phone: \"07123456789\", """ +
            """address: \"$address\", ctaReference: \"$ctaReference\" }) { id date } }"""

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

    @Test
    fun `books a slot on an offered day`() {
        graphQl(bookingMutation(offeredDates(1)[0].toString(), windowId = "2"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.submitDeliveryBookingPublic.id").isNotEmpty)
    }

    @Test
    fun `rejects a day beyond the advance window`() {
        var farFuture = LocalDate.now().plusYears(1)
        while (farFuture.dayOfWeek.value != 2) farFuture = farFuture.plusDays(1)

        graphQl(bookingMutation(farFuture.toString()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errors[0].message").value("Deliveries aren't available on that date"))
    }

    @Test
    fun `rejects an oversized address`() {
        graphQl(bookingMutation(offeredDates(1)[0].toString(), address = "x".repeat(1001)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errors[0].message").value(containsString("address")))
    }

    @Test
    fun `rejects an invalid email`() {
        graphQl(bookingMutation(offeredDates(1)[0].toString(), email = "not-an-email"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errors[0].message").value(containsString("email")))
    }

    @Test
    fun `rejects a booking once the window is full`() {
        // Window 1 (capacity 4) on the second offered day, so this test owns the slot.
        val date = offeredDates(2)[1].toString()
        // Distinct refs: these represent four different people, and the one-upcoming-booking
        // policy would otherwise block bookings 2-4 before capacity is even reached.
        (1..4).forEach { i ->
            graphQl(bookingMutation(date, ctaReference = "FULL-$i"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.errors").doesNotExist())
        }

        graphQl(bookingMutation(date))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errors[0].message").value("That delivery window is fully booked"))
    }

    @Test
    fun `blocks a second upcoming booking for the same CTA reference`() {
        // Window 1 on the first offered day, window 2 on the second offered day: neither slot
        // is touched by the other tests in this class, so capacity here is untouched.
        val firstDay = offeredDates(1)[0].toString()
        val secondDay = offeredDates(2)[1].toString()

        graphQl(bookingMutation(firstDay, windowId = "1", ctaReference = "DUP-1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errors").doesNotExist())

        graphQl(bookingMutation(secondDay, windowId = "2", ctaReference = "DUP-1"))
            .andExpect(status().isOk)
            .andExpect(
                jsonPath("$.errors[0].message")
                    .value(
                        "You already have an upcoming delivery booked. If you need to change it, " +
                            "please call us on 020 3488 2912.",
                    ),
            )
    }

    @Test
    fun `blocks a duplicate CTA reference regardless of case or whitespace`() {
        val firstDay = offeredDates(1)[0].toString()
        val secondDay = offeredDates(2)[1].toString()

        graphQl(bookingMutation(firstDay, windowId = "1", ctaReference = "dup-2"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errors").doesNotExist())

        graphQl(bookingMutation(secondDay, windowId = "2", ctaReference = " DUP-2 "))
            .andExpect(status().isOk)
            .andExpect(
                jsonPath("$.errors[0].message")
                    .value(
                        "You already have an upcoming delivery booked. If you need to change it, " +
                            "please call us on 020 3488 2912.",
                    ),
            )
    }

    @Test
    fun `does not block a new booking when the existing one for that reference is in the past`() {
        val window1 = windowRepository.findById(1L).orElseThrow()
        bookingRepository.save(
            DeliveryBooking(
                deliveryDate = LocalDate.now().minusDays(7),
                window = window1,
                firstName = "Past",
                surname = "Booker",
                email = "past@example.org",
                phone = "07123456789",
                address = "1 Test Street, London SW9 8PR",
                ctaReference = "PAST-1",
            ),
        )

        graphQl(bookingMutation(offeredDates(1)[0].toString(), windowId = "1", ctaReference = "PAST-1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.submitDeliveryBookingPublic.id").isNotEmpty)
    }
}
