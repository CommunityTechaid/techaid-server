package cta.app.config

import io.opentelemetry.api.OpenTelemetry
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The OpenTelemetry API version comes from the Spring Boot BOM unless build.gradle overrides
 * `opentelemetry.version`. The override keeps it at 1.67.0 or newer. Dependabot cannot raise a
 * PR for a BOM-managed Gradle dependency, so if the override is lost nothing else would notice.
 * Drop the override (and this test) once the Boot BOM manages 1.67.0+.
 */
class OpenTelemetryVersionTest {
    @Test
    fun `opentelemetry-api on the classpath is at least 1_67_0`() {
        val jar = OpenTelemetry::class.java.protectionDomain.codeSource.location.path
        val match =
            Regex("""opentelemetry-api-(\d+)\.(\d+)\.(\d+)""").find(jar)
                ?: error("could not read the OpenTelemetry API version from $jar")
        val (major, minor, patch) = match.destructured.toList().map { it.toInt() }

        assertTrue(
            major * 1_000_000 + minor * 1_000 + patch >= 1_067_000,
            "opentelemetry-api $major.$minor.$patch is older than 1.67.0 ($jar)",
        )
    }
}
