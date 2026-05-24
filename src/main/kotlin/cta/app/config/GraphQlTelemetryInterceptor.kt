package cta.app.config

import org.slf4j.MDC
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.graphql.server.WebGraphQlInterceptor
import org.springframework.graphql.server.WebGraphQlRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/**
 * Request attribute name used to surface the GraphQL operation name to the outer
 * access-log filter, which writes its JSON line AFTER this interceptor's doFinally
 * has cleared MDC. The MDC value alone is too short-lived to reach the access log.
 */
const val GRAPHQL_OPERATION_REQUEST_ATTRIBUTE = "cta.graphql.operation"

@Configuration
class GraphQlTelemetryConfig {
    @Bean
    fun graphQlTelemetryInterceptor(): WebGraphQlInterceptor =
        WebGraphQlInterceptor { request: WebGraphQlRequest, chain: WebGraphQlInterceptor.Chain ->
            val operationName = resolveOperationName(request)
            if (operationName != null) {
                MDC.put("graphql.operation", operationName)
                // Also stash on the HttpServletRequest so AccessLoggingFilter (which runs
                // its finally block AFTER our doFinally clears MDC) can include it.
                (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)
                    ?.request
                    ?.setAttribute(GRAPHQL_OPERATION_REQUEST_ATTRIBUTE, operationName)
            }
            chain
                .next(request)
                .doFinally { MDC.remove("graphql.operation") }
        }

    private fun resolveOperationName(request: WebGraphQlRequest): String? {
        val explicit = request.operationName
        if (!explicit.isNullOrBlank()) return explicit

        // Fall back to the first named operation in the document text
        val document = request.document ?: return null
        val match = Regex("""(?:query|mutation|subscription)\s+(\w+)""").find(document)
        return match?.groupValues?.get(1)
    }
}
