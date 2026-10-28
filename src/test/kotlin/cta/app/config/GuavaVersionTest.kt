package cta.app.config

import com.google.common.collect.ImmutableList
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Guava is not declared directly: it arrives through the Google API client used for Gmail
 * and Calendar, which asks for an older version. build.gradle constrains it to 33.7.2 or
 * newer. Dependabot cannot raise a PR for a transitive Gradle dependency, so if a client
 * bump drops that constraint nothing else would notice the regression.
 */
class GuavaVersionTest {
    @Test
    fun `guava on the classpath is at least 33_7_2`() {
        val jar = ImmutableList::class.java.protectionDomain.codeSource.location.path
        val match =
            Regex("""guava-(\d+)\.(\d+)\.(\d+)""").find(jar)
                ?: error("could not read the Guava version from $jar")
        val (major, minor, patch) = match.destructured.toList().map { it.toInt() }

        assertTrue(
            major * 1_000_000 + minor * 1_000 + patch >= 33_007_002,
            "Guava $major.$minor.$patch is older than 33.7.2 ($jar)",
        )
    }
}
