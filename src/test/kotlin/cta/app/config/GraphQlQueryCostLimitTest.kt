package cta.app.config

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Bounds on how much work one GraphQL document may ask for, checked before any data fetcher runs.
 *
 * Depth alone (MaxQueryDepthInstrumentation) does not bound cost: an anonymous caller could repeat
 * an expensive public root field under hundreds of aliases, or multiply a selection through
 * fragments, in a single small request. These tests send real documents over HTTP, because the
 * limits live in the GraphQL engine (parser options and instrumentation), not in any resolver.
 *
 * The happy-path tests replay the largest documents the staff dashboard actually sends, exactly
 * as Apollo puts them on the wire (with `__typename` added to every selection set), so a limit
 * tightened below real traffic fails here rather than in the dashboard.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class GraphQlQueryCostLimitTest {
    @MockitoBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var mockMvc: MockMvc

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun graphQl(
        document: String,
        variables: String = "{}",
        customise: (MockHttpServletRequestBuilder) -> MockHttpServletRequestBuilder = { it },
    ): String =
        mockMvc
            .perform(
                customise(
                    post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"query":${quote(document)},"variables":$variables}"""),
                ),
            ).andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

    private fun staff(request: MockHttpServletRequestBuilder) =
        request.with(
            jwt().authorities(
                listOf("app:admin", "read:kits", "read:donors", "read:organisations", "read:donorParents", "read:content")
                    .map { SimpleGrantedAuthority(it) },
            ),
        )

    private fun assertRejectedBeforeExecution(
        content: String,
        expectedMessageFragment: String,
    ) {
        assertTrue(content.contains("\"errors\""), "an over-limit document must be refused: $content")
        assertTrue(content.contains(expectedMessageFragment), "the refusal must say which limit was hit: $content")
        assertFalse(content.contains("\"data\":{"), "no field may have executed: $content")
    }

    @Test
    fun `a public root field repeated under many aliases is refused`() {
        val roots = (1..(GraphQlConfig.MAX_ROOT_FIELDS + 1)).joinToString(" ") { "a$it: referringOrganisationsPublic(where: {}) { id }" }

        assertRejectedBeforeExecution(graphQl("{ $roots }"), "root fields")
    }

    @Test
    fun `a document selecting too many fields in total is refused on complexity`() {
        // Under the root-field and token caps, but 6 roots x 51 fields = 306 > 300.
        val fields = (1..50).joinToString(" ") { "name" }
        val roots = (1..6).joinToString(" ") { "r$it: referringOrganisationsPublic(where: {}) { $fields }" }

        assertRejectedBeforeExecution(graphQl("{ $roots }"), "complexity")
    }

    @Test
    fun `an oversized document is refused by the parser`() {
        val fields = (1..(GraphQlConfig.MAX_QUERY_TOKENS + 1)).joinToString(" ") { "id" }

        assertRejectedBeforeExecution(graphQl("{ referringOrganisationsPublic(where: {}) { $fields } }"), "tokens")
    }

    @Test
    fun `the referral form typeahead still works anonymously`() {
        val content =
            graphQl(
                """
                query findAutocompleteOrgs(${'$'}term: String) {
                  referringOrganisationsPublic(where: { name: { _contains: ${'$'}term }, archived: { _eq: false } }) { id name __typename }
                }
                """.trimIndent(),
                """{"term":"a"}""",
            )

        assertFalse(content.contains("\"errors\""), content)
    }

    // kit-info.component.ts findKit: the most fields of any dashboard document (64 with __typename).
    @Test
    fun `the largest dashboard document still executes`() {
        val content =
            graphQl(
                """
                query findKit(${'$'}id: Long) {
                  kit(where: { id: { _eq: ${'$'}id } }) {
                    id type status model location createdAt updatedAt age archived make deviceVersion
                    serialNo storageCapacity typeOfStorage ramCapacity cpuType cpuCores tpmVersion
                    batteryHealth lotId locationCode
                    donor { id name email phoneNumber __typename }
                    deviceRequest {
                      id clientRef
                      referringOrganisationContact { id referringOrganisation { id name __typename } __typename }
                      __typename
                    }
                    attributes { credentials status notes network state otherType __typename }
                    notes { id content volunteer createdAt updatedAt __typename }
                    subStatus {
                      installationOfOSFailed wipeFailed needsSparePart needsFurtherInvestigation network
                      installedOSName lockedToUser __typename
                    }
                    __typename
                  }
                }
                """.trimIndent(),
                """{"id":1}""",
                ::staff,
            )

        assertFalse(content.contains("\"errors\""), content)
    }

    // dashboard-index.component.ts findAll: the most root fields (5) and aliases (10) of any dashboard document.
    @Test
    fun `the dashboard document with the most roots and aliases still executes`() {
        val content =
            graphQl(
                """
                query findAll {
                  kits: kitsConnection(where: { archived: { _neq: true } }) { totalElements __typename }
                  donors: donorsConnection(where: {}) { totalElements __typename }
                  typeCount { type count __typename }
                  statusCount { status count __typename }
                  requestCount {
                    LAPTOP: laptops TABLET: tablets OTHER: other SMARTPHONE: phones ALLINONE: allInOnes
                    DESKTOP: desktops COMMSDEVICE: commsDevices BROADBANDHUB: broadbandHubs __typename
                  }
                }
                """.trimIndent(),
                customise = ::staff,
            )

        assertFalse(content.contains("\"errors\""), content)
    }
}
