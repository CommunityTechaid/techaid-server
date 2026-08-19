package cta.app.graphql.mutations

import cta.app.DeviceRequest
import cta.app.DeviceRequestItems
import cta.app.DeviceRequestNeeds
import cta.app.DeviceRequestRepository
import cta.app.DeviceRequestStatus
import cta.app.ReferringOrganisation
import cta.app.ReferringOrganisationContact
import cta.app.ReferringOrganisationContactRepository
import cta.app.ReferringOrganisationRepository
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.graphql.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.graphql.test.tester.GraphQlTester
import org.springframework.security.oauth2.jwt.JwtDecoder

/**
 * The shape of the response a referrer gets when they hit their open-request limit.
 *
 * This is a GraphQL-layer test rather than a controller-method one on purpose: the defect it
 * guards lives entirely in the schema. `createDeviceRequest` was declared `DeviceRequest!`, so
 * when the limit rejection fired, ControllerExceptionHandler produced its readable BAD_REQUEST
 * error AND graphql-java appended a second error about a non-null type returning null. Both
 * landed in the same payload. Calling the controller method directly cannot see that — the
 * method just throws — which is why the second error survived in production long enough to
 * become a documented "pre-existing bug" (measured: paired at identical timestamps in App
 * Insights, 2026-08-17).
 *
 * Runs with borough-availability-rules at its seeded default of OFF, so this exercises the
 * global cap of 3 — the path production actually takes today.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
@AutoConfigureGraphQlTester
class CreateDeviceRequestErrorShapeTest {
    @MockBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var graphQlTester: GraphQlTester

    @Autowired
    lateinit var referringOrganisations: ReferringOrganisationRepository

    @Autowired
    lateinit var referringOrganisationContacts: ReferringOrganisationContactRepository

    @Autowired
    lateinit var deviceRequests: DeviceRequestRepository

    @Test
    fun `a limit rejection is the only error in the payload`() {
        val referee = contactAtLimit()

        graphQlTester
            .document(CREATE_MUTATION)
            .variable("contactId", referee.id.toString())
            .execute()
            .errors()
            .satisfy { errors ->
                assertThat(errors.map { it.message })
                    .`as`("the referrer must get the business message and nothing else")
                    .hasSize(1)
                assertThat(errors.single().message).contains("requests open")
                assertThat(errors.single().errorType.toString()).isEqualTo("BAD_REQUEST")
            }
    }

    @Test
    fun `the rejection does not report a non-null schema violation`() {
        val referee = contactAtLimit()

        graphQlTester
            .document(CREATE_MUTATION)
            .variable("contactId", referee.id.toString())
            .execute()
            .errors()
            .satisfy { errors ->
                // The exact string graphql-java emits for a non-null field resolving to null. Its
                // presence means the mutation is declared non-null again.
                assertThat(errors.map { it.message })
                    .`as`("null-bubbling error must not accompany the rejection")
                    .noneMatch { it?.contains("non null type") == true }
            }
    }

    @Test
    fun `a request under the limit still succeeds and returns the created row`() {
        val referee = contact("Org under-limit", "Referee under-limit")

        graphQlTester
            .document(CREATE_MUTATION)
            .variable("contactId", referee.id.toString())
            .execute()
            .errors()
            .verify()
            .path("createDeviceRequest.id")
            .hasValue()
    }

    private fun contactAtLimit(): ReferringOrganisationContact {
        val referee = contact("Org at-limit ${System.nanoTime()}", "Referee at-limit")
        repeat(3) { i ->
            deviceRequests.save(
                DeviceRequest(
                    deviceRequestItems = DeviceRequestItems(laptops = 1),
                    referringOrganisationContact = referee,
                    status = DeviceRequestStatus.NEW,
                    isSales = false,
                    clientRef = "AT-LIMIT-$i-${referee.id}",
                    borough = "Lambeth",
                    details = "Household needs a laptop",
                    deviceRequestNeeds = DeviceRequestNeeds(false, false, false),
                ),
            )
        }
        return referringOrganisationContacts.findById(referee.id).orElseThrow()
    }

    private fun contact(
        organisationName: String,
        contactName: String,
    ): ReferringOrganisationContact {
        val organisation = referringOrganisations.save(ReferringOrganisation(name = organisationName))
        return referringOrganisationContacts.save(
            ReferringOrganisationContact(
                fullName = contactName,
                email = "${contactName.lowercase().replace(" ", "-")}-${System.nanoTime()}@example.com",
                phoneNumber = "07000000000",
                address = "1 Test Street",
                referringOrganisation = organisation,
            ),
        )
    }

    companion object {
        private val CREATE_MUTATION =
            """
            mutation Create(${'$'}contactId: ID!) {
              createDeviceRequest(data: {
                referringOrganisationContact: ${'$'}contactId
                deviceRequestItems: { laptops: 1 }
                clientRef: "ERR-SHAPE"
                borough: "Lambeth"
                details: "Household needs a laptop"
              }) { id }
            }
            """.trimIndent()
    }
}
