package cta.app.graphql.mutations

import cta.app.AdminConfigRepository
import cta.app.FeatureFlag
import cta.app.FeatureFlagRepository
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
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.GraphQlTester
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean

/**
 * createDeviceRequest enforces the public device offer server-side (#367).
 *
 * The offer is two layers: the service-wide `AdminConfig.canPublicRequest*` switches, and - only
 * while borough-availability-rules is on - the request's borough group matrix. Before this, only
 * the dashboard form hid switched-off types, so a stale tab or a direct GraphQL call could still
 * request them.
 *
 * Mutates the shared admin config and the borough flag, so this class forks its own context (and
 * embedded database) via a distinct application name, and restores state after each test.
 * Flyway seeds Lambeth/Southwark offering every type and Tower Hamlets offering laptops only.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
@AutoConfigureGraphQlTester
@TestPropertySource(properties = ["spring.application.name=techaid-server-public-device-offer-test"])
class PublicDeviceOfferEnforcementTest {
    @MockitoBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var graphQlTester: GraphQlTester

    @Autowired
    lateinit var referringOrganisations: ReferringOrganisationRepository

    @Autowired
    lateinit var referringOrganisationContacts: ReferringOrganisationContactRepository

    @Autowired
    lateinit var featureFlags: FeatureFlagRepository

    @Autowired
    lateinit var adminConfigs: AdminConfigRepository

    @Autowired
    lateinit var jdbc: JdbcTemplate

    // Nothing in Flyway seeds the singleton admin_config row, so create it for these tests.
    @BeforeEach
    fun resetState() {
        jdbc.update(
            "insert into admin_config (id, can_public_request_broadband_hub, can_public_request_desktop, " +
                "can_public_request_laptop, can_public_request_phone, can_public_request_tablet, can_public_requestsimcard) " +
                "values (1, true, true, true, true, true, true) on conflict (id) do nothing",
        )
        setSimOffered(true)
        setRules(false)
    }

    @AfterEach
    fun restoreState() {
        setSimOffered(true)
        setRules(false)
    }

    private fun setRules(on: Boolean) {
        featureFlags.save(FeatureFlag(key = BoroughAvailabilityRules.FLAG_KEY, enabled = on))
    }

    private fun setSimOffered(offered: Boolean) {
        val config = adminConfigs.getAdminConfig()
        config.canPublicRequestSIMCard = offered
        adminConfigs.save(config)
    }

    @Test
    fun `anonymous request for a type switched off service-wide is rejected`() {
        setSimOffered(false)

        create("Lambeth", "commsDevices: 1")
            .errors()
            .satisfy { errors ->
                assertThat(errors).hasSize(1)
                assertThat(errors.single().errorType.toString()).isEqualTo("BAD_REQUEST")
                assertThat(errors.single().message)
                    .isEqualTo("SIM cards are not currently available in Lambeth. Please refresh the form.")
                assertThat(errors.single().extensions["code"]).isEqualTo("DEVICE_TYPE_NOT_OFFERED")
            }
    }

    @Test
    fun `other types still go through when only SIM cards are off`() {
        setSimOffered(false)

        create("Lambeth", "laptops: 1")
            .errors()
            .verify()
            .path("createDeviceRequest.id")
            .hasValue()
    }

    @Test
    fun `with the flag on a type off for the borough group is rejected and the same type in another group is accepted`() {
        setRules(true)

        create("Tower Hamlets", "phones: 1")
            .errors()
            .satisfy { errors ->
                assertThat(errors).hasSize(1)
                assertThat(errors.single().message)
                    .isEqualTo("Phones are not currently available in Tower Hamlets. Please refresh the form.")
                assertThat(errors.single().extensions["code"]).isEqualTo("DEVICE_TYPE_NOT_OFFERED")
            }

        create("Lambeth", "phones: 1")
            .errors()
            .verify()
            .path("createDeviceRequest.id")
            .hasValue()
    }

    @Test
    fun `the borough matches case and whitespace insensitively`() {
        setRules(true)

        create("  tower hamlets ", "phones: 1").errors().satisfy { assertThat(it).hasSize(1) }
    }

    @Test
    fun `a type the borough offers is accepted while the flag is on`() {
        setRules(true)

        create("Tower Hamlets", "laptops: 1")
            .errors()
            .verify()
            .path("createDeviceRequest.id")
            .hasValue()
    }

    @Test
    fun `with the flag off only the service-wide layer applies`() {
        // Tower Hamlets' matrix is laptops only, but the matrix is inert while the flag is off.
        create("Tower Hamlets", "phones: 1")
            .errors()
            .verify()
            .path("createDeviceRequest.id")
            .hasValue()

        setSimOffered(false)
        create("Tower Hamlets", "commsDevices: 1").errors().satisfy { assertThat(it).hasSize(1) }
    }

    @Test
    fun `a borough with no group is not narrowed by the matrix`() {
        setRules(true)

        create("Camden", "phones: 1")
            .errors()
            .verify()
            .path("createDeviceRequest.id")
            .hasValue()
    }

    @Test
    fun `zero-quantity items are ignored`() {
        setSimOffered(false)
        setRules(true)

        create("Tower Hamlets", "laptops: 1 commsDevices: 0 phones: 0")
            .errors()
            .verify()
            .path("createDeviceRequest.id")
            .hasValue()
    }

    @Test
    @WithMockUser(authorities = ["write:requests"])
    fun `a staff caller is rejected in the same way`() {
        setSimOffered(false)

        create("Lambeth", "commsDevices: 1")
            .errors()
            .satisfy { errors ->
                assertThat(errors).hasSize(1)
                assertThat(errors.single().extensions["code"]).isEqualTo("DEVICE_TYPE_NOT_OFFERED")
            }
    }

    @Test
    fun `several unavailable types are all named in one message`() {
        setSimOffered(false)
        setRules(true)

        create("Tower Hamlets", "phones: 1 tablets: 2 commsDevices: 1")
            .errors()
            .satisfy { errors ->
                assertThat(errors.single().message)
                    .isEqualTo("Phones, tablets and SIM cards are not currently available in Tower Hamlets. Please refresh the form.")
            }
    }

    private fun create(
        borough: String,
        items: String,
    ): GraphQlTester.Response {
        val referee = contact()
        return graphQlTester
            .document(
                """
                mutation Create(${'$'}contactId: ID!) {
                  createDeviceRequest(data: {
                    referringOrganisationContact: ${'$'}contactId
                    deviceRequestItems: { $items }
                    clientRef: "OFFER-367"
                    borough: "$borough"
                    details: "Household needs a device"
                  }) { id }
                }
                """.trimIndent(),
            ).variable("contactId", referee.id.toString())
            .execute()
    }

    private fun contact(): ReferringOrganisationContact {
        val organisation = referringOrganisations.save(ReferringOrganisation(name = "Offer org ${System.nanoTime()}"))
        return referringOrganisationContacts.save(
            ReferringOrganisationContact(
                fullName = "Offer Referee",
                email = "offer-${System.nanoTime()}@example.com",
                phoneNumber = "07000000000",
                address = "1 Test Street",
                referringOrganisation = organisation,
            ),
        )
    }
}
