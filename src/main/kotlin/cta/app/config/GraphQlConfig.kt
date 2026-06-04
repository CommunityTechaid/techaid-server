package cta.app.config

import graphql.GraphQLContext
import graphql.execution.CoercedVariables
import graphql.language.BooleanValue
import graphql.language.FloatValue
import graphql.language.IntValue
import graphql.language.StringValue
import graphql.language.Value
import graphql.scalars.ExtendedScalars
import graphql.schema.*
import graphql.schema.idl.RuntimeWiring
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.graphql.execution.RuntimeWiringConfigurer
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.*

@Configuration
public class GraphQlConfig {
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
                            is StringValue -> input.value
                            is IntValue -> input.value.toString()
                            is FloatValue -> input.value.toString()
                            is BooleanValue -> input.isValue.toString()
                            else -> throw CoercingParseLiteralException(
                                "Expected a scalar literal but was '${input::class.simpleName}'.",
                            )
                        }
                },
            ).build()
}
