package cta.app.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * #348: request and referee guards accept only their own scopes (`*:requests`, `*:referees`). An
 * `*:organisations` scope may not stand in for them any more, so no `@PreAuthorize` that names a
 * request or referee scope may also name an organisations scope.
 *
 * The exceptions are organisation or delivery-admin data that request staff can also read; they
 * are listed by function name with the reason.
 *
 * Reads the source (like [DevTechWildcardCoverageTest]) so class-level guards are covered too.
 */
class OrganisationsFallbackRemovedTest {
    private val guard =
        Regex("""@PreAuthorize\("([^"]*)"\)(?:\s*@\w+(?:\([^)]*\))?)*\s*(?:(?:override\s+)?fun\s+(\w+)|class\s+(\w+))""")

    private val allowed =
        mapOf(
            // Delivery admin surface: stays on *:organisations by design (#265 open question 2).
            "deliveryBookingsAdmin" to "delivery admin read",
            // Organisation data (allowance only) that request editors need to raise a request.
            "referringOrganisationMembershipHint" to "organisation data",
        )

    private data class Guard(
        val name: String,
        val expression: String,
    )

    private fun guards(): List<Guard> =
        File("src/main/kotlin")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                guard.findAll(file.readText()).map {
                    Guard(it.groupValues[2].ifEmpty { it.groupValues[3] }, it.groupValues[1])
                }
            }.toList()

    @Test
    fun `the scan finds request and referee guards (guards against a vacuous pass)`() {
        val names = guards().map { it.name }
        assertThat(names).contains("updateDeviceRequest", "synchronizeCollectionDataForDeviceRequest", "notes")
        assertThat(names).contains("DeviceRequestNoteMutations", "ReferringOrganisationContactNoteMutations")
    }

    @Test
    fun `no request or referee guard accepts an organisations scope`() {
        val offenders =
            guards()
                .filter { it.name !in allowed }
                .filter { it.expression.contains(":requests'") || it.expression.contains(":referees'") }
                .filter { it.expression.contains(":organisations'") }
                .map { "${it.name}: ${it.expression}" }
        assertThat(offenders).isEmpty()
    }
}
