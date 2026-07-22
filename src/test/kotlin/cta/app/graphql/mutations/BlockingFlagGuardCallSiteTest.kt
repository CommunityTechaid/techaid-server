package cta.app.graphql.mutations

import cta.app.DeviceRequest
import cta.app.DeviceRequestItems
import cta.app.DeviceRequestRepository
import cta.app.FeatureFlagRepository
import cta.app.Kit
import cta.app.KitRepository
import cta.app.KitStatus
import cta.app.KitSubStatus
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
 * End-to-end contract for the blocking-flag guard (#90) at all five enforcement points:
 * updateKit, updateKits, autoUpdateKit, the REQUEST_COMPLETED cascade, and
 * assignKitsToDeviceRequest. With enforcement on, a kit carrying a blocking sub-status
 * flag is rejected with a GraphQL error naming the kit id and the offending flag; with
 * the flag off (shadow — the seeded default) the same mutations succeed. Also pins the
 * after-input-apply ordering: clearing the flag and advancing the status in one mutation
 * must still work under enforcement.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class BlockingFlagGuardCallSiteTest {
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
        val flag = featureFlags.findById("blocking-flag-enforcement").orElseThrow()
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

    private fun flaggedKit(status: KitStatus = KitStatus.PROCESSING_START): Kit =
        kits.save(
            Kit(
                type = KitType.LAPTOP,
                status = status,
                model = "Flag Probe",
                age = 0,
                subStatus = KitSubStatus(wipeFailed = true),
            ),
        )

    private fun deviceRequest(): DeviceRequest {
        val org = referringOrganisations.save(ReferringOrganisation(name = "Flag Probe Org"))
        val contact =
            referringOrganisationContacts.save(
                ReferringOrganisationContact(
                    fullName = "Flag Probe",
                    phoneNumber = "0700000000",
                    address = "1 Road",
                    referringOrganisation = org,
                ),
            )
        return deviceRequests.save(
            DeviceRequest(
                deviceRequestItems = DeviceRequestItems(laptops = 1),
                referringOrganisationContact = contact,
                clientRef = "FLAG-REF",
                borough = "Lambeth",
                details = "flag probe",
                deviceRequestNeeds = null,
            ),
        )
    }

    private fun assertBlocked(
        response: String,
        kitId: Long,
    ) {
        assertTrue(response.contains("Kit $kitId"), "error must name the kit id: $response")
        assertTrue(response.contains("wipeFailed"), "error must name the offending flag: $response")
    }

    // --- updateKit ---

    @Test
    fun `updateKit rejects a flagged progression when enforcement is on`() {
        val kit = flaggedKit()
        setEnforcement(true)

        val response =
            graphQl(
                """mutation { updateKit(data: { id: ${kit.id}, type: LAPTOP, status: ALLOCATION_READY, model: \"Flag Probe\", """ +
                    """subStatus: { wipeFailed: true } }) { id } }""",
                "write:kits",
            )

        assertBlocked(response, kit.id)
        assertEquals(KitStatus.PROCESSING_START, kits.findById(kit.id).orElseThrow().status, "rejected update must roll back")
    }

    @Test
    fun `updateKit allows the same flagged progression in shadow mode`() {
        val kit = flaggedKit()

        val response =
            graphQl(
                """mutation { updateKit(data: { id: ${kit.id}, type: LAPTOP, status: ALLOCATION_READY, model: \"Flag Probe\", """ +
                    """subStatus: { wipeFailed: true } }) { id status } }""",
                "write:kits",
            )

        assertFalse(response.contains("errors"), response)
        assertEquals(KitStatus.ALLOCATION_READY, kits.findById(kit.id).orElseThrow().status)
    }

    @Test
    fun `updateKit lets one mutation clear the flag and advance the status under enforcement`() {
        val kit = flaggedKit()
        setEnforcement(true)

        // The guard runs after input apply, so clearing the flag in the same call must work.
        val response =
            graphQl(
                """mutation { updateKit(data: { id: ${kit.id}, type: LAPTOP, status: ALLOCATION_READY, model: \"Flag Probe\", """ +
                    """subStatus: { wipeFailed: false } }) { id } }""",
                "write:kits",
            )

        assertFalse(response.contains("errors"), response)
        assertEquals(KitStatus.ALLOCATION_READY, kits.findById(kit.id).orElseThrow().status)
    }

    // --- updateKits (bulk) ---

    @Test
    fun `updateKits rejects a flagged bulk progression when enforcement is on`() {
        val kit = flaggedKit()
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
    fun `autoUpdateKit rejects a flagged progression when enforcement is on`() {
        val kit = flaggedKit()
        setEnforcement(true)

        val response =
            graphQl(
                """mutation { autoUpdateKit(data: { id: ${kit.id}, status: ALLOCATION_QC_COMPLETED, """ +
                    """subStatus: { wipeFailed: true } }) { id } }""",
                "write:kits",
            )

        assertBlocked(response, kit.id)
    }

    // --- assignKitsToDeviceRequest ---

    @Test
    fun `assignKitsToDeviceRequest rejects a flagged kit when enforcement is on`() {
        val kit = flaggedKit()
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
    fun `assignKitsToDeviceRequest allows a flagged kit in shadow mode`() {
        val kit = flaggedKit()
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

    // --- REQUEST_COMPLETED cascade ---

    private fun completeRequestMutation(requestId: Long): String =
        """mutation { updateDeviceRequest(data: { id: $requestId, status: REQUEST_COMPLETED, """ +
            """deviceRequestItems: { laptops: 1 }, clientRef: \"FLAG-REF\", details: \"flag probe\" }) { id status } }"""

    @Test
    fun `REQUEST_COMPLETED cascade rejects a flagged kit when enforcement is on`() {
        val request = deviceRequest()
        val active = flaggedKit(status = KitStatus.ALLOCATION_READY)
        active.deviceRequest = request
        kits.save(active)
        setEnforcement(true)

        val response = graphQl(completeRequestMutation(request.id), "write:organisations")

        assertBlocked(response, active.id)
        val rolledBack = kits.findById(active.id).orElseThrow()
        assertEquals(KitStatus.ALLOCATION_READY, rolledBack.status, "rejected cascade must roll back")
        assertFalse(rolledBack.archived)
    }

    @Test
    fun `REQUEST_COMPLETED cascade allows a flagged kit in shadow mode`() {
        val request = deviceRequest()
        val active = flaggedKit(status = KitStatus.ALLOCATION_READY)
        active.deviceRequest = request
        kits.save(active)

        val response = graphQl(completeRequestMutation(request.id), "write:organisations")

        assertFalse(response.contains("errors"), response)
        val cascaded = kits.findById(active.id).orElseThrow()
        assertEquals(KitStatus.DISTRIBUTION_DELIVERED, cascaded.status)
        assertTrue(cascaded.archived, "cascaded kit must be archived")
    }
}
