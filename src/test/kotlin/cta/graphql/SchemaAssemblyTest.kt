package cta.graphql

import cta.app.config.GraphQLScalarConfig
import graphql.scalars.ExtendedScalars
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.TypeDefinitionRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * Assembles the real schema (every *.graphqls plus the project's custom scalar wiring) with no
 * Spring context or database. Guards the scalar wiring: the earlier attempt to redefine the
 * built-in String scalar failed at exactly this step with a graphql.AssertException, which only
 * surfaced in CI. This turns that class of failure into a fast local check.
 */
class SchemaAssemblyTest {
    private fun assembleSchema(): graphql.schema.GraphQLSchema {
        val scalars = GraphQLScalarConfig()
        val registry = TypeDefinitionRegistry()
        val parser = SchemaParser()
        PathMatchingResourcePatternResolver()
            .getResources("classpath*:graphql/*.graphqls")
            .forEach { resource ->
                resource.inputStream.reader().use { reader -> registry.merge(parser.parse(reader)) }
            }

        val wiring =
            RuntimeWiring
                .newRuntimeWiring()
                .scalar(ExtendedScalars.GraphQLBigDecimal)
                .scalar(ExtendedScalars.GraphQLLong)
                .scalar(scalars.instantScalar())
                .scalar(scalars.lenientStringScalar())
                .build()

        // Throws graphql.AssertException if a scalar is mis-wired (e.g. redefining a built-in).
        return SchemaGenerator().makeExecutableSchema(registry, wiring)
    }

    @Test
    fun `schema assembles with custom scalar wiring and exposes LenientString`() {
        val schema = assembleSchema()

        assertNotNull(schema.getType("LenientString"), "LenientString scalar should be in the schema")
        // lotId on CreateKitInput must resolve to LenientString, not the built-in String.
        val lotId = (schema.getType("CreateKitInput") as graphql.schema.GraphQLInputObjectType).getField("lotId")
        assertEquals("LenientString", (lotId.type as graphql.schema.GraphQLNamedType).name)
    }

    // Wipe-cert linkage (#68): the reference is operator-entered at the wiping station and
    // may look numeric, so it must ride LenientString like lotId/serialNo — a plain String
    // would reject numeric JSON from the upstream automation.
    @Test
    fun `kit update inputs expose wipeCertReference as LenientString`() {
        val schema = assembleSchema()

        listOf("UpdateKitInput", "AutoUpdateKitInput").forEach { typeName ->
            val field =
                (schema.getType(typeName) as graphql.schema.GraphQLInputObjectType)
                    .getField("wipeCertReference")
            assertNotNull(field, "$typeName.wipeCertReference should be in the schema")
            assertEquals("LenientString", (field.type as graphql.schema.GraphQLNamedType).name)
        }
    }

    // Membership (#311): the contract the dashboard is built against, by exact name.
    @Test
    fun `membership types and fields match the contract the dashboard builds against`() {
        val schema = assembleSchema()

        fun fields(type: String) = (schema.getType(type) as graphql.schema.GraphQLFieldsContainer).fieldDefinitions.map { it.name }.toSet()

        fun enumValues(type: String) = (schema.getType(type) as graphql.schema.GraphQLEnumType).values.map { it.name }.toSet()

        assertEquals(
            setOf("tier", "periodStart", "periodEnd", "cap", "used", "remaining", "state", "graceUsed"),
            fields("Membership"),
        )
        assertEquals(
            setOf("tier", "state", "cap", "used", "remaining", "periodEnd", "graceUsed", "graceRemaining"),
            fields("MembershipPublic"),
        )
        assertEquals(setOf("FREE", "TIER_1", "TIER_2", "TIER_3"), enumValues("MembershipTier"))
        assertEquals(setOf("OK", "LAST_REQUEST", "LIMIT_REACHED"), enumValues("MembershipState"))
        assertEquals(setOf("GRACE", "BLOCK"), enumValues("MembershipOverLimitMode"))

        assertEquals(true, "membershipAllowance" in fields("ReferringOrganisationContactPublic"))
        assertEquals(true, "membershipGraceIndex" in fields("DeviceRequestPublic"))
        assertEquals(true, "membershipGraceIndex" in fields("DeviceRequest"))
        assertEquals(true, "isMembershipContact" in fields("ReferringOrganisationContact"))
        assertEquals(
            setOf("membershipTier", "membershipStartDate", "membership", "membershipContacts"),
            fields("ReferringOrganisation").filter { it.startsWith("membership") }.toSet(),
        )
        assertEquals(true, fields("AdminConfig").containsAll(setOf("membershipOverLimitMode", "membershipGraceRequests")))
    }

    // Dates are plain strings (YYYY-MM-DD), never the Instant scalar, and every new update-input
    // field is nullable so that null or absent can mean "unchanged".
    @Test
    fun `membership dates are plain strings and new input fields are nullable`() {
        val schema = assembleSchema()

        fun input(
            type: String,
            field: String,
        ) = (schema.getType(type) as graphql.schema.GraphQLInputObjectType).getField(field).type

        assertEquals("String", (input("UpdateReferringOrganisationInput", "membershipStartDate") as graphql.schema.GraphQLNamedType).name)
        listOf(
            "UpdateReferringOrganisationInput" to "membershipTier",
            "UpdateReferringOrganisationContactInput" to "isMembershipContact",
            "CreateReferringOrganisationContactInput" to "isMembershipContact",
            "UpdateAdminConfigInput" to "membershipOverLimitMode",
            "UpdateAdminConfigInput" to "membershipGraceRequests",
        ).forEach { (type, field) ->
            assertEquals(false, input(type, field) is graphql.schema.GraphQLNonNull, "$type.$field must be nullable")
        }

        val membership = schema.getType("Membership") as graphql.schema.GraphQLObjectType
        listOf("periodStart", "periodEnd").forEach {
            val type = membership.getFieldDefinition(it).type as graphql.schema.GraphQLNonNull
            assertEquals("String", (type.wrappedType as graphql.schema.GraphQLNamedType).name)
        }

        val where = schema.getType("ReferringOrganisationWhereInput") as graphql.schema.GraphQLInputObjectType
        assertEquals("MembershipTierComparison", (where.getField("membershipTier").type as graphql.schema.GraphQLNamedType).name)
        val contactWhere = schema.getType("ReferringOrganisationContactWhereInput") as graphql.schema.GraphQLInputObjectType
        assertEquals("BooleanComparison", (contactWhere.getField("isMembershipContact").type as graphql.schema.GraphQLNamedType).name)
    }
}
