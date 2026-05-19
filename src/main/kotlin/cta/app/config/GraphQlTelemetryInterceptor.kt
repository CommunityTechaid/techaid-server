package cta.app.config

import org.slf4j.MDC
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.graphql.server.WebGraphQlInterceptor
import org.springframework.graphql.server.WebGraphQlRequest
import org.springframework.graphql.server.WebGraphQlResponse
import reactor.core.publisher.Mono

@Configuration
class GraphQlTelemetryConfig {
    @Bean
    fun graphQlTelemetryInterceptor(): WebGraphQlInterceptor =
        WebGraphQlInterceptor { request: WebGraphQlRequest, chain: WebGraphQlInterceptor.Chain ->
            val operationName = resolveOperationName(request)
            if (operationName != null) {
                MDC.put("graphql.operation", operationName)
            }
            chain.next(request)
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
