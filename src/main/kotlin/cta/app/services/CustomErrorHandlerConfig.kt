package cta.app.services

import graphql.GraphQLError
import graphql.GraphqlErrorBuilder
import graphql.schema.DataFetchingEnvironment
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.stereotype.Component


@Component
class CustomErrorHandlerConfig : DataFetcherExceptionResolverAdapter() {

    override fun resolveToSingleError(ex: Throwable, env: DataFetchingEnvironment): GraphQLError? {
        return GraphqlErrorBuilder.newError()
            .message(ex.message)
            .path(env.getExecutionStepInfo().getPath())
            .location(env.getField().getSourceLocation())
            .build()
    }
}