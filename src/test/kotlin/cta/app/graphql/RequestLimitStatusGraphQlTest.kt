package cta.app.graphql

import cta.app.BoroughGroupRepository
import cta.app.DeviceRequest
import cta.app.DeviceRequestItems
import cta.app.DeviceRequestNeeds
import cta.app.DeviceRequestRepository
import cta.app.FeatureFlag
import cta.app.FeatureFlagRepository
import cta.app.ReferrerLimitException
import cta.app.ReferrerLimitExceptionRepository
import cta.app.ReferringOrganisation
import cta.app.ReferringOrganisationContact
import cta.app.ReferringOrganisationContactRepository
import cta.app.ReferringOrganisationRepository
import cta.app.services.BoroughAvailabilityRules
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * `ReferringOrganisationContactPublic.requestLimitStatus` (#336), called ANONYMOUSLY through
 * /graphql: under the cap, at the cap, an organisation exception raising the cap, and the
 * borough-availability flag off (global cap of 3). Only `atLimit` and `limit` exist in the schema,
 * so the open count cannot leak.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class RequestLimitStatusGraphQlTest {
    @MockitoBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired lateinit var mockMvc: MockMvc

    @Autowired lateinit var objectMapper: ObjectMapper

    @Autowired lateinit var organisations: ReferringOrganisationRepository

    @Autowired lateinit var contacts: ReferringOrganisationContactRepository

    @Autowired lateinit var deviceRequests: DeviceRequestRepository

    @Autowired lateinit var featureFlags: FeatureFlagRepository

    @Autowired lateinit var boroughGroups: BoroughGroupRepository

    @Autowired lateinit var limitExceptions: ReferrerLimitExceptionRepository

    @BeforeEach
    fun rulesOn() = setRules(true)

    @AfterEach
    fun rulesBackToSeed() = setRules(false)

    private fun setRules(enabled: Boolean) {
        featureFlags.save(FeatureFlag(key = BoroughAvailabilityRules.FLAG_KEY, enabled = enabled))
    }

    @Test
    fun `under the limit is not at limit`() {
        val referee = referee(newOrg())
        open(referee, 2, "Lambeth")

        val status = status(referee, "Lambeth")

        assertThat(status["atLimit"].asBoolean()).isFalse()
        assertThat(status["limit"].asInt()).isEqualTo(3)
    }

    @Test
    fun `at the limit reports atLimit true with the limit`() {
        val referee = referee(newOrg())
        open(referee, 3, "Lambeth")

        val status = status(referee, "Lambeth")

        assertThat(status["atLimit"].asBoolean()).isTrue()
        assertThat(status["limit"].asInt()).isEqualTo(3)
    }

    @Test
    fun `the count is scoped to the borough group like the submit check`() {
        val referee = referee(newOrg())
        open(referee, 1, "Tower Hamlets")

        assertThat(status(referee, "Tower Hamlets")["atLimit"].asBoolean()).isTrue()
        assertThat(status(referee, "Tower Hamlets")["limit"].asInt()).isEqualTo(1)
        assertThat(status(referee, "Lambeth")["atLimit"].asBoolean()).isFalse()
    }

    @Test
    fun `an organisation exception raises the limit`() {
        val group = boroughGroups.findAll().first { it.name == "Tower Hamlets" }
        val org = newOrg()
        limitExceptions.save(ReferrerLimitException(referringOrganisation = org, group = group, maxPerReferee = 5))
        val referee = referee(org)
        open(referee, 1, "Tower Hamlets")

        val status = status(referee, "Tower Hamlets")

        assertThat(status["atLimit"].asBoolean()).isFalse()
        assertThat(status["limit"].asInt()).isEqualTo(5)
    }

    @Test
    fun `with borough rules off the global cap of 3 applies`() {
        val referee = referee(newOrg())
        open(referee, 1, "Tower Hamlets")
        setRules(false)

        val status = status(referee, "Tower Hamlets")

        assertThat(status["atLimit"].asBoolean()).isFalse()
        assertThat(status["limit"].asInt()).isEqualTo(3)
    }

    @Test
    fun `an omitted borough falls back to the global cap`() {
        val referee = referee(newOrg())
        open(referee, 3, "Lambeth")

        val status = status(referee, null)

        assertThat(status["atLimit"].asBoolean()).isTrue()
        assertThat(status["limit"].asInt()).isEqualTo(3)
    }

    @Test
    fun `the open count is not part of the schema`() {
        val referee = referee(newOrg())
        val body =
            gql(
                "{ referringOrganisationContactsPublic(where: { email: { _ilike: \"${referee.email}\" } }) " +
                    "{ requestLimitStatus { openCount } } }",
            )
        assertThat(body.has("errors")).isTrue()
    }

    private fun status(
        referee: ReferringOrganisationContact,
        borough: String?,
    ): JsonNode {
        val body =
            gql(
                "query(\$email: String!, \$borough: String) { referringOrganisationContactsPublic(where: { email: { _ilike: \$email } }) " +
                    "{ requestLimitStatus(borough: \$borough) { atLimit limit } } }",
                mapOf("email" to referee.email, "borough" to borough),
            )
        assertThat(body.has("errors")).`as`(body.toString()).isFalse()
        return body["data"]["referringOrganisationContactsPublic"][0]["requestLimitStatus"]
    }

    private fun gql(
        query: String,
        variables: Map<String, Any?> = emptyMap(),
    ): JsonNode =
        objectMapper.readTree(
            mockMvc
                .perform(
                    post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mapOf("query" to query, "variables" to variables))),
                ).andReturn()
                .response.contentAsString,
        )

    private fun newOrg() = organisations.save(ReferringOrganisation(name = "Limit status org ${System.nanoTime()}"))

    private fun referee(org: ReferringOrganisation) =
        contacts.save(
            ReferringOrganisationContact(
                fullName = "Referee ${System.nanoTime()}",
                email = "limit-status-${System.nanoTime()}@example.com",
                phoneNumber = "07000000000",
                address = "1 Test Street",
                referringOrganisation = org,
            ),
        )

    private fun open(
        referee: ReferringOrganisationContact,
        count: Int,
        borough: String,
    ) = repeat(count) { i ->
        deviceRequests.save(
            DeviceRequest(
                deviceRequestItems = DeviceRequestItems(laptops = 1),
                referringOrganisationContact = referee,
                clientRef = "LIMIT-STATUS-${System.nanoTime()}-$i",
                borough = borough,
                details = "Household needs a laptop",
                deviceRequestNeeds = DeviceRequestNeeds(false, false, false),
            ),
        )
    }
}
