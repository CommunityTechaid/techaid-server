package cta.graphql

import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.autoconfigure.GraphQlProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.bean.override.mockito.MockitoBean

/**
 * Pins the two one-letter-apart GraphQL schema switches as **bound values**, not as text in a
 * yml file.
 *
 * `spring.graphql.schema.inspection.enabled: false` turns off Spring GraphQL's startup
 * schema-mapping report. It is off deliberately: SchemaMappingInspector fails on a Kotlin 2.x
 * null-method reflection issue, and this project is on Kotlin 2.x and heading for 2.3.
 *
 * The danger this test exists for is a **silent** one. If a future Spring Boot renames, moves or
 * removes the property, the key in `application.yml` becomes an unknown property — Spring ignores
 * unknown keys without complaint — and the bound value quietly reverts to Boot's default of
 * `true`. Nothing else in the suite would notice: `GraphQlIntrospectionDisabledTest` covers
 * `introspection`, which is a different setting one letter away.
 *
 * So each check compares what the yml SAYS against what Boot actually BOUND. Asserting only the
 * bound value would catch a rename too, but the comparison is what makes the failure legible:
 * it says "your config is being ignored" rather than "this is true and should be false".
 *
 * If this test fails after a Boot upgrade, do not "fix" it by changing the expected value. Find
 * the property's new name and move the yml key, then confirm the inspector is still off.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class GraphQlSchemaSwitchesTest {
    @MockitoBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var properties: GraphQlProperties

    @Autowired
    lateinit var environment: Environment

    @Test
    fun `schema mapping inspection is disabled, and the property is still the one Boot reads`() {
        val declared = environment.getProperty("spring.graphql.schema.inspection.enabled")

        assertEquals(
            "false",
            declared,
            "application.yml no longer declares spring.graphql.schema.inspection.enabled. " +
                "It is off on purpose - SchemaMappingInspector breaks on Kotlin 2.x reflection.",
        )
        assertFalse(
            properties.schema.inspection.isEnabled,
            "application.yml sets spring.graphql.schema.inspection.enabled=false but Boot bound " +
                "enabled=true, so the key is being ignored - it has most likely been renamed or " +
                "removed by a Spring Boot upgrade. Find its new name; do not change the expected " +
                "value here.",
        )
    }

    @Test
    fun `introspection is disabled, and the property is still the one Boot reads`() {
        val declared = environment.getProperty("spring.graphql.schema.introspection.enabled")

        assertEquals(
            "false",
            declared,
            "application.yml no longer declares spring.graphql.schema.introspection.enabled. " +
                "Production once enumerated the whole schema to anonymous callers without it.",
        )
        assertFalse(
            properties.schema.introspection.isEnabled,
            "application.yml sets spring.graphql.schema.introspection.enabled=false but Boot " +
                "bound enabled=true, so the key is being ignored - it has most likely been " +
                "renamed or removed by a Spring Boot upgrade. GraphQlIntrospectionDisabledTest " +
                "asserts the resulting behaviour; this asserts the config still reaches Boot.",
        )
    }
}
