package cta.app.graphql.mutations

import cta.app.DeviceRequest
import cta.app.DeviceRequestItems
import cta.app.DeviceRequestRepository
import cta.app.FeatureFlagRepository
import cta.app.Kit
import cta.app.KitRepository
import cta.app.KitStatus
import cta.app.KitType
import cta.app.ReferringOrganisation
import cta.app.ReferringOrganisationContact
import cta.app.ReferringOrganisationContactRepository
import cta.app.ReferringOrganisationRepository
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

/**
 * End-to-end contract for the wipe-cert guard (#68) at all five enforcement points:
 * updateKit, updateKits, autoUpdateKit, the REQUEST_COMPLETED cascade, and
 * assignKitsToDeviceRequest. With enforcement on, a certless drive-bearing kit is
 * rejected with a GraphQL error naming the kit id and the missing cert (the message the
 * #66/#69 scan UIs inherit); with the flag off (shadow — the seeded default) the same
 * mutations succeed. Also pins the previously untested REQUEST_COMPLETED kit-archival
 * cascade itself (roadmap A5): non-completed kits become DISTRIBUTION_DELIVERED and
 * archived, already-completed kits are left alone.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class WipeCertGuardCallSiteTest {
    @MockBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var kits: KitRepository

    @Autowired
    lateinit var deviceRequests: DeviceRequestRepository

    @Autowired
    lateinit var referringOrganisations: ReferringOrganisationRepository

    @Autowired
    lateinit var referringOrganisationContacts: ReferringOrganisationContactRepository

    @Autowired
    lateinit var featureFlags: FeatureFlagRepository

    @AfterEach
    fun resetFlagToShadow() {
        setEnforcement(false)
    }

    private fun setEnforcement(on: Boolean) {
        val flag = featureFlags.findById("wipe-cert-enforcement").orElseThrow()
        flag.enabled = on
        featureFlags.save(flag)
    }

    private fun graphQl(
        query: String,
        authority: String,
    ): String =
        mockMvc
            .perform(
                post("/graphql")
                    .with(jwt().authorities(SimpleGrantedAuthority(authority)))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"query":"$query"}"""),
            ).andReturn()
            .response
            .contentAsString

    private fun certlessLaptop(status: KitStatus = KitStatus.PROCESSING_WIPED): Kit =
        kits.save(Kit(type = KitType.LAPTOP, status = status, model = "Guard Probe", age = 0))

    private fun deviceRequest(): DeviceRequest {
        val org = referringOrganisations.save(ReferringOrganisation(name = "Guard Probe Org"))
        val contact =
            referringOrganisationContacts.save(
                ReferringOrganisationContact(
                    fullName = "Guard Probe",
                    phoneNumber = "0700000000",
                    address = "1 Road",
                    referringOrganisation = org,
                ),
            )
        return deviceRequests.save(
            DeviceRequest(
                deviceRequestItems = DeviceRequestItems(laptops = 1),
                referringOrganisationContact = contact,
                clientRef = "GUARD-REF",
                borough = "Lambeth",
                details = "guard probe",
                deviceRequestNeeds = null,
            ),
        )
    }

    private fun assertBlocked(
        response: String,
        kitId: Long,
    ) {
        assertTrue(response.contains("Kit $kitId"), "error must name the kit id: $response")
        assertTrue(response.contains("wipe certificate"), "error must name the missing cert: $response")
    }

    // --- updateKit ---

    @Test
    fun `updateKit rejects a certless laptop progression when enforcement is on`() {
        val kit = certlessLaptop()
        setEnforcement(true)

        val response =
            graphQl(
                """mutation { updateKit(data: { id: ${kit.id}, type: LAPTOP, status: ALLOCATION_READY, model: \"Guard Probe\" }) { id } }""",
                "write:kits",
            )

        assertBlocked(response, kit.id)
        assertEquals(KitStatus.PROCESSING_WIPED, kits.findById(kit.id).orElseThrow().status, "rejected update must roll back")
    }

    @Test
    fun `updateKit allows the same certless progression in shadow mode`() {
        val kit = certlessLaptop()

        val response =
            graphQl(
                """mutation { updateKit(data: { id: ${kit.id}, type: LAPTOP, status: ALLOCATION_READY, model: \"Guard Probe\" }) { id status } }""",
                "write:kits",
            )

        assertFalse(response.contains("errors"), response)
        assertEquals(KitStatus.ALLOCATION_READY, kits.findById(kit.id).orElseThrow().status)
    }

    @Test
    fun `updateKit lets a kit carrying a cert reference progress under enforcement`() {
        val kit = certlessLaptop()
        setEnforcement(true)

        // The cert reference arrives in the same mutation — recording it and advancing
        // in one call must work (the wiping-station flow).
        val response =
            graphQl(
                """mutation { updateKit(data: { id: ${kit.id}, type: LAPTOP, status: PROCESSING_OS_INSTALLED, model: \"Guard Probe\", wipeCertReference: \"2026-07-19--${kit.id}--SN.json\" }) { id } }""",
                "write:kits",
            )

        assertFalse(response.contains("errors"), response)
        assertEquals(KitStatus.PROCESSING_OS_INSTALLED, kits.findById(kit.id).orElseThrow().status)
    }

    // --- updateKits (bulk) ---

    @Test
    fun `updateKits rejects certless bulk progression when enforcement is on`() {
        val kit = certlessLaptop()
        setEnforcement(true)

        val response =
            graphQl(
                """mutation { updateKits(data: { ids: [${kit.id}], status: ALLOCATION_READY }) { id } }""",
                "write:kits",
            )

        assertBlocked(response, kit.id)
    }

    // --- autoUpdateKit ---

    @Test
    fun `autoUpdateKit rejects certless progression when enforcement is on`() {
        val kit = certlessLaptop()
        setEnforcement(true)

        val response =
            graphQl(
                """mutation { autoUpdateKit(data: { id: ${kit.id}, status: PROCESSING_OS_INSTALLED }) { id } }""",
                "write:kits",
            )

        assertBlocked(response, kit.id)
    }

    // --- assignKitsToDeviceRequest ---

    @Test
    fun `assignKitsToDeviceRequest rejects a certless laptop when enforcement is on`() {
        val kit = certlessLaptop()
        val request = deviceRequest()
        setEnforcement(true)

        val response =
            graphQl(
                """mutation { assignKitsToDeviceRequest(data: { deviceRequestId: ${request.id}, kitIds: [${kit.id}] }) { id } }""",
                "write:organisations",
            )

        assertBlocked(response, kit.id)
    }

    @Test
    fun `assignKitsToDeviceRequest allows certless assignment in shadow mode`() {
        val kit = certlessLaptop()
        val request = deviceRequest()

        val response =
            graphQl(
                """mutation { assignKitsToDeviceRequest(data: { deviceRequestId: ${request.id}, kitIds: [${kit.id}] }) { id } }""",
                "write:organisations",
            )

        assertFalse(response.contains("errors"), response)
        assertEquals(
            request.id,
            kits
                .findById(kit.id)
                .orElseThrow()
                .deviceRequest
                ?.id,
        )
    }

    // --- REQUEST_COMPLETED cascade (roadmap A5 + enforcement point five) ---

    private fun completeRequestMutation(requestId: Long): String =
        """mutation { updateDeviceRequest(data: { id: $requestId, status: REQUEST_COMPLETED, deviceRequestItems: { laptops: 1 }, clientRef: \"GUARD-REF\", details: \"guard probe\" }) { id status } }"""

    @Test
    fun `REQUEST_COMPLETED cascade delivers and archives non-completed kits and skips completed ones`() {
        val request = deviceRequest()
        val active = certlessLaptop(status = KitStatus.ALLOCATION_READY)
        val recycled = certlessLaptop(status = KitStatus.DISTRIBUTION_RECYCLED)
        active.deviceRequest = request
        recycled.deviceRequest = request
        kits.save(active)
        kits.save(recycled)

        val response = graphQl(completeRequestMutation(request.id), "write:organisations")

        assertFalse(response.contains("errors"), response)
        val cascaded = kits.findById(active.id).orElseThrow()
        assertEquals(KitStatus.DISTRIBUTION_DELIVERED, cascaded.status)
        assertTrue(cascaded.archived, "cascaded kit must be archived")
        val untouched = kits.findById(recycled.id).orElseThrow()
        assertEquals(KitStatus.DISTRIBUTION_RECYCLED, untouched.status, "already-completed kits stay put")
        assertFalse(untouched.archived)
    }

    @Test
    fun `REQUEST_COMPLETED cascade rejects a certless kit when enforcement is on`() {
        val request = deviceRequest()
        val active = certlessLaptop(status = KitStatus.ALLOCATION_READY)
        active.deviceRequest = request
        kits.save(active)
        setEnforcement(true)

        val response = graphQl(completeRequestMutation(request.id), "write:organisations")

        assertBlocked(response, active.id)
        val rolledBack = kits.findById(active.id).orElseThrow()
        assertEquals(KitStatus.ALLOCATION_READY, rolledBack.status, "rejected cascade must roll back")
        assertFalse(rolledBack.archived)
    }
}
