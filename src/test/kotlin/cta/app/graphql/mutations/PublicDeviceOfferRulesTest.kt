package cta.app.graphql.mutations

import cta.app.ReferringOrganisation
import cta.app.ReferringOrganisationContact
import cta.app.ReferringOrganisationContactRepository
import cta.app.ReferringOrganisationRepository
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * The public device offer, enforced by the server for createDeviceRequest.
 *
 * The dashboard's public request form only lets a referrer pick a borough we accept (Lambeth,
 * Southwark, plus Tower Hamlets while `tower-hamlets-borough-support` is on), and only offers the
 * device types allowed by adminConfig's canPublicRequest* switches, narrowed by the borough
 * availability matrix while `borough-availability-rules` is on. It sends one main device at
 * quantity 1, optionally plus a SIM card and/or a broadband hub. Before this test, none of that
 * was checked server-side, so an anonymous caller could post any borough string and any items.
 *
 * Staff (write:organisations or app:admin) see the unrestricted admin device list in the
 * dashboard and are not restricted here either.
 *
 * Flags and the admin_config row are reset around every method so this class cannot leak state
 * into others sharing the cached context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class PublicDeviceOfferRulesTest {
    @MockitoBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var referringOrganisations: ReferringOrganisationRepository

    @Autowired
    lateinit var referringOrganisationContacts: ReferringOrganisationContactRepository

    @BeforeEach
    fun defaults() {
        setFlag("tower-hamlets-borough-support", false)
        setFlag("borough-availability-rules", false)
        setAdminConfig()
    }

    @AfterEach
    fun restore() = defaults()

    private fun setFlag(
        key: String,
        enabled: Boolean,
    ) {
        jdbcTemplate.update("update feature_flags set enabled = ? where flag_key = ?", enabled, key)
    }

    private fun setAdminConfig(
        laptop: Boolean = true,
        desktop: Boolean = true,
        tablet: Boolean = true,
        phone: Boolean = true,
        sim: Boolean = true,
        hub: Boolean = true,
    ) {
        jdbcTemplate.update(
            """
            insert into admin_config (id, can_public_request_laptop, can_public_request_desktop,
                can_public_request_tablet, can_public_request_phone, can_public_requestsimcard,
                can_public_request_broadband_hub, created_at, updated_at)
            values (1, ?, ?, ?, ?, ?, ?, now(), now())
            on conflict (id) do update set
                can_public_request_laptop = excluded.can_public_request_laptop,
                can_public_request_desktop = excluded.can_public_request_desktop,
                can_public_request_tablet = excluded.can_public_request_tablet,
                can_public_request_phone = excluded.can_public_request_phone,
                can_public_requestsimcard = excluded.can_public_requestsimcard,
                can_public_request_broadband_hub = excluded.can_public_request_broadband_hub
            """.trimIndent(),
            laptop,
            desktop,
            tablet,
            phone,
            sim,
            hub,
        )
    }

    // A fresh contact per call so the per-referee open-request cap never interferes.
    private fun newContact(): ReferringOrganisationContact {
        val stamp = System.nanoTime()
        val organisation = referringOrganisations.save(ReferringOrganisation(name = "Org offer $stamp"))
        return referringOrganisationContacts.save(
            ReferringOrganisationContact(
                fullName = "Referee $stamp",
                email = "referee-$stamp@example.com",
                phoneNumber = "0$stamp",
                address = "1 Test Street",
                referringOrganisation = organisation,
            ),
        )
    }

    private fun create(
        borough: String,
        items: String,
        staffAuthority: String? = null,
    ): ResultActions {
        val query =
            """mutation { createDeviceRequest(data: { referringOrganisationContact: \"${newContact().id}\", """ +
                """deviceRequestItems: { $items }, clientRef: \"OFFER\", borough: \"$borough\", """ +
                """details: \"Needs a device\" }) { id } }"""
        val request =
            post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query":"$query"}""")
        if (staffAuthority != null) request.with(jwt().authorities(SimpleGrantedAuthority(staffAuthority)))
        return mockMvc.perform(request).andExpect(status().isOk)
    }

    private fun ResultActions.accepted() =
        andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.createDeviceRequest.id").isNotEmpty)

    private fun ResultActions.rejected() =
        andExpect(jsonPath("$.errors[0].extensions.classification").value("BAD_REQUEST"))
            .andExpect(jsonPath("$.data.createDeviceRequest").doesNotExist())

    @Test
    fun `the dashboard's ordinary public submission is accepted`() {
        create("Lambeth", "laptops: 1").accepted()
        create("Southwark", "phones: 1, commsDevices: 1, broadbandHubs: 1").accepted()
        // The form can also send only a SIM card or only a hub.
        create("Lambeth", "commsDevices: 1").accepted()
        // Borough matched the same case/whitespace-insensitive way the server matches groups.
        create(" lambeth ", "laptops: 1").accepted()
    }

    @Test
    fun `a borough outside the supported list is rejected`() {
        create("Hackney", "laptops: 1").rejected()
        create("", "laptops: 1").rejected()
    }

    @Test
    fun `Tower Hamlets follows the borough support flag`() {
        create("Tower Hamlets", "laptops: 1").rejected()
        setFlag("tower-hamlets-borough-support", true)
        create("Tower Hamlets", "laptops: 1").accepted()
    }

    @Test
    fun `a device type switched off in adminConfig is rejected`() {
        setAdminConfig(laptop = false, sim = false)
        create("Lambeth", "laptops: 1").rejected()
        create("Lambeth", "tablets: 1, commsDevices: 1").rejected()
        create("Lambeth", "tablets: 1").accepted()
    }

    @Test
    fun `types the public form never offers are rejected`() {
        create("Lambeth", "allInOnes: 1").rejected()
        create("Lambeth", "other: 1").rejected()
    }

    @Test
    fun `the borough matrix narrows the offer only while borough-availability-rules is on`() {
        setFlag("tower-hamlets-borough-support", true)
        // Seeded matrix: Tower Hamlets offers laptops only. Flag off means no narrowing.
        create("Tower Hamlets", "phones: 1").accepted()

        setFlag("borough-availability-rules", true)
        create("Tower Hamlets", "phones: 1").rejected()
        create("Tower Hamlets", "laptops: 1, commsDevices: 1").rejected()
        create("Tower Hamlets", "laptops: 1").accepted()
    }

    @Test
    fun `item shapes the public form cannot produce are rejected`() {
        create("Lambeth", "laptops: 2").rejected()
        create("Lambeth", "laptops: 1, tablets: 1").rejected()
        create("Lambeth", "laptops: -1, tablets: 1").rejected()
        create("Lambeth", "laptops: 0").rejected()
    }

    @Test
    fun `staff are not restricted`() {
        setAdminConfig(laptop = false)
        create("Hackney", "laptops: 3, other: 2", staffAuthority = "write:organisations").accepted()
        create("Tower Hamlets", "allInOnes: 1", staffAuthority = "app:admin").accepted()
    }
}
