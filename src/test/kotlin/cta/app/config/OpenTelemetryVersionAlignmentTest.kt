package cta.app.config

import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OpenTelemetryVersionAlignmentTest {
    /**
     * opentelemetry-api and opentelemetry-context ship as a matched set. Pinning the api
     * while the Spring Boot BOM moves context left them on different versions (1.46.0 vs
     * 1.62.0). A mismatch fails silently at runtime: GraphQlTelemetryInterceptor wraps its
     * span calls in runCatching, so operation names just vanish from App Insights.
     */
    @Test
    fun `api and context resolve to the same version`() {
        assertEquals(jarVersion(Context::class.java), jarVersion(Span::class.java))
    }

    private fun jarVersion(type: Class<*>): String {
        val jar =
            type.protectionDomain.codeSource.location.path
                .substringAfterLast('/')
        return Regex("""-(\d+\.\d+\.\d+)\.jar$""").find(jar)?.groupValues?.get(1)
            ?: error("cannot read a version from $jar")
    }
}
