package cta.app.graphql.mutations

import com.fasterxml.jackson.databind.ObjectMapper
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
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
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Borough-specific delivery days (sheet row 23): deliveryAvailabilityPublic and
 * submitDeliveryBookingPublic both defer to the single DeliveryService.checkBoroughDaySchedule so
 * the two can't drift, gated behind delivery_config.boroughSchedulingEnabled (off by default, and
 * independent of the unrelated borough-availability-rules feature flag).
 *
 * A distinct @TestPropertySource forks this class into its own cached Spring context — and
 * therefore its own embedded Postgres — mirroring DeliveryBookingConcurrencyTest/
 * DeliveryBookingProtectionTest, so toggling boroughSchedulingEnabled and seeding
 * delivery_day_boroughs rows here can never contaminate the sibling delivery test classes that
 * share the default context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
@TestPropertySource(properties = ["delivery-booking.rate-limit.max-requests=1000"])
class DeliveryBoroughDaySchedulingTest {
    @MockBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    private val mapper = ObjectMapper()

    /** Methods share this class's context/database, so undo whatever a test toggled/seeded. */
    @AfterEach
    fun resetSchedulingState() {
        jdbcTemplate.update("update delivery_config set borough_scheduling_enabled = false where id = 1")
        jdbcTemplate.update("delete from delivery_day_boroughs")
    }

    private fun graphQl(query: String): ResultActions =
        mockMvc.perform(
            post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query":"$query"}"""),
        )

    /**
     * device_requests has few NOT NULL columns beyond id (is_prepped, is_sales); the entity
     * constructor otherwise requires a ReferringOrganisationContact relation we don't want to
     * build here, so seed with a raw insert instead (mirrors DeliveryMutationsTest).
     */
    private fun seedDeviceRequest(
        id: Long,
        borough: String?,
        status: String = "PROCESSING_EQUALITIES_DATA_COMPLETE",
    ) {
        jdbcTemplate.update(
            """
            insert into device_requests (id, is_prepped, is_sales, status, borough, created_at, updated_at)
            values (?, false, false, ?, ?, now(), now())
            """.trimIndent(),
            id,
            status,
            borough,
        )
    }

    private fun setSchedulingEnabled(enabled: Boolean) {
        jdbcTemplate.update("update delivery_config set borough_scheduling_enabled = ? where id = 1", enabled)
    }

    private fun restrictDay(
        dayOfWeek: Int,
        vararg boroughs: String,
    ) {
        boroughs.forEach { borough ->
            jdbcTemplate.update(
                "insert into delivery_day_boroughs (id, day_of_week, borough) " +
                    "values (nextval('delivery_day_boroughs_sequence'), ?, ?)",
                dayOfWeek,
                borough,
            )
        }
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

    private fun availabilityQuery(ctaReference: Long): String =
        """query { deliveryAvailabilityPublic(ctaReference: $ctaReference) { date } }"""

    private fun availableDates(ctaReference: Long): List<String> {
        val body =
            graphQl(availabilityQuery(ctaReference))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        return mapper.readTree(body).at("/data/deliveryAvailabilityPublic").map { it.get("date").asText() }
    }

    private fun bookingMutation(
        date: String,
        ctaReference: Long,
        windowId: String = "1",
    ): String =
        """mutation { submitDeliveryBookingPublic(input: { date: \"$date\", windowId: \"$windowId\", """ +
            """firstName: \"Test\", surname: \"Booker\", email: \"test@example.org\", phone: \"07123456789\", """ +
            """address: \"1 Test Street, London SW9 8PR\", ctaReference: $ctaReference }) { id date } }"""

    @Test
    fun `toggle off offers every day regardless of any configured borough restriction`() {
        val requestId = 981000L
        seedDeviceRequest(requestId, borough = "Southwark")
        val date = offeredDates(1)[0]
        // Would exclude Southwark if the toggle were on; it isn't, so this must be ignored.
        restrictDay(date.dayOfWeek.value, "Lambeth")
        setSchedulingEnabled(false)

        assertThat(availableDates(requestId)).contains(date.toString())

        graphQl(bookingMutation(date.toString(), requestId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.submitDeliveryBookingPublic.id").isNotEmpty)
    }

    @Test
    fun `toggle on with no boroughs configured for the weekday offers the day`() {
        val requestId = 981001L
        seedDeviceRequest(requestId, borough = "Westminster")
        setSchedulingEnabled(true)
        val date = offeredDates(2)[1]

        assertThat(availableDates(requestId)).contains(date.toString())

        graphQl(bookingMutation(date.toString(), requestId, windowId = "2"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.submitDeliveryBookingPublic.id").isNotEmpty)
    }

    @Test
    fun `toggle on with the weekday restricted to a different borough withholds the day and rejects submit`() {
        val requestId = 981002L
        seedDeviceRequest(requestId, borough = "Southwark")
        val date = offeredDates(3)[2]
        restrictDay(date.dayOfWeek.value, "Lambeth")
        setSchedulingEnabled(true)

        assertThat(availableDates(requestId)).doesNotContain(date.toString())

        val dayName = date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        graphQl(bookingMutation(date.toString(), requestId))
            .andExpect(status().isOk)
            .andExpect(
                jsonPath("$.errors[0].message")
                    .value("Deliveries to Southwark are not available on $dayName. Please choose a different day."),
            )
    }

    @Test
    fun `toggle on with an unresolvable borough fails open, offering the day and allowing submit`() {
        val requestId = 981003L
        seedDeviceRequest(requestId, borough = null)
        val date = offeredDates(4)[3]
        restrictDay(date.dayOfWeek.value, "Lambeth")
        setSchedulingEnabled(true)

        assertThat(availableDates(requestId)).contains(date.toString())

        graphQl(bookingMutation(date.toString(), requestId, windowId = "2"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.submitDeliveryBookingPublic.id").isNotEmpty)
    }
}
