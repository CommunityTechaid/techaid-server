package cta.app.graphql.queries

import cta.auth.FakeAuth0Server
import cta.auth.FakeAuth0Server.Companion.ALICE_LAST_LOGIN
import cta.auth.FakeAuth0Server.Companion.MAPPER
import cta.auth.FakeAuth0Server.Companion.MGMT_TOKEN
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.Date

/**
 * Pins the GraphQL contract of the user/role admin screens (users.graphqls) as the dashboard
 * sees it, end to end: dashboard-shaped query -> resolvers -> Auth0Service -> Auth0 SDK -> HTTP.
 *
 * Why it exists (issue #242): the Auth0 SDK's model classes used to BE our GraphQL return
 * types, so the SDK's property names were the dashboard's field names. The 2.x -> 5.x
 * migration renames them (Permission.name -> permissionName, resourceServerId ->
 * resourceServerIdentifier), drops the page metadata (start/total/limit), turns lists into
 * auto-paging iterables and changes lastLogin from Date to OffsetDateTime. Every one of those
 * would surface as a silently null or silently different field — graphql-java resolves an
 * unknown property to null without an error, and schema inspection is disabled — so a
 * controller-method test could not see them. This test asserts the serialised GraphQL JSON.
 *
 * Auth0 is played by [FakeAuth0Server] over real HTTP; no test ever reaches a real tenant.
 *
 * The queries mirror the dashboard's (techaid-dashboard src/app/views/corewidgets/components/
 * user-* and role-*), plus the schema fields the dashboard does not currently select, so the
 * whole declared surface is pinned.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class UserAdminGraphQlContractTest {
    @MockitoBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var mockMvc: MockMvc

    companion object {
        private val auth0 = FakeAuth0Server()

        @JvmStatic
        @DynamicPropertySource
        fun auth0Properties(registry: DynamicPropertyRegistry) {
            registry.add("auth0.domain") { auth0.url }
            registry.add("auth0.client-id") { "test-client-id" }
            registry.add("auth0.client-secret") { "test-client-secret" }
        }

        @JvmStatic
        @AfterAll
        fun stopAuth0() = auth0.stop()

        /**
         * The lastLogin wire format. The 2.x SDK parsed last_login into java.util.Date and
         * graphql-java's String scalar serialises any object with toString(), so the dashboard
         * has always received Date.toString() output (e.g. "Wed Sep 30 09:15:30 BST 2026", in
         * the JVM's default time zone, seconds precision) and renders it with Angular's
         * `date:'medium'` pipe. Computing it the same way keeps the test time-zone independent.
         */
        fun legacyDateString(iso: String): String = Date.from(Instant.parse(iso)).toString()
    }

    @BeforeEach
    fun clearCalls() = auth0.reset()

    private fun graphQl(
        query: String,
        variables: Map<String, Any?> = emptyMap(),
    ): JsonNode {
        val body = MAPPER.writeValueAsString(mapOf("query" to query, "variables" to variables))
        val response =
            mockMvc
                .perform(
                    post("/graphql")
                        .with(
                            jwt().authorities(
                                SimpleGrantedAuthority("read:users"),
                                SimpleGrantedAuthority("write:users"),
                            ),
                        ).contentType(MediaType.APPLICATION_JSON)
                        .content(body),
                ).andReturn()
                .response.contentAsString
        val json = MAPPER.readTree(response)
        assertThat(json.path("errors").isMissingNode)
            .describedAs("GraphQL errors: %s", json.path("errors"))
            .isTrue
        return json.path("data")
    }

    private fun json(text: String): JsonNode = MAPPER.readTree(text)

    private val aliceFull =
        """
        {"userId":"auth0|alice","email":"alice@example.com","emailVerified":true,
         "phoneNumber":"+447700900001","name":"Alice Admin","picture":"https://example.com/alice.png",
         "connection":null,"lastLogin":"${legacyDateString(ALICE_LAST_LOGIN)}","blocked":false,
         "loginsCount":42,"lastIp":"203.0.113.7"}
        """

    private val allUserFields =
        "userId email emailVerified phoneNumber name picture connection lastLogin blocked loginsCount lastIp"

    // ---- users(page, filter): the user index and the role-users typeahead -------------------

    @Test
    fun `users returns one offset page with Auth0's totals and every User field`() {
        val data =
            graphQl(
                """
                query findAllUsers(${'$'}page: PaginationInput!, ${'$'}term: String) {
                  users(page: ${'$'}page, filter: ${'$'}term) {
                    total start length limit
                    items { $allUserFields }
                  }
                }
                """,
                mapOf(
                    "page" to mapOf("page" to 0, "size" to 2, "sort" to listOf(mapOf("key" to "last_login", "value" to "-1"))),
                    "term" to "ali",
                ),
            ).path("users")

        assertThat(data.path("total").asInt()).isEqualTo(3)
        assertThat(data.path("start").asInt()).isEqualTo(0)
        assertThat(data.path("length").asInt()).isEqualTo(2)
        assertThat(data.path("limit").asInt()).isEqualTo(2)
        // Exactly one page: the 5.x SDK's lists auto-page when iterated, which would return all 3.
        assertThat(data.path("items").size()).isEqualTo(2)
        assertThat(data.path("items").get(0)).isEqualTo(json(aliceFull))

        val request = auth0.callsTo("GET", "/api/v2/users").single()
        assertThat(request.query)
            .containsEntry("page", "0")
            .containsEntry("per_page", "2")
            .containsEntry("include_totals", "true")
            .containsEntry("sort", "last_login:-1")
            .containsEntry("q", "ali")
        assertThat(request.authorization).isEqualTo("Bearer $MGMT_TOKEN")
    }

    @Test
    fun `users second page reports its offset`() {
        val data =
            graphQl(
                // filter is passed because omitting it is a pre-existing error (the Kotlin default
                // is not applied: "Parameter specified as non-null is null"); the dashboard always
                // sends its search term. Out of scope for #242.
                """query { users(page: {page: 1, size: 2}, filter: "") { total start items { userId } } }""",
            ).path("users")

        assertThat(data).isEqualTo(json("""{"total":3,"start":2,"items":[{"userId":"google-oauth2|carol"}]}"""))
        assertThat(auth0.callsTo("GET", "/api/v2/users").single().query).doesNotContainKey("q")
    }

    // ---- user(id): user info --------------------------------------------------------------

    @Test
    fun `user maps a never-logged-in user's absent fields to null`() {
        val data =
            graphQl("""query { user(id: "auth0|bob") { $allUserFields } }""").path("user")

        assertThat(data).isEqualTo(
            json(
                """
                {"userId":"auth0|bob","email":"bob@example.com","emailVerified":false,"phoneNumber":null,
                 "name":"Bob Volunteer","picture":"https://example.com/bob.png","connection":null,
                 "lastLogin":null,"blocked":null,"loginsCount":null,"lastIp":null}
                """,
            ),
        )
    }

    @Test
    fun `user lastLogin keeps the legacy Date toString format`() {
        val lastLogin =
            graphQl("""query { user(id: "auth0|alice") { lastLogin } }""").path("user").path("lastLogin").asString()

        assertThat(lastLogin).isEqualTo(legacyDateString(ALICE_LAST_LOGIN))
        // e.g. "Wed Sep 30 09:15:30 BST 2026" — not ISO-8601.
        assertThat(lastLogin).matches("""[A-Z][a-z]{2} [A-Z][a-z]{2} \d{2} \d{2}:\d{2}:\d{2} \S+ \d{4}""")
    }

    // ---- user(id) { permissions roles }: the user permissions and roles tabs -----------------

    @Test
    fun `user permissions and roles tabs resolve their nested pages`() {
        val data =
            graphQl(
                """
                query findPermissions(${'$'}userId: String!, ${'$'}page: PaginationInput) {
                  user(id: ${'$'}userId) {
                    id: userId
                    permissions(page: ${'$'}page) {
                      totalElements: total
                      number: start
                      limit
                      length
                      content: items { resourceServerId resourceServerName name description }
                    }
                    roles {
                      total start
                      content: items { name permissions { total items { name } } }
                    }
                  }
                }
                """,
                mapOf("userId" to "auth0|alice", "page" to mapOf("page" to 0, "size" to 10)),
            ).path("user")

        assertThat(data.path("id").asString()).isEqualTo("auth0|alice")
        assertThat(data.path("permissions")).isEqualTo(
            json(
                """
                {"totalElements":2,"number":0,"limit":10,"length":null,"content":[
                  {"resourceServerId":"https://api.example.org","resourceServerName":"TaDa API",
                   "name":"read:users","description":"Read users"},
                  {"resourceServerId":"https://api.example.org","resourceServerName":"TaDa API",
                   "name":"write:users","description":"Write users"}]}
                """,
            ),
        )
        // No page argument: no paging metadata, just the items.
        assertThat(data.path("roles")).isEqualTo(
            json(
                """
                {"total":null,"start":null,"content":[
                  {"name":"Admin","permissions":{"total":null,"items":[
                    {"name":"read:users"},{"name":"write:users"},{"name":"read:kits"}]}},
                  {"name":"Volunteer","permissions":{"total":null,"items":[{"name":"read:kits"}]}}]}
                """,
            ),
        )
        assertThat(auth0.callsTo("GET", "/api/v2/users/auth0|alice/permissions").single().query)
            .containsEntry("page", "0")
            .containsEntry("per_page", "10")
            .containsEntry("include_totals", "true")
    }

    @Test
    fun `user roles tab pages the user's roles`() {
        val data =
            graphQl(
                """
                query { user(id: "auth0|alice") {
                  roles(page: {page: 0, size: 1}) { totalElements: total number: start limit content: items { id name description } }
                } }
                """,
            ).path("user")

        assertThat(data.path("roles")).isEqualTo(
            json(
                """{"totalElements":2,"number":0,"limit":1,"content":[{"id":"rol_admin","name":"Admin","description":"Full access"}]}""",
            ),
        )
    }

    // ---- roles(page, filter) and role(id): the role index and role info --------------------

    @Test
    fun `roles returns one offset page filtered by name`() {
        val data =
            graphQl(
                """
                query findAllRoles(${'$'}page: PaginationInput!, ${'$'}term: String) {
                  roles(page: ${'$'}page, filter: ${'$'}term) {
                    totalElements: total number: start length limit
                    content: items { id name description }
                  }
                }
                """,
                mapOf("page" to mapOf("page" to 0, "size" to 5), "term" to "vol"),
            ).path("roles")

        assertThat(data).isEqualTo(
            json(
                """
                {"totalElements":1,"number":0,"length":null,"limit":5,
                 "content":[{"id":"rol_volunteer","name":"Volunteer","description":"Kit handling"}]}
                """,
            ),
        )
        assertThat(auth0.callsTo("GET", "/api/v2/roles").single().query)
            .containsEntry("name_filter", "vol")
            .containsEntry("include_totals", "true")
    }

    @Test
    fun `role permissions tab pages the role's permissions`() {
        val data =
            graphQl(
                """
                query findPermissions(${'$'}page: PaginationInput, ${'$'}roleId: String!) {
                  role(id: ${'$'}roleId) {
                    id name description
                    permissions(page: ${'$'}page) {
                      totalElements: total number: start limit
                      content: items { resourceServerId resourceServerName name description }
                    }
                  }
                }
                """,
                mapOf("roleId" to "rol_admin", "page" to mapOf("page" to 1, "size" to 2)),
            ).path("role")

        assertThat(data).isEqualTo(
            json(
                """
                {"id":"rol_admin","name":"Admin","description":"Full access",
                 "permissions":{"totalElements":3,"number":2,"limit":2,"content":[
                   {"resourceServerId":"https://api.example.org","resourceServerName":"TaDa API",
                    "name":"read:kits","description":"Read kits"}]}}
                """,
            ),
        )
    }

    @Test
    fun `role users tab pages the role's members`() {
        val data =
            graphQl(
                """
                query findAllUsers(${'$'}page: PaginationInput, ${'$'}roleId: String!) {
                  role(id: ${'$'}roleId) {
                    id
                    users(page: ${'$'}page) {
                      totalElements: total
                      number: start
                      limit
                      content: items { id: userId name email userId phoneNumber picture lastLogin }
                    }
                  }
                }
                """,
                mapOf("roleId" to "rol_admin", "page" to mapOf("page" to 1, "size" to 2)),
            ).path("role")

        // Auth0 returns only user_id, picture, name and email for role members.
        assertThat(data).isEqualTo(
            json(
                """
                {"id":"rol_admin","users":{"totalElements":3,"number":2,"limit":2,"content":[
                  {"id":"google-oauth2|carol","name":"Carol Coordinator","email":"carol@example.com",
                   "userId":"google-oauth2|carol","phoneNumber":null,
                   "picture":"https://example.com/carol.png","lastLogin":null}]}}
                """,
            ),
        )
    }

    @Test
    fun `role users without a page argument returns members with no paging metadata`() {
        val data =
            graphQl("""query { role(id: "rol_admin") { users { total start items { userId } } } }""")
                .path("role")
                .path("users")

        assertThat(data).isEqualTo(
            json(
                """
                {"total":null,"start":null,"items":[
                  {"userId":"auth0|alice"},{"userId":"auth0|bob"},{"userId":"google-oauth2|carol"}]}
                """,
            ),
        )
    }

    // ---- mutations ---------------------------------------------------------------------------

    @Test
    fun `assignRoles adds the users to the role and returns the role`() {
        val data =
            graphQl(
                """
                mutation assignRoles(${'$'}roleId: String!, ${'$'}userIds: [String!]!) {
                  assignRoles(roleId: ${'$'}roleId, userIds: ${'$'}userIds) { id name }
                }
                """,
                mapOf("roleId" to "rol_admin", "userIds" to listOf("auth0|bob", "google-oauth2|carol")),
            )

        assertThat(data).isEqualTo(json("""{"assignRoles":{"id":"rol_admin","name":"Admin"}}"""))
        val call = auth0.callsTo("POST", "/api/v2/roles/rol_admin/users").single()
        assertThat(call.json.path("users")).isEqualTo(json("""["auth0|bob","google-oauth2|carol"]"""))
        assertThat(call.authorization).isEqualTo("Bearer $MGMT_TOKEN")
    }

    @Test
    fun `removeRoles removes the roles from the user and returns the user`() {
        val data =
            graphQl(
                """
                mutation removeRoles(${'$'}userId: String!, ${'$'}roleIds: [String!]!) {
                  removeRoles(userId: ${'$'}userId, roleIds: ${'$'}roleIds) { id: userId email }
                }
                """,
                mapOf("userId" to "auth0|alice", "roleIds" to listOf("rol_volunteer")),
            )

        assertThat(data).isEqualTo(json("""{"removeRoles":{"id":"auth0|alice","email":"alice@example.com"}}"""))
        val call = auth0.callsTo("DELETE", "/api/v2/users/auth0|alice/roles").single()
        assertThat(call.json.path("roles")).isEqualTo(json("""["rol_volunteer"]"""))
    }

    @Test
    fun `removePermissions removes the permissions from the user and returns the user`() {
        val data =
            graphQl(
                """
                mutation removePermissions(${'$'}userId: String!, ${'$'}permissions: [PermissionInput!]!) {
                  removePermissions(userId: ${'$'}userId, permissions: ${'$'}permissions) { userId }
                }
                """,
                mapOf(
                    "userId" to "auth0|alice",
                    "permissions" to
                        listOf(
                            mapOf(
                                "name" to "write:users",
                                "description" to "Write users",
                                "resourceServerId" to "https://api.example.org",
                                "resourceServerName" to "TaDa API",
                            ),
                        ),
                ),
            )

        assertThat(data).isEqualTo(json("""{"removePermissions":{"userId":"auth0|alice"}}"""))
        val sent =
            auth0
                .callsTo("DELETE", "/api/v2/users/auth0|alice/permissions")
                .single()
                .json
                .path("permissions")
        assertThat(sent.size()).isEqualTo(1)
        assertThat(sent.get(0).path("permission_name").asString()).isEqualTo("write:users")
        assertThat(sent.get(0).path("resource_server_identifier").asString()).isEqualTo("https://api.example.org")
    }

    @Test
    fun `deleteUser deletes the user`() {
        val data = graphQl("""mutation { deleteUser(userId: "auth0|bob") }""")

        assertThat(data).isEqualTo(json("""{"deleteUser":true}"""))
        assertThat(auth0.callsTo("DELETE", "/api/v2/users/auth0|bob")).hasSize(1)
    }
}
