package cta.app.config

import graphql.ExecutionResult
import graphql.GraphQLContext
import graphql.analysis.MaxQueryComplexityInstrumentation
import graphql.analysis.MaxQueryDepthInstrumentation
import graphql.analysis.QueryReducer
import graphql.analysis.QueryTraverser
import graphql.execution.AbortExecutionException
import graphql.execution.CoercedVariables
import graphql.execution.instrumentation.Instrumentation
import graphql.execution.instrumentation.InstrumentationContext
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.SimplePerformantInstrumentation
import graphql.execution.instrumentation.parameters.InstrumentationExecuteOperationParameters
import graphql.language.BooleanValue
import graphql.language.FloatValue
import graphql.language.IntValue
import graphql.language.StringValue
import graphql.language.Value
import graphql.parser.ParserOptions
import graphql.scalars.ExtendedScalars
import graphql.schema.*
import graphql.schema.idl.RuntimeWiring
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.graphql.execution.RuntimeWiringConfigurer
import org.springframework.graphql.server.WebGraphQlInterceptor
import org.springframework.graphql.server.WebGraphQlRequest
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.*

@Configuration
public class GraphQlConfig {
    /**
     * Rejects absurdly nested documents before any data fetcher runs.
     *
     * There was no depth or complexity bound at all, and the public surface is anonymous, so one
     * request could alias a nested selection hundreds of levels deep and fan out into hundreds of
     * unpaginated queries against a single scale-to-zero replica - outside the per-IP booking
     * limiters, which only cover the delivery endpoints.
     *
     * The limit is deliberately generous. The deepest document the dashboard actually sends is
     * depth 6 (kitsConnection -> content -> deviceRequest -> referringOrganisationContact ->
     * referringOrganisation -> name), so 15 leaves well over twice the headroom a real query
     * needs. Validation runs before authorisation, so a document that breaches this is refused
     * for authenticated staff too - which is why the headroom matters.
     */
    @Bean
    fun maxQueryDepthInstrumentation(): Instrumentation = MaxQueryDepthInstrumentation(MAX_QUERY_DEPTH)

    /*
     * Depth does not bound cost. A small anonymous document can still repeat an expensive public
     * root field under many aliases, or multiply a selection through fragments, so three more
     * limits apply, all before any data fetcher runs (and so, like depth, to staff as well).
     *
     * Headroom was measured, not guessed: all 111 gql documents in techaid-dashboard/src (plus the
     * hand-written public ones, which are smaller), counted as Apollo sends them, i.e. with
     * __typename added to every selection set, on 2026-10-24:
     *   - most root fields:  5    (dashboard-index findAll)       -> MAX_ROOT_FIELDS 20        (4x)
     *   - most fields:       64   (kit-info findKit)              -> MAX_QUERY_COMPLEXITY 300  (~4.7x)
     *   - most parse tokens: 204  (device-request-component)      -> MAX_QUERY_TOKENS 1000     (~4.9x)
     *   - most aliases: 10, deepest: 6 - aliases are bounded by the root-field and complexity caps.
     * Re-measure before tightening any of these; GraphQlQueryCostLimitTest replays the two largest.
     */

    /**
     * Total selected fields - each field (including __typename) costs 1. Note graphql-java counts a
     * named fragment's fields once however often it is spread, so this is not a fragment-expansion
     * bound; the root-field cap is what bounds repeated root resolvers.
     */
    @Bean
    fun maxQueryComplexityInstrumentation(): Instrumentation = MaxQueryComplexityInstrumentation(MAX_QUERY_COMPLEXITY)

    /** Each root field is its own repository call, so the fan-out of one request is capped directly. */
    @Bean
    fun maxRootFieldsInstrumentation(): Instrumentation = MaxRootFieldsInstrumentation(MAX_ROOT_FIELDS)

    /**
     * graphql-java's default parser limit is 15,000 tokens, which is room for thousands of aliased
     * fields before validation or the instrumentations above even see the document.
     */
    @Bean
    fun parserLimitsInterceptor(): WebGraphQlInterceptor {
        val parserOptions = ParserOptions.getDefaultOperationParserOptions().transform { it.maxTokens(MAX_QUERY_TOKENS) }
        return WebGraphQlInterceptor { request: WebGraphQlRequest, chain: WebGraphQlInterceptor.Chain ->
            request.configureExecutionInput { input, _ ->
                input.graphQLContext.put(ParserOptions::class.java, parserOptions)
                input
            }
            chain.next(request)
        }
    }

    @Bean
    fun runtimeWiringConfigurer(
        instantScalar: GraphQLScalarType,
        lenientStringScalar: GraphQLScalarType,
    ): RuntimeWiringConfigurer =
        RuntimeWiringConfigurer { wiringBuilder: RuntimeWiring.Builder ->
            wiringBuilder
                .scalar(ExtendedScalars.GraphQLBigDecimal)
                .scalar(ExtendedScalars.GraphQLLong)
                .scalar(instantScalar)
                .scalar(lenientStringScalar)
        }

    companion object {
        /** Maximum field-selection depth accepted on any document. Deepest real query is 6. */
        const val MAX_QUERY_DEPTH: Int = 15

        /** Maximum root fields per operation, fragments expanded. Most in a real query is 5. */
        const val MAX_ROOT_FIELDS: Int = 20

        /** Maximum total fields per operation, fragments expanded. Most in a real query is 64. */
        const val MAX_QUERY_COMPLEXITY: Int = 300

        /** Maximum parser tokens per document. Most in a real query is 204. */
        const val MAX_QUERY_TOKENS: Int = 1000
    }
}

/** Refuses an operation whose root selection (fragments and @skip/@include resolved) has more than [maxRootFields] fields. */
class MaxRootFieldsInstrumentation(
    private val maxRootFields: Int,
) : SimplePerformantInstrumentation() {
    override fun beginExecuteOperation(
        parameters: InstrumentationExecuteOperationParameters,
        state: InstrumentationState?,
    ): InstrumentationContext<ExecutionResult>? {
        val context = parameters.executionContext
        val rootFields =
            QueryTraverser
                .newQueryTraverser()
                .schema(context.graphQLSchema)
                .document(context.document)
                .operationName(context.operationDefinition.name)
                .coercedVariables(context.coercedVariables)
                .build()
                .reducePreOrder(QueryReducer<Int> { field, count -> if (field.parentEnvironment == null) count + 1 else count }, 0)
        if (rootFields > maxRootFields) {
            throw AbortExecutionException("Query has $rootFields root fields, more than the maximum of $maxRootFields root fields")
        }
        return super.beginExecuteOperation(parameters, state)
    }
}

@Configuration
class GraphQLScalarConfig {
    @Bean
    fun instantScalar(): GraphQLScalarType =
        GraphQLScalarType
            .Builder()
            .name("Instant")
            .description("A custom scalar that handles java.time.Instant in ISO-8601 format")
            .coercing(
                object : Coercing<Instant, String> {
                    override fun serialize(
                        dataFetcherResult: Any,
                        graphQLContext: GraphQLContext,
                        locale: Locale,
                    ): String =
                        when (dataFetcherResult) {
                            is Instant -> dataFetcherResult.toString()
                            else -> throw CoercingSerializeException("Expected Instant object.")
                        }

                    override fun parseValue(
                        input: Any,
                        graphQLContext: GraphQLContext,
                        locale: Locale,
                    ): Instant =
                        try {
                            Instant.parse(input.toString())
                        } catch (e: DateTimeParseException) {
                            throw CoercingParseValueException("Invalid ISO date-time: $input")
                        }

                    override fun parseLiteral(
                        input: Value<*>,
                        variables: CoercedVariables,
                        graphQLContext: GraphQLContext,
                        locale: Locale,
                    ): Instant? =
                        try {
                            Instant.parse(input.toString())
                        } catch (e: DateTimeParseException) {
                            throw CoercingParseLiteralException("Invalid ISO date-time: $input")
                        }
                },
            ).build()

    /**
     * A String-like scalar with lenient input coercion: numbers and booleans are accepted and
     * stringified, instead of being rejected with "Expected a String input, but it was a 'Integer'".
     *
     * Applied (in the SDL) to numeric-prone text fields on kit inputs — lotId, locationCode,
     * serialNo — so bulk/spreadsheet imports that send a numeric cell (e.g. a numeric lotId like
     * 26060301) no longer fail. It is a distinct scalar name, not a redefinition of the built-in
     * String, so the schema assembles cleanly. Output serialisation matches the default String
     * scalar (toString); objects/lists are still rejected so structural mismatches still surface.
     */
    @Bean
    fun lenientStringScalar(): GraphQLScalarType =
        GraphQLScalarType
            .Builder()
            .name("LenientString")
            .description("A String input that also accepts numbers and booleans, coercing them to their string form.")
            .coercing(
                object : Coercing<String, String> {
                    override fun serialize(
                        dataFetcherResult: Any,
                        graphQLContext: GraphQLContext,
                        locale: Locale,
                    ): String = dataFetcherResult.toString()

                    override fun parseValue(
                        input: Any,
                        graphQLContext: GraphQLContext,
                        locale: Locale,
                    ): String =
                        when (input) {
                            is String -> input

                            is Number, is Boolean -> input.toString()

                            else -> throw CoercingParseValueException(
                                "Expected a String, Number or Boolean but was '${input::class.simpleName}'.",
                            )
                        }

                    override fun parseLiteral(
                        input: Value<*>,
                        variables: CoercedVariables,
                        graphQLContext: GraphQLContext,
                        locale: Locale,
                    ): String =
                        when (input) {
                            is StringValue -> {
                                input.value
                                    ?: throw CoercingParseLiteralException(
                                        "Expected a non-null string literal.",
                                    )
                            }

                            is IntValue -> {
                                input.value.toString()
                            }

                            is FloatValue -> {
                                input.value.toString()
                            }

                            is BooleanValue -> {
                                input.isValue.toString()
                            }

                            else -> {
                                throw CoercingParseLiteralException(
                                    "Expected a scalar literal but was '${input::class.simpleName}'.",
                                )
                            }
                        }
                },
            ).build()
}
