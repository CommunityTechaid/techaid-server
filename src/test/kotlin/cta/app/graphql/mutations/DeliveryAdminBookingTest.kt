package cta.app.graphql.mutations

import cta.app.DeliveryBookingRepository
import cta.app.DeviceRequestRepository
import cta.app.DeviceRequestStatus
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Admin exception bookings (#73). Policy (Tony, 2026-11-04): admins only; the booking does not
 * take a slot (it isn't counted against the window's capacity); any day, not just offered days;
 * the normal confirmation email and request update; recorded as an exception with who made it.
 *
 * Against the Flyway-seeded config: Tue/Thu, 1-day lead, 4 upcoming days. The Spring context, and
 * therefore the database, is shared with the other delivery test classes, and they already use
 * every seeded day/window slot. So any test here that makes PUBLIC bookings does it on its own
 * temporary window (see [withOwnWindow]); admin bookings take no slot, so they can use the seeded ones.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class DeliveryAdminBookingTest {
    @MockitoBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var bookings: DeliveryBookingRepository

    @Autowired
    lateinit var deviceRequests: DeviceRequestRepository

    @Autowired
    lateinit var jdbc: JdbcTemplate

    private fun admin(query: String): ResultActions =
        mockMvc.perform(
            post("/graphql")
                .with(
                    jwt()
                        .jwt { it.claim("https://test.example.com/email", "admin@example.org") }
                        .authorities(SimpleGrantedAuthority("app:admin")),
                ).contentType(MediaType.APPLICATION_JSON)
                .content("""{"query":"$query"}"""),
        )

    private fun anonymous(query: String): ResultActions =
        mockMvc.perform(
            post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query":"$query"}"""),
        )

    private fun seedRequest(
        id: Long,
        status: String,
    ) {
        jdbc.update(
            """
            insert into device_requests (id, is_prepped, is_sales, status, created_at, updated_at)
            values (?, false, false, ?, now(), now())
            """.trimIndent(),
            id,
            status,
        )
    }

    /** Runs [block] with a temporary active window of [capacity], deactivated afterwards. */
    private fun withOwnWindow(
        id: Long,
        capacity: Int,
        block: (windowId: String) -> Unit,
    ) {
        jdbc.update(
            "insert into delivery_windows (id, name, start_time, end_time, icon, capacity, sort_order, active) " +
                "values (?, ?, '9:00am', '10:00am', '', ?, 99, true)",
            id,
            "Test window $id",
            capacity,
        )
        try {
            block(id.toString())
        } finally {
            jdbc.update("update delivery_windows set active = false where id = ?", id)
        }
    }

    private fun offeredDates(count: Int): List<LocalDate> {
        val dates = mutableListOf<LocalDate>()
        var d = LocalDate.now().plusDays(1)
        while (dates.size < count) {
            if (d.dayOfWeek.value in setOf(2, 4)) dates.add(d)
            d = d.plusDays(1)
        }
        return dates
    }

    private fun adminBooking(
        date: LocalDate,
        ctaReference: Long,
        windowId: String = "1",
        reason: String? = "Missed collection, referee called",
    ): String =
        """mutation { submitDeliveryBookingAdmin(input: { date: \"$date\", windowId: \"$windowId\", """ +
            """firstName: \"Ad\", surname: \"Min\", email: \"booker@example.org\", phone: \"07123456789\", """ +
            """address: \"1 Test Street\", ctaReference: $ctaReference""" +
            (reason?.let { """, exceptionReason: \"$it\"""" } ?: "") +
            """ }) { id date adminException bookedBy exceptionReason ctaReference } }"""

    private fun publicBooking(
        date: LocalDate,
        ctaReference: Long,
        windowId: String,
    ): String =
        """mutation { submitDeliveryBookingPublic(input: { date: \"$date\", windowId: \"$windowId\", """ +
            """firstName: \"Pub\", surname: \"Lic\", email: \"p@example.org\", phone: \"07123456789\", """ +
            """address: \"2 Test Street\", ctaReference: $ctaReference }) { id } }"""

    @Test
    fun `an admin booking is recorded as an exception with who made it and why`() {
        seedRequest(960001L, "PROCESSING_EQUALITIES_DATA_COMPLETE")
        admin(adminBooking(offeredDates(3)[2], 960001L))
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.submitDeliveryBookingAdmin.adminException").value(true))
            .andExpect(jsonPath("$.data.submitDeliveryBookingAdmin.bookedBy").value("admin@example.org"))
            .andExpect(jsonPath("$.data.submitDeliveryBookingAdmin.exceptionReason").value("Missed collection, referee called"))

        val saved = bookings.findAll().single { it.ctaReference == 960001L }
        assertTrue(saved.adminException)
        assertEquals("admin@example.org", saved.bookedBy)
    }

    @Test
    fun `an admin booking does not take a slot`() =
        withOwnWindow(9073L, capacity = 2) { windowId ->
            // An admin booking first, then the full public capacity of 2.
            val date = offeredDates(1)[0]
            seedRequest(960010L, "PROCESSING_EQUALITIES_DATA_COMPLETE")
            admin(adminBooking(date, 960010L, windowId = windowId)).andExpect(jsonPath("$.errors").doesNotExist())

            anonymous("""query { deliveryAvailabilityPublic { date windows { window { id } spotsRemaining } } }""")
                .andExpect(
                    jsonPath(
                        "$.data.deliveryAvailabilityPublic[?(@.date == '$date')].windows[?(@.window.id == '$windowId')].spotsRemaining",
                    ).value(2),
                )

            (1..2).forEach { i ->
                val ref = 960010L + i
                seedRequest(ref, "PROCESSING_EQUALITIES_DATA_COMPLETE")
                anonymous(publicBooking(date, ref, windowId)).andExpect(jsonPath("$.errors").doesNotExist())
            }
            seedRequest(960019L, "PROCESSING_EQUALITIES_DATA_COMPLETE")
            anonymous(publicBooking(date, 960019L, windowId))
                .andExpect(jsonPath("$.errors[0].extensions.code").value("WINDOW_FULL"))

            // ...and an admin can still add one to the full window.
            seedRequest(960020L, "PROCESSING_EQUALITIES_DATA_COMPLETE")
            admin(adminBooking(date, 960020L, windowId = windowId)).andExpect(jsonPath("$.errors").doesNotExist())
        }

    @Test
    fun `an admin can book any day, including one the public page never offers`() {
        var monday = LocalDate.now().plusYears(1)
        while (monday.dayOfWeek != DayOfWeek.MONDAY) monday = monday.plusDays(1)
        seedRequest(960030L, "PROCESSING_EQUALITIES_DATA_COMPLETE")

        admin(adminBooking(monday, 960030L))
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.submitDeliveryBookingAdmin.date").value(monday.toString()))
    }

    @Test
    fun `an admin booking moves the request on, whatever status it was in, like a normal booking`() {
        // The motivating case: the first delivery failed, so the request is no longer eligible
        // for a public booking.
        seedRequest(960040L, "PROCESSING_COLLECTION_DELIVERY_ARRANGED")

        admin(adminBooking(offeredDates(3)[2], 960040L, windowId = "2"))
            .andExpect(jsonPath("$.errors").doesNotExist())

        val request = deviceRequests.findById(960040L).orElseThrow()
        assertEquals(DeviceRequestStatus.PROCESSING_COLLECTION_DELIVERY_ARRANGED, request.status)
        assertTrue(request.collectionDate != null, "collection date must be set from the booking")
    }

    @Test
    fun `the reason is optional`() {
        seedRequest(960050L, "PROCESSING_EQUALITIES_DATA_COMPLETE")
        admin(adminBooking(offeredDates(3)[2], 960050L, windowId = "2", reason = null))
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.submitDeliveryBookingAdmin.adminException").value(true))
    }

    @Test
    fun `rejects a date in the past`() {
        seedRequest(960060L, "PROCESSING_EQUALITIES_DATA_COMPLETE")
        admin(adminBooking(LocalDate.now().minusDays(1), 960060L))
            .andExpect(jsonPath("$.errors", hasSize<Any>(1)))
            .andExpect(jsonPath("$.errors[0].message").value(containsString("past")))
        assertFalse(bookings.findAll().any { it.ctaReference == 960060L })
    }

    @Test
    fun `rejects a reference that matches no device request`() {
        admin(adminBooking(offeredDates(3)[2], 969999L))
            .andExpect(jsonPath("$.errors", hasSize<Any>(1)))
            .andExpect(jsonPath("$.errors[0].message").value(containsString("969999")))
    }

    @Test
    fun `rejects an unknown window`() {
        seedRequest(960070L, "PROCESSING_EQUALITIES_DATA_COMPLETE")
        admin(adminBooking(offeredDates(3)[2], 960070L, windowId = "999"))
            .andExpect(jsonPath("$.errors", hasSize<Any>(1)))
            .andExpect(jsonPath("$.errors[0].message").value(containsString("window")))
    }

    @Test
    fun `the admin bookings list flags exceptions and public bookings are not exceptions`() =
        withOwnWindow(9074L, capacity = 1) { windowId ->
            val date = offeredDates(1)[0]
            seedRequest(960080L, "PROCESSING_EQUALITIES_DATA_COMPLETE")
            seedRequest(960081L, "PROCESSING_EQUALITIES_DATA_COMPLETE")
            admin(adminBooking(date, 960080L, windowId = windowId)).andExpect(jsonPath("$.errors").doesNotExist())
            anonymous(publicBooking(date, 960081L, windowId)).andExpect(jsonPath("$.errors").doesNotExist())

            admin("""query { deliveryBookingsAdmin(from: \"$date\", to: \"$date\") { ctaReference adminException bookedBy } }""")
                .andExpect(jsonPath("$.data.deliveryBookingsAdmin[?(@.ctaReference == 960080)].adminException").value(true))
                .andExpect(jsonPath("$.data.deliveryBookingsAdmin[?(@.ctaReference == 960080)].bookedBy").value("admin@example.org"))
                .andExpect(jsonPath("$.data.deliveryBookingsAdmin[?(@.ctaReference == 960081)].adminException").value(false))
        }
}
