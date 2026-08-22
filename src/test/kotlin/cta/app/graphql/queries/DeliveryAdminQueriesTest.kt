package cta.app.graphql.queries

import com.fasterxml.jackson.databind.ObjectMapper
import cta.app.DeliveryBooking
import cta.app.DeliveryBookingOverride
import cta.app.DeliveryBookingOverrideRepository
import cta.app.DeliveryBookingRepository
import cta.app.DeliveryWindowRepository
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.time.LocalDate

/**
 * Admin read-time matching of a booking's ctaReference to a DeviceRequest id
 * (DeliveryAdminQueries.toAdminGql / CLOSED_REQUEST_STATUSES). This never touches the public
 * booking mutation — unmatched/non-numeric refs simply resolve to null fields, nothing is
 * ever rejected on the anonymous submit path.
 *
 * Both tests share the class-scoped embedded database (no per-method rollback, following this
 * repo's existing DeliveryMutationsTest/DeliveryBookingProtectionTest convention), so the seed
 * data below uses explicit high device_request ids (904298/904299) that nothing else in the
 * suite touches, and everything is seeded within a single method to avoid re-seeding collisions.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class DeliveryAdminQueriesTest {
    @MockBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var bookingRepository: DeliveryBookingRepository

    @Autowired
    lateinit var overrideRepository: DeliveryBookingOverrideRepository

    @Autowired
    lateinit var windowRepository: DeliveryWindowRepository

    private val openRequestId = 904298L
    private val completedRequestId = 904299L
    private val failedCollectionDeliveryRequestId = 904300L

    private fun anonymousGraphQl(query: String): ResultActions =
        mockMvc.perform(
            post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query":"$query"}"""),
        )

    private fun authorizedGraphQl(query: String): ResultActions =
        mockMvc.perform(
            post("/graphql")
                .with(jwt().authorities(SimpleGrantedAuthority("read:organisations")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query":"$query"}"""),
        )

    /**
     * device_requests has few NOT NULL columns beyond id (is_prepped, is_sales); the entity
     * constructor otherwise requires a ReferringOrganisationContact relation we don't want to
     * build here, so seed with a raw insert instead.
     */
    private fun seedDeviceRequest(
        id: Long,
        status: String,
    ) {
        jdbcTemplate.update(
            """
            insert into device_requests (id, is_prepped, is_sales, status, created_at, updated_at)
            values (?, false, false, ?, now(), now())
            """.trimIndent(),
            id,
            status,
        )
    }

    private fun seedBooking(ctaReference: Long): DeliveryBooking {
        val window = windowRepository.findAllByOrderBySortOrderAsc().first()
        return bookingRepository.save(
            DeliveryBooking(
                deliveryDate = LocalDate.now().plusDays(7),
                window = window,
                firstName = "Test",
                surname = "Booker",
                email = "test@example.org",
                phone = "07123456789",
                address = "1 Test Street, London SW9 8PR",
                ctaReference = ctaReference,
            ),
        )
    }

    private val adminBookingsQuery =
        """query { deliveryBookingsAdmin { ctaReference matchedRequestId matchedRequestStatus matchedRequestOpen } }"""

    @Test
    fun `resolves matched-request fields for open, closed and unmatched references`() {
        seedDeviceRequest(openRequestId, "NEW")
        seedDeviceRequest(completedRequestId, "REQUEST_COMPLETED")
        seedDeviceRequest(failedCollectionDeliveryRequestId, "REQUEST_COLLECTION_DELIVERY_FAILED")

        seedBooking(openRequestId)
        seedBooking(completedRequestId)
        seedBooking(failedCollectionDeliveryRequestId)
        seedBooking(999999999L)

        val response =
            authorizedGraphQl(adminBookingsQuery)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andReturn()
                .response
                .contentAsString

        val rowsByRef = extractBookingRowsByCtaReference(response)

        // Match on an open request: id + status + open true.
        val exactMatch = rowsByRef.getValue(openRequestId)
        assertEquals(openRequestId.toString(), exactMatch["matchedRequestId"], "exact match id: $exactMatch")
        assertEquals("NEW", exactMatch["matchedRequestStatus"], "exact match status: $exactMatch")
        assertEquals(true, exactMatch["matchedRequestOpen"], "exact match open: $exactMatch")

        // A closed request matches, but is flagged as not open.
        val closedMatch = rowsByRef.getValue(completedRequestId)
        assertEquals(completedRequestId.toString(), closedMatch["matchedRequestId"], "closed match id: $closedMatch")
        assertEquals("REQUEST_COMPLETED", closedMatch["matchedRequestStatus"], "closed match status: $closedMatch")
        assertEquals(false, closedMatch["matchedRequestOpen"], "closed match open: $closedMatch")

        // A failed collection/delivery matches, and is flagged as open: settled by the team on
        // 2026-07-30 (#120) — the referrer has two weeks to rebook, so the request stays open
        // until then rather than closing automatically on a failed attempt.
        val failedMatch = rowsByRef.getValue(failedCollectionDeliveryRequestId)
        assertEquals(
            failedCollectionDeliveryRequestId.toString(),
            failedMatch["matchedRequestId"],
            "failed collection/delivery match id: $failedMatch",
        )
        assertEquals(
            "REQUEST_COLLECTION_DELIVERY_FAILED",
            failedMatch["matchedRequestStatus"],
            "failed collection/delivery match status: $failedMatch",
        )
        assertEquals(true, failedMatch["matchedRequestOpen"], "failed collection/delivery match open: $failedMatch")

        // A numeric reference with no matching request resolves to all-null.
        val unmatchedNumeric = rowsByRef.getValue(999999999L)
        assertEquals(null, unmatchedNumeric["matchedRequestId"], "unmatched numeric id: $unmatchedNumeric")
        assertEquals(null, unmatchedNumeric["matchedRequestStatus"], "unmatched numeric status: $unmatchedNumeric")
        assertEquals(null, unmatchedNumeric["matchedRequestOpen"], "unmatched numeric open: $unmatchedNumeric")
    }

    @Test
    fun `deliveryBookingsAdmin rejects anonymous callers`() {
        anonymousGraphQl(adminBookingsQuery)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errors[0].message").value("Access Denied"))
            .andExpect(jsonPath("$.data").value(nullValue()))
    }

    /**
     * additionalBookingAllowed is resolved for the whole page in one query
     * (findAllByCtaReferenceInAndConsumedAtIsNull), not per row — this exercises all three
     * states an override can leave a reference in: unconsumed, none, and already consumed.
     */
    @Test
    fun `additionalBookingAllowed reflects an unconsumed override`() {
        val withOverrideRef = 904330L
        val withoutOverrideRef = 904331L
        val consumedOverrideRef = 904332L

        seedBooking(withOverrideRef)
        seedBooking(withoutOverrideRef)
        seedBooking(consumedOverrideRef)

        overrideRepository.save(DeliveryBookingOverride(ctaReference = withOverrideRef))
        val consumed = overrideRepository.save(DeliveryBookingOverride(ctaReference = consumedOverrideRef))
        consumed.consumedAt = Instant.now()
        overrideRepository.save(consumed)

        val response =
            authorizedGraphQl("query { deliveryBookingsAdmin { ctaReference additionalBookingAllowed } }")
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andReturn()
                .response
                .contentAsString

        val rows = ObjectMapper().readTree(response).get("data").get("deliveryBookingsAdmin")
        val allowedByRef = mutableMapOf<Long, Boolean>()
        rows.forEach { row -> allowedByRef[row.get("ctaReference").asLong()] = row.get("additionalBookingAllowed").asBoolean() }

        assertEquals(true, allowedByRef[withOverrideRef])
        assertEquals(false, allowedByRef[withoutOverrideRef])
        assertEquals(false, allowedByRef[consumedOverrideRef])
    }

    /**
     * Parses the raw GraphQL response body and indexes deliveryBookingsAdmin rows by
     * ctaReference, keeping matchedRequest* values as Any? (String/Boolean/null) so callers can
     * assert precisely without fighting jsonPath's handling of duplicate keys.
     */
    private fun extractBookingRowsByCtaReference(body: String): Map<Long, Map<String, Any?>> {
        val mapper = ObjectMapper()
        val root = mapper.readTree(body)
        val rows = root.get("data").get("deliveryBookingsAdmin")
        val result = LinkedHashMap<Long, Map<String, Any?>>()
        rows.forEach { row ->
            val ref = row.get("ctaReference").asLong()
            result[ref] =
                mapOf(
                    "matchedRequestId" to row.get("matchedRequestId").let { if (it.isNull) null else it.asText() },
                    "matchedRequestStatus" to row.get("matchedRequestStatus").let { if (it.isNull) null else it.asText() },
                    "matchedRequestOpen" to row.get("matchedRequestOpen").let { if (it.isNull) null else it.asBoolean() },
                )
        }
        return result
    }
}
