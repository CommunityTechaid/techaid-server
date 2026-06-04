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
    @Test
    fun `schema assembles with custom scalar wiring and exposes LenientString`() {
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
        val schema = SchemaGenerator().makeExecutableSchema(registry, wiring)

        assertNotNull(schema.getType("LenientString"), "LenientString scalar should be in the schema")
        // lotId on CreateKitInput must resolve to LenientString, not the built-in String.
        val lotId = (schema.getType("CreateKitInput") as graphql.schema.GraphQLInputObjectType).getField("lotId")
        assertEquals("LenientString", (lotId.type as graphql.schema.GraphQLNamedType).name)
    }
}
