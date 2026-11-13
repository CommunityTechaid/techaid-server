package cta.app.config

import graphql.GraphQLError
import io.opentelemetry.api.trace.Span
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.graphql.server.WebGraphQlInterceptor
import org.springframework.graphql.server.WebGraphQlRequest
import org.springframework.graphql.server.WebGraphQlResponse
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import tools.jackson.databind.ObjectMapper

/**
 * Request attribute name used to surface the GraphQL operation name to the outer
 * access-log filter, which writes its JSON line AFTER this interceptor's doFinally
 * has cleared MDC. The MDC value alone is too short-lived to reach the access log.
 */
const val GRAPHQL_OPERATION_REQUEST_ATTRIBUTE = "cta.graphql.operation"

@Configuration
class GraphQlTelemetryConfig {
    private val log: Logger = LoggerFactory.getLogger(GraphQlTelemetryConfig::class.java)
    private val mapper = ObjectMapper()

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
                // Enrich the OpenTelemetry span so App Insights AppRequests shows the
                // operation name instead of a generic "POST /graphql". Guarded so any
                // agent/classloader issue degrades to "no enrichment" rather than 500.
                runCatching {
                    Span.current().setAttribute("graphql.operation", operationName)
                    Span.current().updateName("POST /graphql $operationName")
                }
            }
            // Absent header: null, and nothing below changes. Present: the validated ref, or the
            // fixed placeholder for the log line only - a rejected value is never logged or returned.
            val rawRef = request.headers.getFirst(CLIENT_ERROR_REF_HEADER)
            val clientRef = validClientErrorRef(rawRef)
            val loggedRef = if (rawRef == null) null else clientRef ?: INVALID_CLIENT_ERROR_REF
            if (clientRef != null) {
                // Lets CustomErrorHandlerConfig quote this ref instead of minting its own. Passed in
                // the GraphQL context rather than MDC: data fetchers may run on another thread.
                request.configureExecutionInput { _, builder ->
                    builder.graphQLContext(mapOf(CLIENT_ERROR_REF_CONTEXT_KEY to clientRef)).build()
                }
            }
            chain
                .next(request)
                .doOnNext { response -> logGraphQlErrors(operationName, loggedRef, request, response) }
                .map { response -> if (clientRef == null) response else withRefExtension(response, clientRef) }
                .doFinally { MDC.remove("graphql.operation") }
        }

    /**
     * Echo the client ref in each error's `extensions.ref`, so the caller can tell the server
     * received it (a missing ref means something on the way stripped the header). Successful
     * responses are returned untouched.
     */
    private fun withRefExtension(
        response: WebGraphQlResponse,
        ref: String,
    ): WebGraphQlResponse {
        val errors = response.executionResult.errors
        if (errors.isEmpty()) return response
        return response.transform { builder -> builder.errors(errors.map { RefExtendedError(it, ref) }) }
    }

    /**
     * GraphQL errors — including pre-execution validation/variable-coercion errors such as
     * "Variable 'data' has an invalid value: Expected a String input, but it was a 'Integer'"
     * — are returned in the response body with HTTP 200. They therefore never reach
     * [cta.app.services.CustomErrorHandlerConfig] (which only handles data-fetcher exceptions)
     * and never show up as failed requests in the access log or App Insights.
     *
     * Log them here, together with the request's input variables, so malformed inputs (e.g. a
     * spreadsheet bulk-insert sending a numeric cell into a String field) can be diagnosed.
     * Variables are JSON-serialised so a number sent into a String field ("model": 3000) is
     * distinguishable from a valid string value ("model": "3000").
     *
     * NOTE: variables may contain personal data for some mutations. This fires only on error
     * responses, never on successful requests.
     */
    private fun logGraphQlErrors(
        operationName: String?,
        ref: String?,
        request: WebGraphQlRequest,
        response: WebGraphQlResponse,
    ) {
        if (response.errors.isEmpty()) return
        val operation = operationName ?: "<anonymous>"
        val variables =
            runCatching { mapper.writeValueAsString(request.variables) }
                .getOrElse { request.variables.toString() }
        response.errors.forEach { error ->
            if (ref == null) {
                log.warn(
                    "GraphQL error on operation '{}' [{}]: {} (path={}, locations={}) — input variables: {}",
                    operation,
                    error.errorType,
                    error.message,
                    error.path,
                    error.locations,
                    variables,
                )
            } else {
                log.warn(
                    "GraphQL error on operation '{}' [{}] [ref {}]: {} (path={}, locations={}) — input variables: {}",
                    operation,
                    error.errorType,
                    ref,
                    error.message,
                    error.path,
                    error.locations,
                    variables,
                )
            }
        }
    }

    private fun resolveOperationName(request: WebGraphQlRequest): String? {
        val explicit = request.operationName
        if (!explicit.isNullOrBlank()) return explicit

        val document = request.document ?: return null

        // Named operation: `query foo { ... }`
        Regex("""(?:query|mutation|subscription)\s+(\w+)""").find(document)?.let {
            return it.groupValues[1]
        }

        // Shorthand: `{ buildInfo { ... } }` — use the first top-level field name
        return Regex("""^\s*\{\s*(\w+)""").find(document)?.groupValues?.get(1)
    }
}

/**
 * Wraps an error to add `extensions.ref` while keeping everything else - message, path,
 * locations, classification and any existing extensions - exactly as the original serialises it.
 */
private class RefExtendedError(
    private val delegate: GraphQLError,
    private val ref: String,
) : GraphQLError {
    override fun getMessage(): String? = delegate.message

    override fun getLocations() = delegate.locations

    override fun getErrorType() = delegate.errorType

    override fun getPath(): List<Any>? = delegate.path

    override fun getExtensions(): Map<String, Any?> = (delegate.extensions ?: emptyMap()) + (REF_EXTENSION to ref)

    override fun toSpecification(): Map<String, Any?> {
        val specification = LinkedHashMap<String, Any?>(delegate.toSpecification())
        val extensions = LinkedHashMap<Any?, Any?>((specification["extensions"] as? Map<*, *>).orEmpty())
        extensions[REF_EXTENSION] = ref
        specification["extensions"] = extensions
        return specification
    }

    private companion object {
        const val REF_EXTENSION = "ref"
    }
}
