package cta.auth

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Collections

/**
 * An in-process stand-in for the Auth0 Authentication and Management APIs, so tests can drive
 * Auth0Service over real HTTP without ever calling a real tenant.
 *
 * It is deliberately HTTP-level rather than a Mockito stub of the SDK: the Auth0 SDK moved from
 * a hand-written 2.x to a generated 5.x with entirely different classes (issue #242), and the
 * tests that pin the GraphQL contract had to keep passing, unchanged, across that swap. Only
 * the wire protocol is common to both SDKs, so that is the seam.
 *
 * It mimics the Auth0 behaviours our code depends on:
 *  - offset pagination (`page`, `per_page`, `include_totals`): with totals the body is an object
 *    carrying `start`/`limit`/`total` (plus `length` on GET /users); without, a bare JSON array;
 *  - checkpoint pagination (`from`, `take`) on GET /roles/{id}/users, returning `next` while
 *    more users remain;
 *  - role-users items carry only `user_id`, `picture`, `name`, `email`, as Auth0's do.
 *
 * Every request is recorded in [calls] so tests can assert what was sent.
 */
class FakeAuth0Server {
    data class Call(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val body: String,
        val authorization: String?,
    ) {
        val json: JsonNode get() = MAPPER.readTree(body.ifBlank { "{}" })
    }

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    val calls: MutableList<Call> = Collections.synchronizedList(mutableListOf())

    val url: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { exchange ->
            try {
                handle(exchange)
            } catch (e: Exception) {
                respond(exchange, 500, """{"error":"${e.javaClass.simpleName}: ${e.message}"}""")
            }
        }
        server.start()
    }

    fun stop() = server.stop(0)

    fun reset() = calls.clear()

    fun callsTo(
        method: String,
        path: String,
    ): List<Call> = calls.filter { it.method == method && it.path == path }

    private fun handle(exchange: HttpExchange) {
        val method = exchange.requestMethod
        val path = exchange.requestURI.path
        val query = parseQuery(exchange.requestURI.rawQuery)
        val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
        calls += Call(method, path, query, body, exchange.requestHeaders.getFirst("Authorization"))

        val segments = path.trim('/').split('/')
        when {
            method == "POST" && path == "/oauth/token" -> {
                respond(exchange, 200, """{"access_token":"$MGMT_TOKEN","expires_in":86400,"token_type":"Bearer"}""")
            }

            method == "POST" && path == "/dbconnections/change_password" -> {
                respond(exchange, 200, "\"We've just sent you an email to reset your password.\"")
            }

            method == "POST" && path == "/dbconnections/signup" -> {
                respond(exchange, 200, """{"_id":"new-user","email":"new@example.com","email_verified":false}""")
            }

            path == "/api/v2/users" && method == "GET" -> {
                offsetPage(exchange, query, "users", USERS, includeLength = true)
            }

            path == "/api/v2/roles" && method == "GET" -> {
                val nameFilter = query["name_filter"]
                val roles = ROLES.filter { nameFilter == null || it.name.contains(nameFilter, ignoreCase = true) }
                offsetPage(exchange, query, "roles", roles.map { it.json })
            }

            segments.size == 4 && segments[2] == "users" && method == "GET" -> {
                val user = USERS_BY_ID[segments[3]]
                if (user == null) respond(exchange, 404, NOT_FOUND) else respond(exchange, 200, user)
            }

            segments.size == 4 && segments[2] == "users" && method == "DELETE" -> {
                respond(exchange, 204, null)
            }

            segments.size == 4 && segments[2] == "roles" && method == "GET" -> {
                val role = ROLES.firstOrNull { it.id == segments[3] }
                if (role == null) respond(exchange, 404, NOT_FOUND) else respond(exchange, 200, role.json)
            }

            segments.size == 5 && segments[2] == "roles" && segments[4] == "permissions" && method == "GET" -> {
                offsetPage(exchange, query, "permissions", ROLE_PERMISSIONS[segments[3]].orEmpty())
            }

            segments.size == 5 && segments[2] == "roles" && segments[4] == "users" && method == "GET" -> {
                roleUsers(exchange, query, ROLE_USERS[segments[3]].orEmpty())
            }

            segments.size == 5 && segments[2] == "roles" && segments[4] == "users" && method == "POST" -> {
                respond(exchange, 200, null)
            }

            segments.size == 5 && segments[2] == "users" && segments[4] == "roles" && method == "GET" -> {
                offsetPage(exchange, query, "roles", USER_ROLES[segments[3]].orEmpty())
            }

            segments.size == 5 && segments[2] == "users" && segments[4] == "permissions" && method == "GET" -> {
                offsetPage(exchange, query, "permissions", USER_PERMISSIONS[segments[3]].orEmpty())
            }

            segments.size == 5 && segments[2] == "users" && segments[4] in setOf("roles", "permissions") &&
                method == "DELETE" -> {
                respond(exchange, 204, null)
            }

            else -> {
                respond(exchange, 404, NOT_FOUND)
            }
        }
    }

    private fun offsetPage(
        exchange: HttpExchange,
        query: Map<String, String>,
        key: String,
        items: List<String>,
        includeLength: Boolean = false,
    ) {
        val page = query["page"]?.toInt() ?: 0
        val perPage = query["per_page"]?.toInt() ?: 50
        val slice = items.drop(page * perPage).take(perPage)
        val array = slice.joinToString(",", "[", "]")
        if (query["include_totals"] != "true") {
            respond(exchange, 200, array)
            return
        }
        val length = if (includeLength) ""","length":${slice.size}""" else ""
        respond(exchange, 200, """{"start":${page * perPage},"limit":$perPage$length,"total":${items.size},"$key":$array}""")
    }

    private fun roleUsers(
        exchange: HttpExchange,
        query: Map<String, String>,
        users: List<String>,
    ) {
        if (query.containsKey("take") || query.containsKey("from")) {
            val from = query["from"]?.toInt() ?: 0
            val take = query["take"]?.toInt() ?: 50
            val slice = users.drop(from).take(take)
            val next = if (from + take < users.size) ""","next":"${from + take}"""" else ""
            respond(exchange, 200, """{"users":${slice.joinToString(",", "[", "]")}$next}""")
        } else {
            offsetPage(exchange, query, "users", users)
        }
    }

    private fun respond(
        exchange: HttpExchange,
        status: Int,
        body: String?,
    ) {
        if (body == null) {
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
            return
        }
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun parseQuery(raw: String?): Map<String, String> =
        raw
            ?.split('&')
            ?.filter { it.isNotBlank() }
            ?.associate {
                val (k, v) = (it.split('=', limit = 2) + "").take(2)
                URLDecoder.decode(k, StandardCharsets.UTF_8) to URLDecoder.decode(v, StandardCharsets.UTF_8)
            }.orEmpty()

    data class FakeRole(
        val id: String,
        val name: String,
        val description: String,
    ) {
        val json get() = """{"id":"$id","name":"$name","description":"$description"}"""
    }

    companion object {
        val MAPPER: JsonMapper = JsonMapper.builder().build()

        const val MGMT_TOKEN = "fake-management-token"
        private const val NOT_FOUND = """{"statusCode":404,"error":"Not Found","message":"The user does not exist."}"""

        /** A fully populated user, shaped like a GET /api/v2/users item from Auth0. */
        const val ALICE_LAST_LOGIN = "2026-09-30T08:15:30.123Z"
        val ALICE =
            """
            {"user_id":"auth0|alice","email":"alice@example.com","email_verified":true,
             "phone_number":"+447700900001","phone_verified":false,"name":"Alice Admin",
             "nickname":"alice","picture":"https://example.com/alice.png",
             "created_at":"2024-01-02T03:04:05.000Z","updated_at":"2026-09-30T08:15:30.123Z",
             "identities":[{"connection":"Username-Password-Authentication","provider":"auth0",
               "user_id":"alice","isSocial":false}],
             "last_ip":"203.0.113.7","last_login":"$ALICE_LAST_LOGIN","logins_count":42,
             "blocked":false,"app_metadata":{},"user_metadata":{}}
            """.trimIndent()

        /** A user who has never logged in: no last_login, last_ip or logins_count. */
        val BOB =
            """
            {"user_id":"auth0|bob","email":"bob@example.com","email_verified":false,
             "name":"Bob Volunteer","picture":"https://example.com/bob.png",
             "created_at":"2025-05-06T07:08:09.000Z","updated_at":"2025-05-06T07:08:09.000Z",
             "identities":[{"connection":"Username-Password-Authentication","provider":"auth0",
               "user_id":"bob","isSocial":false}]}
            """.trimIndent()

        val CAROL =
            """
            {"user_id":"google-oauth2|carol","email":"carol@example.com","email_verified":true,
             "name":"Carol Coordinator","picture":"https://example.com/carol.png",
             "created_at":"2025-06-07T08:09:10.000Z","updated_at":"2026-08-01T12:00:00.000Z",
             "identities":[{"connection":"google-oauth2","provider":"google-oauth2",
               "user_id":"carol","isSocial":true}],
             "last_login":"2026-08-01T12:00:00.000Z","logins_count":3,"blocked":true}
            """.trimIndent()

        val USERS = listOf(ALICE, BOB, CAROL)
        val USERS_BY_ID =
            mapOf("auth0|alice" to ALICE, "auth0|bob" to BOB, "google-oauth2|carol" to CAROL)

        val ADMIN = FakeRole("rol_admin", "Admin", "Full access")
        val VOLUNTEER = FakeRole("rol_volunteer", "Volunteer", "Kit handling")
        val ROLES = listOf(ADMIN, VOLUNTEER)

        private fun permission(
            name: String,
            description: String,
        ) = """{"resource_server_identifier":"https://api.example.org","permission_name":"$name",""" +
            """"resource_server_name":"TaDa API","description":"$description"}"""

        private fun userPermission(
            name: String,
            description: String,
        ) = """{"resource_server_identifier":"https://api.example.org","permission_name":"$name",""" +
            """"resource_server_name":"TaDa API","description":"$description",""" +
            """"sources":[{"source_id":"","source_name":"","source_type":"DIRECT"}]}"""

        val ROLE_PERMISSIONS =
            mapOf(
                ADMIN.id to
                    listOf(
                        permission("read:users", "Read users"),
                        permission("write:users", "Write users"),
                        permission("read:kits", "Read kits"),
                    ),
                VOLUNTEER.id to listOf(permission("read:kits", "Read kits")),
            )

        val USER_PERMISSIONS =
            mapOf(
                "auth0|alice" to
                    listOf(
                        userPermission("read:users", "Read users"),
                        userPermission("write:users", "Write users"),
                    ),
            )

        private fun roleUser(
            id: String,
            name: String,
            email: String,
        ) = """{"user_id":"$id","picture":"https://example.com/${email.substringBefore('@')}.png",""" +
            """"name":"$name","email":"$email"}"""

        val ROLE_USERS =
            mapOf(
                ADMIN.id to
                    listOf(
                        roleUser("auth0|alice", "Alice Admin", "alice@example.com"),
                        roleUser("auth0|bob", "Bob Volunteer", "bob@example.com"),
                        roleUser("google-oauth2|carol", "Carol Coordinator", "carol@example.com"),
                    ),
            )

        val USER_ROLES = mapOf("auth0|alice" to listOf(ADMIN.json, VOLUNTEER.json))
    }
}
