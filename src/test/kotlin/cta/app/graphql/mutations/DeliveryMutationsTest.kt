package cta.app.graphql.mutations

import cta.app.CollectionMethod
import cta.app.DeliveryBooking
import cta.app.DeliveryBookingRepository
import cta.app.DeliveryWindowRepository
import cta.app.DeviceRequestRepository
import cta.app.DeviceRequestStatus
import cta.app.FeatureFlag
import cta.app.FeatureFlagRepository
import cta.app.services.BoroughAvailabilityRules
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

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

    @Autowired
    lateinit var deviceRequestRepository: DeviceRequestRepository

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var featureFlags: FeatureFlagRepository

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
        ctaReference: Long = 4298,
    ): String =
        """mutation { submitDeliveryBookingPublic(input: { date: \"$date\", windowId: \"$windowId\", """ +
            """firstName: \"Test\", surname: \"Booker\", email: \"$email\", phone: \"07123456789\", """ +
            """address: \"$address\", ctaReference: $ctaReference }) { id date } }"""

    /**
     * device_requests has few NOT NULL columns beyond id (is_prepped, is_sales); the entity
     * constructor otherwise requires a ReferringOrganisationContact relation we don't want to
     * build here, so seed with a raw insert instead (mirrors DeliveryAdminQueriesTest).
     */
    private fun seedDeviceRequest(
        id: Long,
        status: String,
        borough: String? = null,
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
        seedDeviceRequest(4298L, "PROCESSING_EQUALITIES_DATA_COMPLETE")

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
        // Window 1 (capacity 4) on the second offered day, so this test owns the slot. Distinct,
        // eligible refs: each must clear the PROCESSING_EQUALITIES_DATA_COMPLETE gate on its own.
        val date = offeredDates(2)[1].toString()
        (1..4).forEach { i ->
            val ref = 950000L + i
            seedDeviceRequest(ref, "PROCESSING_EQUALITIES_DATA_COMPLETE")
            graphQl(bookingMutation(date, ctaReference = ref))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.errors").doesNotExist())
        }

        graphQl(bookingMutation(date))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errors[0].message").value("That delivery window is fully booked"))
    }

    /**
     * Changed 2026-08-26 (sheet row 24): the old duplicate-booking rule was removed. A second
     * booking for the same reference is now blocked only because the first booking already moved
     * the linked request out of PROCESSING_EQUALITIES_DATA_COMPLETE (see
     * DeliveryService.markCollectionDeliveryArranged), not because of any dedup check.
     */
    @Test
    fun `a second booking for the same reference is ineligible once the first has moved the request on`() {
        val requestId = 950010L
        seedDeviceRequest(requestId, "PROCESSING_EQUALITIES_DATA_COMPLETE")
        // Window 1 on the first offered day, window 2 on the second offered day: neither slot
        // is touched by the other tests in this class, so capacity here is untouched.
        val firstDay = offeredDates(1)[0].toString()
        val secondDay = offeredDates(2)[1].toString()

        graphQl(bookingMutation(firstDay, windowId = "1", ctaReference = requestId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errors").doesNotExist())

        graphQl(bookingMutation(secondDay, windowId = "2", ctaReference = requestId))
            .andExpect(status().isOk)
            .andExpect(
                jsonPath("$.errors[0].message")
                    .value(containsString("You are not able to book a delivery for request ID '$requestId'")),
            )
    }

    /**
     * Changed 2026-08-26 (sheet row 24): the old rule blocked a new booking whenever *any* row
     * existed for the reference, past or future. That rule is gone — only the linked request's
     * status matters now, so a stray past booking row no longer blocks anything by itself.
     */
    @Test
    fun `a past booking row for the same reference no longer blocks a new one by itself`() {
        val requestId = 950020L
        seedDeviceRequest(requestId, "PROCESSING_EQUALITIES_DATA_COMPLETE")
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
                ctaReference = requestId,
            ),
        )

        // Day 4 (untouched by any other test in this class) so this test's capacity accounting
        // can't collide with anything else's.
        graphQl(bookingMutation(offeredDates(4)[3].toString(), windowId = "1", ctaReference = requestId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.submitDeliveryBookingPublic.id").isNotEmpty)
    }

    @Test
    fun `marks a matched open device request as collection-delivery arranged`() {
        val requestId = 904301L
        seedDeviceRequest(requestId, "PROCESSING_EQUALITIES_DATA_COMPLETE")

        graphQl(bookingMutation(offeredDates(1)[0].toString(), windowId = "1", ctaReference = requestId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.submitDeliveryBookingPublic.id").isNotEmpty)

        val updated = deviceRequestRepository.findById(requestId).orElseThrow()
        assertEquals(DeviceRequestStatus.PROCESSING_COLLECTION_DELIVERY_ARRANGED, updated.status)
    }

    /**
     * ctaReference is a DeviceRequest id, so the schema types it as Long and the GraphQL layer
     * rejects anything that isn't one. Before this was enforced, a mistyped reference produced a
     * booking that silently linked to nothing (issue #133).
     */
    @Test
    fun `rejects a non-numeric CTA reference`() {
        val date = offeredDates(1)[0].toString()
        val mutation =
            """mutation { submitDeliveryBookingPublic(input: { date: \"$date\", windowId: \"2\", """ +
                """firstName: \"Test\", surname: \"Booker\", email: \"test@example.org\", """ +
                """phone: \"07123456789\", address: \"1 Test Street, London SW9 8PR\", """ +
                """ctaReference: \"CTA-123\" }) { id date } }"""

        graphQl(mutation)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errors").isNotEmpty)
            .andExpect(jsonPath("$.data.submitDeliveryBookingPublic").doesNotExist())
    }

    /**
     * A booking records what was arranged, not just that something was (issue #155): the request
     * carries the delivery method, the window start as an instant, and who the slot was booked by.
     */
    @Test
    fun `records the delivery method, date and contact on the matched request`() {
        val requestId = 904303L
        seedDeviceRequest(requestId, "PROCESSING_EQUALITIES_DATA_COMPLETE")
        val date = offeredDates(1)[0]

        // Window 2 is the seeded "Afternoon window", 2:00pm.
        graphQl(bookingMutation(date.toString(), windowId = "2", ctaReference = requestId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errors").doesNotExist())

        val updated = deviceRequestRepository.findById(requestId).orElseThrow()
        assertEquals(DeviceRequestStatus.PROCESSING_COLLECTION_DELIVERY_ARRANGED, updated.status)
        assertEquals(CollectionMethod.DELIVERY, updated.collectionMethod)
        assertEquals(
            ZonedDateTime.of(date, LocalTime.of(14, 0), ZoneId.of("Europe/London")).toInstant(),
            updated.collectionDate,
        )
        assertEquals("Test Booker", updated.collectionContactName)
    }

    /**
     * Changed 2026-08-26 (sheet row 24): a closed request is no longer eligible to book at all —
     * only PROCESSING_EQUALITIES_DATA_COMPLETE is. This used to assert the booking succeeded but
     * left the request untouched; now it must be rejected outright, before any booking is saved.
     */
    @Test
    fun `rejects a booking for a matched but closed device request`() {
        val requestId = 904302L
        seedDeviceRequest(requestId, "REQUEST_COMPLETED")

        graphQl(bookingMutation(offeredDates(2)[1].toString(), windowId = "2", ctaReference = requestId))
            .andExpect(status().isOk)
            .andExpect(
                jsonPath("$.errors[0].message")
                    .value(containsString("You are not able to book a delivery for request ID '$requestId'")),
            )

        val updated = deviceRequestRepository.findById(requestId).orElseThrow()
        assertEquals(DeviceRequestStatus.REQUEST_COMPLETED, updated.status)
    }

    /**
     * Borough gate (sheet row 18), gated behind borough-availability-rules. Flyway seeds two
     * live groups covering "Lambeth", "Southwark" and "Tower Hamlets" (V26.08.14.2100) — reused
     * here rather than inventing test-only groups, so these tests exercise the real matching
     * RefereeRequestLimitService.groupFor performs. All requests are seeded eligible
     * (PROCESSING_EQUALITIES_DATA_COMPLETE) so the borough gate is what's actually under test.
     */
    @Test
    fun `with the flag on, a covered borough is allowed`() {
        featureFlags.save(FeatureFlag(key = BoroughAvailabilityRules.FLAG_KEY, enabled = true))
        try {
            val requestId = 904320L
            seedDeviceRequest(requestId, "PROCESSING_EQUALITIES_DATA_COMPLETE", borough = "Lambeth")

            // Day 3, untouched by any other test in this class, so capacity accounting can't
            // collide with anything else's.
            graphQl(bookingMutation(offeredDates(3)[2].toString(), windowId = "1", ctaReference = requestId))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.submitDeliveryBookingPublic.id").isNotEmpty)
        } finally {
            featureFlags.save(FeatureFlag(key = BoroughAvailabilityRules.FLAG_KEY, enabled = false))
        }
    }

    @Test
    fun `with the flag on, a borough matching no group is refused`() {
        featureFlags.save(FeatureFlag(key = BoroughAvailabilityRules.FLAG_KEY, enabled = true))
        try {
            val requestId = 904321L
            seedDeviceRequest(requestId, "PROCESSING_EQUALITIES_DATA_COMPLETE", borough = "Westminster")

            graphQl(bookingMutation(offeredDates(1)[0].toString(), windowId = "1", ctaReference = requestId))
                .andExpect(status().isOk)
                .andExpect(
                    jsonPath("$.errors[0].message")
                        .value(containsString("020 3488 7742")),
                )
        } finally {
            featureFlags.save(FeatureFlag(key = BoroughAvailabilityRules.FLAG_KEY, enabled = false))
        }
    }

    @Test
    fun `with the flag on, a blank borough fails open`() {
        featureFlags.save(FeatureFlag(key = BoroughAvailabilityRules.FLAG_KEY, enabled = true))
        try {
            val requestId = 904322L
            seedDeviceRequest(requestId, "PROCESSING_EQUALITIES_DATA_COMPLETE", borough = null)

            graphQl(bookingMutation(offeredDates(3)[2].toString(), windowId = "2", ctaReference = requestId))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.submitDeliveryBookingPublic.id").isNotEmpty)
        } finally {
            featureFlags.save(FeatureFlag(key = BoroughAvailabilityRules.FLAG_KEY, enabled = false))
        }
    }

    /**
     * Changed 2026-08-26 (sheet row 24): the borough gate still fails open for an unmatched
     * reference, but the new eligibility gate does NOT — it runs unconditionally and rejects an
     * unresolvable reference outright, so the overall booking is now refused.
     */
    @Test
    fun `with the flag on, an unmatched CTA reference fails the (non-fail-open) eligibility gate`() {
        featureFlags.save(FeatureFlag(key = BoroughAvailabilityRules.FLAG_KEY, enabled = true))
        try {
            // 904399 deliberately matches no seeded device request.
            graphQl(bookingMutation(offeredDates(3)[2].toString(), windowId = "1", ctaReference = 904399L))
                .andExpect(status().isOk)
                .andExpect(
                    jsonPath("$.errors[0].message")
                        .value(containsString("You are not able to book a delivery for request ID '904399'")),
                )
        } finally {
            featureFlags.save(FeatureFlag(key = BoroughAvailabilityRules.FLAG_KEY, enabled = false))
        }
    }

    @Test
    fun `with the flag off, an uncovered borough is allowed, matching todays behaviour`() {
        featureFlags.save(FeatureFlag(key = BoroughAvailabilityRules.FLAG_KEY, enabled = false))
        val requestId = 904323L
        seedDeviceRequest(requestId, "PROCESSING_EQUALITIES_DATA_COMPLETE", borough = "Westminster")

        graphQl(bookingMutation(offeredDates(3)[2].toString(), windowId = "2", ctaReference = requestId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.submitDeliveryBookingPublic.id").isNotEmpty)
    }
}
