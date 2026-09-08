package cta.app.graphql.queries

import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.nullValue
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

/**
 * Public deliveryBookingEligibilityPublic: applies the same status gate as
 * submitDeliveryBookingPublic (DeliveryService.checkBookingEligibility), and never reveals
 * anything about the matched request beyond the boolean — the ineligible message is
 * deliberately identical whether the reference is unknown or just in the wrong status.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class DeliveryQueriesTest {
    @MockBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

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

    private fun eligibilityQuery(ctaReference: Long): String =
        """query { deliveryBookingEligibilityPublic(ctaReference: $ctaReference) { eligible message } }"""

    /** The message wording is asserted by substring so the CTA reference interpolation doesn't need matching exactly. */
    private val ineligibleMessageFragment =
        "at this time. Please check the number is correct, and try again if not. Otherwise please contact " +
            "distributions@communitytechaid.org.uk quoting your request ID for further information"

    @Test
    fun `eligible for a request at PROCESSING_EQUALITIES_DATA_COMPLETE`() {
        val requestId = 904350L
        seedDeviceRequest(requestId, "PROCESSING_EQUALITIES_DATA_COMPLETE")

        graphQl(eligibilityQuery(requestId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.deliveryBookingEligibilityPublic.eligible").value(true))
            .andExpect(jsonPath("$.data.deliveryBookingEligibilityPublic.message").value(nullValue()))
    }

    @Test
    fun `ineligible for a request in any other status`() {
        val requestId = 904351L
        seedDeviceRequest(requestId, "NEW")

        graphQl(eligibilityQuery(requestId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.deliveryBookingEligibilityPublic.eligible").value(false))
            .andExpect(jsonPath("$.data.deliveryBookingEligibilityPublic.message").value(containsString(ineligibleMessageFragment)))
    }

    @Test
    fun `ineligible and identically worded for an unknown reference`() {
        val unknownRef = 904352L

        graphQl(eligibilityQuery(unknownRef))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.deliveryBookingEligibilityPublic.eligible").value(false))
            .andExpect(jsonPath("$.data.deliveryBookingEligibilityPublic.message").value(containsString(ineligibleMessageFragment)))
    }
}
