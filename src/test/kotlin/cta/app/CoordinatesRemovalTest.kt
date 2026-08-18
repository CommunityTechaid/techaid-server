package cta.app

import cta.app.graphql.mutations.CreateDonorInput
import cta.app.graphql.mutations.DonorMutations
import cta.app.graphql.mutations.KitMutations
import cta.app.graphql.mutations.UpdateKitInput
import cta.app.services.LocationService
import graphql.schema.GraphQLObjectType
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.graphql.execution.GraphQlSource
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.context.support.WithMockUser

/**
 * `coordinates` is gone from donors and kits (#161).
 *
 * It held `Coordinates(lat, lng, address, input)`, geocoded by `LocationService` from
 * `donors.post_code` and `kits.location` — so `input` was the raw donor postcode and `address`
 * Google's formatted version of it. For an individual donor that is a home address. Nothing read
 * it: production held a real payload on 16 of 425 donors and 0 of 19,609 kits, and nothing created
 * since August 2022 ever carried a value.
 *
 * Personal data with no consumer is worth removing rather than retaining, which is why the full
 * drop was chosen over leaving the GDPR scrub to handle it.
 *
 * WHAT STAYS, deliberately: the `Coordinates` GraphQL type and the ad-hoc
 * `location(address: String)` query, plus `LocationService` itself. Those are a live lookup
 * independent of the persisted columns — the last test pins that, so a later "finish the job"
 * pass does not take a working feature with it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class CoordinatesRemovalTest {
    @MockBean
    lateinit var jwtDecoder: JwtDecoder

    @MockBean
    lateinit var locationService: LocationService

    @Autowired
    lateinit var donorMutations: DonorMutations

    @Autowired
    lateinit var kitMutations: KitMutations

    @Autowired
    lateinit var kits: KitRepository

    @Autowired
    lateinit var graphQlSource: GraphQlSource

    @Test
    @WithMockUser(authorities = ["write:donors"])
    fun `creating a donor no longer geocodes their postcode`() {
        donorMutations.createDonor(
            CreateDonorInput(
                name = "Geocode Me Not",
                email = "nogeocode@example.com",
                phoneNumber = "07000000000",
                postCode = "SE1 1AA",
                referral = "test",
                isLeadContact = false,
            ),
        )

        verify(locationService, never()).findCoordinates(anyString())
    }

    @Test
    @WithMockUser(authorities = ["write:kits"])
    fun `updating a kit no longer geocodes its location`() {
        val kit = kits.save(Kit(model = "No geocode", age = 1))

        kitMutations.updateKit(
            UpdateKitInput(
                id = kit.id,
                type = KitType.OTHER,
                status = KitStatus.DONATION_NEW,
                model = "No geocode",
                location = "Brixton",
                age = 1,
            ),
        )

        verify(locationService, never()).findCoordinates(anyString())
    }

    @Test
    fun `the schema no longer exposes coordinates on Donor or Kit`() {
        val schema = graphQlSource.schema()

        listOf("Donor", "Kit").forEach { typeName ->
            val type = schema.getType(typeName) as GraphQLObjectType
            assertThat(type.fieldDefinitions.map { it.name })
                .`as`("$typeName must not expose the persisted coordinates field")
                .doesNotContain("coordinates")
        }
    }

    @Test
    fun `the live location lookup and its type survive`() {
        val schema = graphQlSource.schema()

        assertThat(schema.getType("Coordinates"))
            .`as`("the Coordinates type backs the ad-hoc lookup and is not part of the drop")
            .isNotNull()
        assertThat(schema.queryType.fieldDefinitions.map { it.name })
            .`as`("location(address:) is a live geocode, independent of the removed columns")
            .contains("location")
    }
}
