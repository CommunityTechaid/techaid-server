package cta.app.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Keeps [DevTechWildcard.AUTHORITIES] in step with the code: every authority named in a
 * `hasAuthority` / `hasAnyAuthority` check under src/main/kotlin must be either granted by the
 * wildcard or deliberately excluded from it. Without this, a new permission check would quietly
 * lock developers out of the new feature in UAT, and the wildcard would rot.
 *
 * It reads the source rather than reflecting over annotations because the checks live in
 * class- and method-level `@PreAuthorize` strings AND in plain `hasAuthority("...")` calls.
 */
class DevTechWildcardCoverageTest {
    private val check = Regex("""has(?:Any)?Authority\(([^)]*)\)""")
    private val quoted = Regex("""['"]([A-Za-z]+:[A-Za-z*]+)['"]""")

    private fun authoritiesCheckedInCode(): Set<String> =
        File("src/main/kotlin")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> check.findAll(file.readText()).map { it.groupValues[1] } }
            .flatMap { args -> quoted.findAll(args).map { it.groupValues[1] } }
            .toSet()

    @Test
    fun `the scan finds the checks (guards against a vacuous pass)`() {
        assertThat(authoritiesCheckedInCode()).contains("app:admin", "write:organisations", "write:users")
    }

    @Test
    fun `every authority checked in code is granted by the wildcard or deliberately excluded`() {
        assertThat(DevTechWildcard.AUTHORITIES + DevTechWildcard.EXCLUDED)
            .containsAll(authoritiesCheckedInCode())
    }

    @Test
    fun `the wildcard grants nothing the code never checks`() {
        assertThat(authoritiesCheckedInCode()).containsAll(DevTechWildcard.AUTHORITIES)
    }
}
