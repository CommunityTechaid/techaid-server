package cta.app.graphql.mutations

import cta.app.ReferringOrganisation
import cta.app.ReferringOrganisationContactRepository
import cta.app.ReferringOrganisationRepository
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
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
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Authorization contract for operations reachable at /graphql. The security model is
 * anyRequest().permitAll() + per-method @PreAuthorize, so an operation without an
 * annotation is anonymously callable — these tests pin down which operations must
 * reject anonymous callers.
 *
 * "Rejected" surfaces as a GraphQL error with message "Access Denied" (Spring
 * Security's AccessDeniedException) and null data. The authorized variants assert the
 * gate lets the right scope through: any remaining error must NOT be an access
 * denial (the test DB has no seed data, so a not-found/SQL error after the gate
 * still proves authorization passed).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class PublicSurfaceAuthorizationTest {
    @MockitoBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jdbc: org.springframework.jdbc.core.JdbcTemplate

    @Autowired
    lateinit var referringOrganisations: ReferringOrganisationRepository

    @Autowired
    lateinit var referringOrganisationContacts: ReferringOrganisationContactRepository

    private fun anonymousGraphQl(query: String): ResultActions =
        mockMvc.perform(
            post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query":"$query"}"""),
        )

    private fun authorizedGraphQl(
        query: String,
        authority: String,
    ): ResultActions =
        mockMvc.perform(
            post("/graphql")
                .with(jwt().authorities(SimpleGrantedAuthority(authority)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query":"$query"}"""),
        )

    private val syncMutation =
        """mutation { synchronizeCollectionDataForDeviceRequest(data: { id: 999999 }) { id } }"""

    private val createOrgMutation =
        """mutation { createReferringOrganisation(data: { name: \"Auth Probe Org\" }) { id name } }"""

    @Test
    fun `synchronizeCollectionDataForDeviceRequest rejects anonymous callers`() {
        anonymousGraphQl(syncMutation)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errors[0].message").value("Access Denied"))
            .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()))
    }

    @Test
    fun `synchronizeCollectionDataForDeviceRequest admits write-organisations scope`() {
        authorizedGraphQl(syncMutation, "write:organisations")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errors[0].message").value(notAccessDenied))
    }

    @Test
    fun `createReferringOrganisation rejects anonymous callers`() {
        anonymousGraphQl(createOrgMutation)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errors[0].message").value("Access Denied"))
            .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()))
    }

    @Test
    fun `createReferringOrganisation admits write-organisations scope`() {
        // Since the Flyway baseline migration the test schema is complete, so the
        // mutation succeeds outright — stronger than the old "any error but Access
        // Denied" assertion (the schema gap used to fail it post-gate on a missing
        // sequence).
        authorizedGraphQl(createOrgMutation, "write:organisations")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.createReferringOrganisation.name").value("Auth Probe Org"))
    }

    // The public referral form no longer creates referees: an unknown email or organisation is
    // sent to a Typeform instead, so creating a contact is a staff action from the referee pages.
    private fun createContactMutation(email: String): String {
        val organisation = referringOrganisations.save(ReferringOrganisation(name = "Contact Probe Org ${System.nanoTime()}"))
        return """mutation { createReferringOrganisationContact(data: { referringOrganisation: \"${organisation.id}\", """ +
            """fullName: \"Contact Probe\", email: \"$email\", phoneNumber: \"07000000000\" }) { id } }"""
    }

    private fun contactsWithEmail(email: String) = referringOrganisationContacts.findAll().count { it.email == email }

    @Test
    fun `createReferringOrganisationContact rejects anonymous callers and writes nothing`() {
        val email = "anon-${System.nanoTime()}@example.com"
        anonymousGraphQl(createContactMutation(email))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errors[0].message").value("Access Denied"))
            .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()))
        assertTrue(contactsWithEmail(email) == 0, "an anonymous call must not create a contact")
    }

    @Test
    fun `createReferringOrganisationContact rejects a caller without a staff scope`() {
        val email = "reader-${System.nanoTime()}@example.com"
        authorizedGraphQl(createContactMutation(email), "read:organisations")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errors[0].message").value("Access Denied"))
        assertTrue(contactsWithEmail(email) == 0, "a read-only caller must not create a contact")
    }

    @Test
    fun `createReferringOrganisationContact admits write-organisations scope`() {
        val email = "staff-${System.nanoTime()}@example.com"
        authorizedGraphQl(createContactMutation(email), "write:organisations")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.createReferringOrganisationContact.id").isNotEmpty)
        assertTrue(contactsWithEmail(email) == 1, "staff creation must write the contact")
    }

    @Test
    fun `createReferringOrganisationContact admits app-admin scope`() {
        authorizedGraphQl(createContactMutation("admin-${System.nanoTime()}@example.com"), "app:admin")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.createReferringOrganisationContact.id").isNotEmpty)
    }

    // referringOrganisationsPublic stays anonymous (public referral form typeahead) but
    // must only accept the restricted filter surface the form actually uses.
    @Test
    fun `referringOrganisationsPublic accepts the public filter shape anonymously`() {
        val content =
            anonymousGraphQl(
                """query { referringOrganisationsPublic(where: { name: { _contains: \"tech\" }, archived: { _eq: false } }) { id name } }""",
            ).andExpect(status().isOk)
                .andReturn()
                .response
                .contentAsString

        assertFalse(content.contains("Access Denied"), content)
        assertFalse(content.contains("Validation error"), content)
    }

    @Test
    fun `referringOrganisationsPublic rejects admin-only filter fields`() {
        val content =
            anonymousGraphQl(
                """query { referringOrganisationsPublic(where: { website: { _contains: \"x\" } }) { id name } }""",
            ).andExpect(status().isOk)
                .andReturn()
                .response
                .contentAsString

        assertTrue(
            content.contains("Validation error"),
            "website must not be filterable on the public query: $content",
        )
    }

    // The public booking page reads availability anonymously — the Flyway seed gives it
    // upcoming Tue/Thu days, so an anonymous caller must get data and no access denial.
    @Test
    fun `deliveryAvailabilityPublic is callable anonymously and returns seeded days`() {
        anonymousGraphQl(
            """query { deliveryAvailabilityPublic { date dayOfWeek windows { spotsRemaining window { id name } } } }""",
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.deliveryAvailabilityPublic").isArray)
    }

    // The eligibility check backs the booking form's new reference step and must be reachable
    // without auth just like deliveryAvailabilityPublic above — it's a deliberately
    // unauthenticated query, and this is the guard that would catch a stray @PreAuthorize.
    @Test
    fun `deliveryBookingEligibilityPublic is callable anonymously`() {
        anonymousGraphQl(
            """query { deliveryBookingEligibilityPublic(ctaReference: 1) { eligible message } }""",
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.errors").doesNotExist())
    }

    // The public booking mutation must be reachable without auth; a well-formed but
    // out-of-policy date is rejected by the booking rules (BAD_REQUEST), never by the auth gate.
    @Test
    fun `submitDeliveryBookingPublic is callable anonymously`() {
        val content =
            anonymousGraphQl(
                """mutation { submitDeliveryBookingPublic(input: { date: \"1970-01-01\", windowId: \"1\", firstName: \"A\", surname: \"B\", email: \"a@b.com\", phone: \"0700000000\", address: \"1 Road\", ctaReference: 1 }) { id } }""",
            ).andExpect(status().isOk)
                .andReturn()
                .response
                .contentAsString

        assertFalse(content.contains("Access Denied"), content)
    }

    // The wipe-cert exemption is a compliance override ("no drive present"), so it is
    // gated tighter than ordinary kit edits: app:admin only, not write:kits (#68).
    private val setExemptionMutation =
        """mutation { setKitWipeCertExemption(data: { id: 999999, exemption: NO_DRIVE }) { id } }"""

    @Test
    fun `setKitWipeCertExemption rejects anonymous callers`() {
        anonymousGraphQl(setExemptionMutation)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errors[0].message").value("Access Denied"))
            .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()))
    }

    @Test
    fun `setKitWipeCertExemption rejects the ordinary kit-edit scope`() {
        authorizedGraphQl(setExemptionMutation, "write:kits")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errors[0].message").value("Access Denied"))
    }

    @Test
    fun `setKitWipeCertExemption admits app-admin scope`() {
        // Kit 999999 doesn't exist in the empty test DB — a not-found error after the
        // gate still proves authorization passed.
        authorizedGraphQl(setExemptionMutation, "app:admin")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errors[0].message").value(notAccessDenied))
    }

    // Membership (#311). The tier decides who is capped, so changing it is staff-only like every
    // other organisation edit; the allowance itself is only ever served through gated queries or
    // the public referee lookup (pinned in MembershipGraphQlTest).
    @Test
    fun `updateReferringOrganisation with a membership tier rejects anonymous and read-only callers`() {
        val organisation = referringOrganisations.save(ReferringOrganisation(name = "Tier Probe Org ${System.nanoTime()}"))
        val mutation =
            """mutation { updateReferringOrganisation(data: { id: \"${organisation.id}\", name: \"Tier Probe\", """ +
                """membershipTier: TIER_3 }) { id } }"""

        anonymousGraphQl(mutation)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errors[0].message").value("Access Denied"))
        authorizedGraphQl(mutation, "read:organisations")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errors[0].message").value("Access Denied"))
        assertTrue(
            referringOrganisations.findById(organisation.id).get().membershipTier == cta.app.MembershipTier.FREE,
            "a refused call must not change the tier",
        )
    }

    @Test
    fun `an organisation's membership cannot be read anonymously`() {
        anonymousGraphQl(
            """query { referringOrganisations(where: {}) { id membership { used } } }""",
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.errors[0].message").value("Access Denied"))
    }

    @Test
    fun `adminConfig stays anonymous with the over-limit settings but updateAdminConfig stays gated`() {
        // Nothing in Flyway seeds the singleton admin_config row, so give the test database one
        // (and take it away again if this test is the one that made it).
        val created =
            jdbc.update(
                "insert into admin_config (id, can_public_request_broadband_hub, can_public_request_desktop, " +
                    "can_public_request_laptop, can_public_request_phone, can_public_request_tablet, can_public_requestsimcard) " +
                    "values (1, true, true, true, true, true, true) on conflict (id) do nothing",
            ) == 1
        try {
            anonymousGraphQl("""query { adminConfig { membershipOverLimitMode membershipGraceRequests } }""")
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.adminConfig.membershipOverLimitMode").isNotEmpty)
        } finally {
            if (created) jdbc.update("delete from admin_config where id = 1")
        }

        anonymousGraphQl(
            """mutation { updateAdminConfig(data: { canPublicRequestSIMCard: true, canPublicRequestLaptop: true, """ +
                """canPublicRequestPhone: true, canPublicRequestBroadbandHub: true, canPublicRequestTablet: true, """ +
                """canPublicRequestDesktop: true, membershipOverLimitMode: BLOCK }) { id } }""",
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.errors[0].message").value("Access Denied"))
    }

    companion object {
        private val notAccessDenied = org.hamcrest.Matchers.not("Access Denied")
    }
}
