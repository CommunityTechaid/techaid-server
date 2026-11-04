package cta.app.graphql.mutations

import cta.app.FeatureFlagRepository
import cta.app.Kit
import cta.app.KitRepository
import cta.app.KitStatus
import cta.app.KitType
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

/**
 * End-to-end contract for the battery-health guard (#264) at the three kit enforcement
 * points: updateKit, updateKits and autoUpdateKit. With enforcement on, a low-battery laptop
 * moving into an allocation status gets an error naming the kit. With the flag off (shadow,
 * which is the seeded default) the same mutation succeeds. The device-request REQUEST_COMPLETED
 * cascade is deliberately not a call site: it only ever targets DISTRIBUTION_DELIVERED, which
 * this guard never blocks.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class BatteryHealthGuardCallSiteTest {
    @MockitoBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var kits: KitRepository

    @Autowired
    lateinit var featureFlags: FeatureFlagRepository

    @AfterEach
    fun resetFlagToShadow() {
        setEnforcement(false)
    }

    private fun setEnforcement(on: Boolean) {
        val flag = featureFlags.findById("battery-health-enforcement").orElseThrow()
        flag.enabled = on
        featureFlags.save(flag)
    }

    private fun graphQl(query: String): String =
        mockMvc
            .perform(
                post("/graphql")
                    .with(jwt().authorities(SimpleGrantedAuthority("write:kits")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"query":"$query"}"""),
            ).andReturn()
            .response
            .contentAsString

    private fun lowBatteryKit(): Kit =
        kits.save(
            Kit(
                type = KitType.LAPTOP,
                status = KitStatus.PROCESSING_START,
                model = "Battery Probe",
                age = 0,
                batteryHealth = 40,
            ),
        )

    private fun updateKitMutation(
        kitId: Long,
        batteryHealth: Int,
    ): String =
        """mutation { updateKit(data: { id: $kitId, type: LAPTOP, status: ALLOCATION_READY, model: \"Battery Probe\", """ +
            """batteryHealth: $batteryHealth }) { id } }"""

    private fun assertBlocked(
        response: String,
        kitId: Long,
    ) {
        assertTrue(response.contains("Kit $kitId"), "error must name the kit id: $response")
        assertTrue(response.contains("Battery health too low"), response)
    }

    @Test
    fun `the flag is seeded off, so the guard ships in shadow mode`() {
        assertFalse(featureFlags.findById("battery-health-enforcement").orElseThrow().enabled)
    }

    @Test
    fun `updateKit rejects a low-battery progression when enforcement is on`() {
        val kit = lowBatteryKit()
        setEnforcement(true)

        val response = graphQl(updateKitMutation(kit.id, 40))

        assertBlocked(response, kit.id)
        assertEquals(KitStatus.PROCESSING_START, kits.findById(kit.id).orElseThrow().status, "rejected update must roll back")
    }

    @Test
    fun `updateKit allows the same progression in shadow mode`() {
        val kit = lowBatteryKit()

        val response = graphQl(updateKitMutation(kit.id, 40))

        assertFalse(response.contains("errors"), response)
        assertEquals(KitStatus.ALLOCATION_READY, kits.findById(kit.id).orElseThrow().status)
    }

    @Test
    fun `updateKit checks the reading sent in the same mutation`() {
        val kit = lowBatteryKit()
        setEnforcement(true)

        // The guard runs after input apply, so a new battery reading counts straight away.
        val response = graphQl(updateKitMutation(kit.id, 85))

        assertFalse(response.contains("errors"), response)
        assertEquals(KitStatus.ALLOCATION_READY, kits.findById(kit.id).orElseThrow().status)
    }

    @Test
    fun `updateKits rejects a low-battery bulk progression when enforcement is on`() {
        val kit = lowBatteryKit()
        setEnforcement(true)

        val response = graphQl("""mutation { updateKits(data: { ids: [${kit.id}], status: ALLOCATION_READY }) { id } }""")

        assertBlocked(response, kit.id)
    }

    @Test
    fun `autoUpdateKit rejects a low-battery progression when enforcement is on`() {
        val kit = lowBatteryKit()
        setEnforcement(true)

        val response = graphQl("""mutation { autoUpdateKit(data: { id: ${kit.id}, status: ALLOCATION_QC_COMPLETED }) { id } }""")

        assertBlocked(response, kit.id)
    }
}
