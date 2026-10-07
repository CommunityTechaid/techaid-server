package cta.auth

import com.auth0.client.auth.AuthAPI
import com.auth0.client.mgmt.ManagementApi
import com.auth0.client.mgmt.core.SyncPagingIterable
import com.auth0.client.mgmt.roles.types.AssignRoleUsersRequestContent
import com.auth0.client.mgmt.roles.types.ListRolePermissionsRequestParameters
import com.auth0.client.mgmt.roles.types.ListRoleUsersRequestParameters
import com.auth0.client.mgmt.types.GetRoleResponseContent
import com.auth0.client.mgmt.types.GetUserResponseContent
import com.auth0.client.mgmt.types.ListRolePermissionsOffsetPaginatedResponseContent
import com.auth0.client.mgmt.types.ListRolesOffsetPaginatedResponseContent
import com.auth0.client.mgmt.types.ListRolesRequestParameters
import com.auth0.client.mgmt.types.ListUserPermissionsOffsetPaginatedResponseContent
import com.auth0.client.mgmt.types.ListUserRolesOffsetPaginatedResponseContent
import com.auth0.client.mgmt.types.ListUsersOffsetPaginatedResponseContent
import com.auth0.client.mgmt.types.ListUsersRequestParameters
import com.auth0.client.mgmt.types.PermissionRequestPayload
import com.auth0.client.mgmt.types.PermissionsResponsePayload
import com.auth0.client.mgmt.types.Role
import com.auth0.client.mgmt.types.RoleUser
import com.auth0.client.mgmt.types.UserPermissionSchema
import com.auth0.client.mgmt.types.UserResponseSchema
import com.auth0.client.mgmt.users.types.DeleteUserPermissionsRequestContent
import com.auth0.client.mgmt.users.types.DeleteUserRolesRequestContent
import com.auth0.client.mgmt.users.types.ListUserPermissionsRequestParameters
import com.auth0.client.mgmt.users.types.ListUserRolesRequestParameters
import cta.graphql.PaginationInput
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Date
import java.util.Optional

/**
 * The Auth0 Authentication and Management APIs, behind the GraphQL-facing models in
 * Auth0Models.kt. No Auth0 SDK type leaves this class.
 *
 * SDK 5.x notes (issue #242):
 *  - list calls return an auto-paging [SyncPagingIterable]. Iterating it (including via Kotlin's
 *    `map`/`filter`/`toList` extensions) fetches EVERY page; read `.items` for the one page
 *    requested. The offset metadata (start/limit/total) is on the parsed response body.
 *  - 5.x always sends `include_totals=true` and a page size, even when the caller gave no
 *    page; we null the metadata in that case so the GraphQL output matches 2.x, which sent no
 *    paging parameters and got a bare array back.
 */
@Service
class Auth0Service {
    @Value("\${auth0.domain}")
    private lateinit var domain: String

    @Value("\${auth0.client-id}")
    private lateinit var clientId: String

    @Value("\${auth0.client-secret}")
    private lateinit var clientSecret: String

    private var cachedMgmt: ManagementApi? = null

    private var mgmtApiExpires: Long = 0

    private val auth by lazy {
        AuthAPI.newBuilder(domain, clientId, clientSecret).build()
    }

    private val mgmt: ManagementApi
        get() {
            if (cachedMgmt != null && mgmtApiExpires > Instant.now().epochSecond + 10) {
                return cachedMgmt!!
            }
            val request = auth.requestToken("https://$domain/api/v2/")
            val holder = request.execute().body
            mgmtApiExpires = Instant.now().epochSecond + holder.expiresIn
            cachedMgmt =
                ManagementApi
                    .builder()
                    .url("${baseUrl()}/api/v2")
                    .token(holder.accessToken)
                    // 2.x used 10s connect + 10s read timeouts; 5.x defaults to a 60s call timeout.
                    .timeout(MGMT_CALL_TIMEOUT_SECONDS)
                    .build()
            return cachedMgmt!!
        }

    /** `auth0.domain` is normally a bare host; a scheme is honoured (as AuthAPI does) for tests. */
    private fun baseUrl(): String = if (domain.startsWith("http://") || domain.startsWith("https://")) domain else "https://$domain"

    /** One page of users, optionally narrowed by an Auth0 search query and sorted. */
    fun findAllUsers(
        page: PaginationInput,
        query: String = "",
    ): Auth0Page<Auth0User> {
        val request =
            ListUsersRequestParameters
                .builder()
                .page(page.page)
                .perPage(page.size)
                .includeTotals(true)
        val sorted = page.sort?.joinToString(" ") { "${it.key}:${it.value}" } ?: ""
        if (sorted.isNotBlank()) request.sort(sorted)
        if (query.isNotBlank()) request.q(query)
        val result = mgmt.users().list(request.build())
        val body = result.response<ListUsersOffsetPaginatedResponseContent>()
        return Auth0Page(
            items = result.items.map { it.toAuth0User() },
            start = body?.start.toInt(),
            length = body?.length.toInt(),
            total = body?.total.toInt(),
            limit = body?.limit.toInt(),
        )
    }

    fun resetPassword(email: String) {
        auth.resetPassword(email, "Username-Password-Authentication").execute()
    }

    fun deleteById(id: String) {
        mgmt.users().delete(id)
    }

    fun findById(id: String): Auth0User = mgmt.users().get(id).toAuth0User()

    fun signUp(
        email: String,
        username: String,
        password: String,
        fields: Map<String, String> = mapOf(),
    ) {
        auth
            .signUp(email, username, password.toCharArray(), "Username-Password-Authentication")
            .setCustomFields(fields)
            .execute()
    }

    /** One page of roles, optionally narrowed to names containing [nameFilter]. */
    fun findRoles(
        page: PaginationInput,
        nameFilter: String = "",
    ): Auth0Page<Auth0Role> {
        val request =
            ListRolesRequestParameters
                .builder()
                .page(page.page)
                .perPage(page.size)
                .includeTotals(true)
        if (nameFilter.isNotBlank()) request.nameFilter(nameFilter)
        val result = mgmt.roles().list(request.build())
        val body = result.response<ListRolesOffsetPaginatedResponseContent>()
        return Auth0Page(
            items = result.items.map { it.toAuth0Role() },
            start = body?.start?.toInt(),
            total = body?.total?.toInt(),
            limit = body?.limit?.toInt(),
        )
    }

    fun findRoleById(roleId: String): Auth0Role = mgmt.roles().get(roleId).toAuth0Role()

    fun findRolePermissions(
        roleId: String,
        page: PaginationInput?,
    ): Auth0Page<Auth0Permission> {
        val request = ListRolePermissionsRequestParameters.builder()
        if (page != null) request.page(page.page).perPage(page.size).includeTotals(true)
        val result = mgmt.roles().permissions().list(roleId, request.build())
        val body = result.response<ListRolePermissionsOffsetPaginatedResponseContent>()
        return offsetPage(
            page,
            result.items.map { it.toAuth0Permission() },
            body?.start,
            body?.total,
            body?.limit,
        )
    }

    /**
     * 5.x only offers checkpoint pagination (`from`/`take`, no totals) for a role's members, so
     * a requested page is cut from the full member list here, reproducing the offset page —
     * start, limit, total — that 2.x got from Auth0. Role memberships in this tenant are small.
     */
    fun findRoleUsers(
        roleId: String,
        page: PaginationInput?,
    ): Auth0Page<Auth0User> {
        val result = mgmt.roles().users().list(roleId, ListRoleUsersRequestParameters.builder().build())
        if (page == null) {
            return Auth0Page(items = result.items.map { it.toAuth0User() })
        }
        // Deliberately iterates the auto-paging iterable: it follows `next` to the last page.
        val members = result.toList()
        val start = page.page * page.size
        return Auth0Page(
            items = members.drop(start).take(page.size).map { it.toAuth0User() },
            start = start,
            total = members.size,
            limit = page.size,
        )
    }

    fun findUserRoles(
        userId: String,
        page: PaginationInput?,
    ): Auth0Page<Auth0Role> {
        val request = ListUserRolesRequestParameters.builder()
        if (page != null) request.page(page.page).perPage(page.size).includeTotals(true)
        val result = mgmt.users().roles().list(userId, request.build())
        val body = result.response<ListUserRolesOffsetPaginatedResponseContent>()
        return offsetPage(page, result.items.map { it.toAuth0Role() }, body?.start, body?.total, body?.limit)
    }

    fun findUserPermissions(
        userId: String,
        page: PaginationInput?,
    ): Auth0Page<Auth0Permission> {
        val request = ListUserPermissionsRequestParameters.builder()
        if (page != null) request.page(page.page).perPage(page.size).includeTotals(true)
        val result = mgmt.users().permissions().list(userId, request.build())
        val body = result.response<ListUserPermissionsOffsetPaginatedResponseContent>()
        return offsetPage(
            page,
            result.items.map { it.toAuth0Permission() },
            body?.start,
            body?.total,
            body?.limit,
        )
    }

    fun assignRoles(
        roleId: String,
        userIds: List<String>,
    ): Auth0Role {
        mgmt.roles().users().assign(roleId, AssignRoleUsersRequestContent.builder().users(userIds).build())
        return findRoleById(roleId)
    }

    fun removeRoles(
        userId: String,
        roleIds: List<String>,
    ): Auth0User {
        mgmt.users().roles().delete(userId, DeleteUserRolesRequestContent.builder().roles(roleIds).build())
        return findById(userId)
    }

    fun removePermissions(
        userId: String,
        permissions: List<Auth0Permission>,
    ): Auth0User {
        // Auth0 identifies a permission by resource server + name; the other input fields are display-only.
        val payload =
            permissions.map {
                PermissionRequestPayload
                    .builder()
                    .resourceServerIdentifier(it.resourceServerId!!)
                    .permissionName(it.name!!)
                    .build()
            }
        mgmt.users().permissions().delete(userId, DeleteUserPermissionsRequestContent.builder().permissions(payload).build())
        return findById(userId)
    }

    private companion object {
        const val MGMT_CALL_TIMEOUT_SECONDS = 20
    }
}

/** The parsed response body behind one page of an auto-paging list. */
private fun <R> SyncPagingIterable<*>.response(): R? = getResponse<R>().orElse(null)

private fun Optional<Double>?.toInt(): Int? = this?.orElse(null)?.toInt()

/** Paging metadata only when a page was requested — see the class comment. */
private fun <T> offsetPage(
    page: PaginationInput?,
    items: List<T>,
    start: Optional<Double>?,
    total: Optional<Double>?,
    limit: Optional<Double>?,
): Auth0Page<T> =
    if (page == null) {
        Auth0Page(items)
    } else {
        Auth0Page(items, start = start.toInt(), total = total.toInt(), limit = limit.toInt())
    }

/**
 * The 2.x SDK held last_login as a java.util.Date and graphql-java serialised it with
 * toString(); the dashboard has always received that format. See [Auth0User.lastLogin].
 */
private fun Optional<OffsetDateTime>.toLegacyDateString(): String? = map { Date.from(it.toInstant()).toString() }.orElse(null)

// Auth0 only accepts `connection` when creating a user; reads never return it, so it maps to null.

private fun UserResponseSchema.toAuth0User() =
    Auth0User(
        userId = userId.orElse(null),
        email = email.orElse(null),
        emailVerified = emailVerified.orElse(null),
        phoneNumber = phoneNumber.orElse(null),
        name = name.orElse(null),
        picture = picture.orElse(null),
        connection = null,
        lastLogin = lastLogin.toLegacyDateString(),
        blocked = blocked.orElse(null),
        loginsCount = loginsCount.orElse(null),
        lastIp = lastIp.orElse(null),
    )

private fun GetUserResponseContent.toAuth0User() =
    Auth0User(
        userId = userId.orElse(null),
        email = email.orElse(null),
        emailVerified = emailVerified.orElse(null),
        phoneNumber = phoneNumber.orElse(null),
        name = name.orElse(null),
        picture = picture.orElse(null),
        connection = null,
        lastLogin = lastLogin.toLegacyDateString(),
        blocked = blocked.orElse(null),
        loginsCount = loginsCount.orElse(null),
        lastIp = lastIp.orElse(null),
    )

/** Auth0 returns only these four fields for a role's members. */
private fun RoleUser.toAuth0User() =
    Auth0User(
        userId = userId.orElse(null),
        email = email.orElse(null),
        emailVerified = null,
        phoneNumber = null,
        name = name.orElse(null),
        picture = picture.orElse(null),
        connection = null,
        lastLogin = null,
        blocked = null,
        loginsCount = null,
        lastIp = null,
    )

private fun Role.toAuth0Role() = Auth0Role(id = id.orElse(null), name = name.orElse(null), description = description.orElse(null))

private fun GetRoleResponseContent.toAuth0Role() =
    Auth0Role(id = id.orElse(null), name = name.orElse(null), description = description.orElse(null))

private fun PermissionsResponsePayload.toAuth0Permission() =
    Auth0Permission(
        resourceServerId = resourceServerIdentifier.orElse(null),
        resourceServerName = resourceServerName.orElse(null),
        name = permissionName.orElse(null),
        description = description.orElse(null),
    )

private fun UserPermissionSchema.toAuth0Permission() =
    Auth0Permission(
        resourceServerId = resourceServerIdentifier.orElse(null),
        resourceServerName = resourceServerName.orElse(null),
        name = permissionName.orElse(null),
        description = description.orElse(null),
    )
