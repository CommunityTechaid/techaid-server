package cta.app.config

import graphql.GraphQLContext
import graphql.execution.CoercedVariables
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
    fun runtimeWiringConfigurer(instantScalar: GraphQLScalarType): RuntimeWiringConfigurer {
        return RuntimeWiringConfigurer { wiringBuilder: RuntimeWiring.Builder ->
            wiringBuilder
                .scalar(ExtendedScalars.GraphQLBigDecimal)
                .scalar(ExtendedScalars.GraphQLLong)
                .scalar(instantScalar)


        }
    }
}


@Configuration
class GraphQLScalarConfig {

    @Bean
    fun instantScalar(): GraphQLScalarType {
        return GraphQLScalarType.Builder()
            .name("Instant")
            .description("A custom scalar that handles java.time.Instant in ISO-8601 format")
            .coercing(object : Coercing<Instant, String> {

                override fun serialize(dataFetcherResult: Any, graphQLContext: GraphQLContext, locale: Locale): String {
                    return when (dataFetcherResult) {
                        is Instant -> dataFetcherResult.toString()
                        else -> throw CoercingSerializeException("Expected Instant object.")
                    }
                }

                override fun parseValue(input: Any, graphQLContext: GraphQLContext, locale: Locale): Instant {
                    return try {
                        Instant.parse(input.toString())
                    } catch (e: DateTimeParseException) {
                        throw CoercingParseValueException("Invalid ISO date-time: $input")
                    }
                }

                override fun parseLiteral(
                    input: Value<*>,
                    variables: CoercedVariables,
                    graphQLContext: GraphQLContext,
                    locale: Locale
                ): Instant? {
                    return try {
                        Instant.parse(input.toString())
                    } catch (e: DateTimeParseException) {
                        throw CoercingParseLiteralException("Invalid ISO date-time: $input")
                    }
                }
            })
            .build()
    }

}
