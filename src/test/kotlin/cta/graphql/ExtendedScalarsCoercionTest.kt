package cta.graphql

import graphql.ExecutionInput
import graphql.GraphQL
import graphql.scalars.ExtendedScalars
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Pins how the graphql-java-extended-scalars `Long` and `BigDecimal` scalars (the only two wired
 * in GraphQlConfig.runtimeWiringConfigurer) parse variables and literals and serialise output,
 * so a version bump of that library cannot silently change the API's wire behaviour.
 *
 * Long carries delivery ctaReference, audit ids and KitRevision id/timestamp, and every
 * LongComparison filter; BigDecimal appears only in BigDecimalComparison.
 *
 * Approach: real GraphQL execution (parse, validate, coerce, serialise) against a minimal
 * test-only schema. It imports the REAL `LongComparison` / `BigDecimalComparison` input types
 * from filters.graphqls and registers the same scalars as production, with trivial echo
 * fetchers. Real query fields were not used: they need DB fixtures and auth for no extra
 * coverage of the scalars.
 */
class ExtendedScalarsCoercionTest {
    private val graphQl: GraphQL =
        run {
            val parser = SchemaParser()
            val registry = parser.parse(javaClass.getResourceAsStream("/graphql/filters.graphqls")!!.reader())
            registry.merge(
                parser.parse(
                    """
                    scalar Long
                    scalar BigDecimal

                    type Query {
                        echoLong(v: Long): Long
                        echoLongs(v: [Long]): [Long]
                        echoBigDecimal(v: BigDecimal): BigDecimal
                        filterLong(f: LongComparison): [Long]
                        filterBigDecimal(f: BigDecimalComparison): [BigDecimal]
                        bigLong: Long
                        stringLong: Long
                        decimal: BigDecimal
                    }
                    """.trimIndent(),
                ),
            )
            val wiring =
                RuntimeWiring
                    .newRuntimeWiring()
                    .scalar(ExtendedScalars.GraphQLBigDecimal)
                    .scalar(ExtendedScalars.GraphQLLong)
                    .scalar(
                        cta.app.config
                            .GraphQLScalarConfig()
                            .instantScalar(),
                    ).scalar(
                        cta.app.config
                            .GraphQLScalarConfig()
                            .lenientStringScalar(),
                    ).type("Query") { t ->
                        t
                            .dataFetcher("echoLong") { it.getArgument<Any>("v") }
                            .dataFetcher("echoLongs") { it.getArgument<Any>("v") }
                            .dataFetcher("echoBigDecimal") { it.getArgument<Any>("v") }
                            .dataFetcher("filterLong") { it.getArgument<Map<String, Any?>>("f")?.get("_in") }
                            .dataFetcher("filterBigDecimal") { it.getArgument<Map<String, Any?>>("f")?.get("_in") }
                            .dataFetcher("bigLong") { 3_000_000_000L }
                            .dataFetcher("stringLong") { "123" }
                            .dataFetcher("decimal") { BigDecimal("12.50") }
                    }.build()
            GraphQL.newGraphQL(SchemaGenerator().makeExecutableSchema(registry, wiring)).build()
        }

    private fun run(
        query: String,
        variables: Map<String, Any?> = emptyMap(),
    ) = graphQl.execute(
        ExecutionInput
            .newExecutionInput()
            .query(query)
            .variables(variables)
            .build(),
    )

    private fun data(
        query: String,
        variables: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> {
        val result = run(query, variables)
        assertTrue(result.errors.isEmpty(), "unexpected errors: ${result.errors}")
        return result.getData()
    }

    @Test
    fun `Long variable accepts a small integer and a value above Int MAX`() {
        val q = "query(\$v: Long) { echoLong(v: \$v) }"
        assertEquals(26060301L, data(q, mapOf("v" to 26060301))["echoLong"])
        assertEquals(3_000_000_000L, data(q, mapOf("v" to 3_000_000_000L))["echoLong"])
        assertEquals(Long.MAX_VALUE, data(q, mapOf("v" to Long.MAX_VALUE))["echoLong"])
    }

    @Test
    fun `Long variable given as a quoted string`() {
        val result = run("query(\$v: Long) { echoLong(v: \$v) }", mapOf("v" to "123"))
        // Observed on 19.0: the string is parsed as a number and ACCEPTED.
        assertTrue(result.errors.isEmpty(), "string Long variable: ${result.errors}")
        assertEquals(123L, result.getData<Map<String, Any?>>()["echoLong"])
    }

    @Test
    fun `Long literal in the query document`() {
        assertEquals(26060301L, data("{ echoLong(v: 26060301) }")["echoLong"])
        assertEquals(3_000_000_000L, data("{ echoLong(v: 3000000000) }")["echoLong"])
    }

    @Test
    fun `Long string literal in the query document`() {
        val result = run("{ echoLong(v: \"123\") }")
        // Observed on 19.0: a quoted string literal is ACCEPTED and parsed as a number, like the variable.
        assertTrue(result.errors.isEmpty(), "errors: ${result.errors}")
        assertEquals(123L, result.getData<Map<String, Any?>>()["echoLong"])
    }

    @Test
    fun `Long non-numeric and fractional variables are rejected`() {
        val q = "query(\$v: Long) { echoLong(v: \$v) }"
        assertEquals(1, run(q, mapOf("v" to "abc")).errors.size)
        assertEquals(1, run(q, mapOf("v" to 1.5)).errors.size)
    }

    @Test
    fun `Long inside a LongComparison filter list`() {
        val q = "query(\$f: LongComparison) { filterLong(f: \$f) }"
        assertEquals(listOf(1L, 3_000_000_000L), data(q, mapOf("f" to mapOf("_in" to listOf(1, 3_000_000_000L))))["filterLong"])
        assertEquals(listOf(5L), data("{ filterLong(f: {_in: [5]}) }")["filterLong"])
    }

    @Test
    fun `Long output serialises as a JSON number`() {
        assertEquals(3_000_000_000L, data("{ bigLong }")["bigLong"])
    }

    @Test
    fun `Long output from a numeric String value`() {
        val result = run("{ stringLong }")
        // Observed on 19.0: serialising the String "123" succeeds and yields the Long 123.
        assertTrue(result.errors.isEmpty(), "errors: ${result.errors}")
        assertEquals(123L, result.getData<Map<String, Any?>>()["stringLong"])
    }

    @Test
    fun `BigDecimal variable from number and from string`() {
        val q = "query(\$v: BigDecimal) { echoBigDecimal(v: \$v) }"
        assertEquals(BigDecimal("12.34"), data(q, mapOf("v" to BigDecimal("12.34")))["echoBigDecimal"])
        assertEquals(BigDecimal("12.34"), data(q, mapOf("v" to 12.34))["echoBigDecimal"])
        assertEquals(BigDecimal("12.34"), data(q, mapOf("v" to "12.34"))["echoBigDecimal"])
        assertEquals(BigDecimal("7"), data(q, mapOf("v" to 7))["echoBigDecimal"])
        assertEquals(1, run(q, mapOf("v" to "abc")).errors.size)
    }

    @Test
    fun `BigDecimal literal in the query document`() {
        assertEquals(BigDecimal("12.34"), data("{ echoBigDecimal(v: 12.34) }")["echoBigDecimal"])
        assertEquals(BigDecimal("7"), data("{ echoBigDecimal(v: 7) }")["echoBigDecimal"])
    }

    @Test
    fun `BigDecimal inside a BigDecimalComparison filter`() {
        val q = "query(\$f: BigDecimalComparison) { filterBigDecimal(f: \$f) }"
        assertEquals(
            listOf(BigDecimal("1.5"), BigDecimal("2")),
            data(q, mapOf("f" to mapOf("_in" to listOf(1.5, 2))))["filterBigDecimal"],
        )
    }

    @Test
    fun `BigDecimal output keeps its scale`() {
        assertEquals(BigDecimal("12.50"), data("{ decimal }")["decimal"])
    }
}
