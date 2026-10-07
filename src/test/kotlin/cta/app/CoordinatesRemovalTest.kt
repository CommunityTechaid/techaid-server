package cta.app

import graphql.schema.GraphQLObjectType
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.execution.GraphQlSource
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.bean.override.mockito.MockitoBean

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
 * The ad-hoc `location(address: String)` query, its `Coordinates` type and `LocationService`
 * outlived the drop until 2026-10-07, when they were removed too: no dashboard caller, and the
 * Google key behind them was revoked after third-party abuse. With no geocoder left in the
 * codebase, donor and kit writes cannot geocode, so only the schema is pinned here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class CoordinatesRemovalTest {
    @MockitoBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var graphQlSource: GraphQlSource

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
    fun `the location geocoding query and its type are gone`() {
        val schema = graphQlSource.schema()

        assertThat(schema.getType("Coordinates"))
            .`as`("Coordinates only backed the removed location query")
            .isNull()
        assertThat(schema.queryType.fieldDefinitions.map { it.name })
            .`as`("location(address:) proxied a billed Google key and had no caller")
            .doesNotContain("location")
    }
}
