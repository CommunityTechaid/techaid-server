package cta.auth

import com.auth0.client.auth.AuthAPI
import com.auth0.client.mgmt.ManagementAPI
import com.auth0.client.mgmt.filter.PageFilter
import com.auth0.client.mgmt.filter.RolesFilter
import com.auth0.client.mgmt.filter.UserFilter
import com.auth0.json.mgmt.Page
import com.auth0.json.mgmt.permissions.Permission
import com.auth0.json.mgmt.roles.Role
import com.auth0.json.mgmt.users.User
import cta.graphql.PaginationInput
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class Auth0Service {
    @Value("\${auth0.domain}")
    private lateinit var domain: String

    @Value("\${auth0.client-id}")
    private lateinit var clientId: String

    @Value("\${auth0.client-secret}")
    private lateinit var clientSecret: String

    private var cachedMgmt: ManagementAPI? = null

    private var mgmtApiExpires: Long = 0

    private val auth by lazy {
        AuthAPI(domain, clientId, clientSecret)
    }

    private val mgmt: ManagementAPI
        get() {
            if (cachedMgmt != null && mgmtApiExpires > Instant.now().epochSecond + 10) {
                return cachedMgmt!!
            }
            val request = auth.requestToken("https://$domain/api/v2/")
            val holder = request.execute().body
            mgmtApiExpires = Instant.now().epochSecond + holder.expiresIn
            cachedMgmt = ManagementAPI(domain, holder.accessToken)
            return cachedMgmt!!
        }

    /** One page of users, optionally narrowed by an Auth0 search query and sorted. */
    fun findAllUsers(
        page: PaginationInput,
        query: String = "",
    ): Auth0Page<Auth0User> {
        val filter = UserFilter().withPage(page.page, page.size).withTotals(true)
        val sorted = page.sort?.joinToString(" ") { "${it.key}:${it.value}" } ?: ""
        if (sorted.isNotBlank()) filter.withSort(sorted)
        if (query.isNotBlank()) filter.withQuery(query)
        return mgmt
            .users()
            .list(filter)
            .execute()
            .body
            .toAuth0Page { it.toAuth0User() }
    }

    fun resetPassword(email: String) {
        auth.resetPassword(email, "Username-Password-Authentication").execute()
    }

    fun deleteById(id: String) {
        mgmt.users().delete(id).execute()
    }

    fun findById(id: String): Auth0User =
        mgmt
            .users()
            .get(id, UserFilter())
            .execute()
            .body
            .toAuth0User()

    fun signUp(
        email: String,
        username: String,
        password: String,
        fields: Map<String, String> = mapOf(),
    ) {
        auth
            .signUp(email, username, password, "Username-Password-Authentication")
            .setCustomFields(fields)
            .execute()
    }

    /** One page of roles, optionally narrowed to names containing [nameFilter]. */
    fun findRoles(
        page: PaginationInput,
        nameFilter: String = "",
    ): Auth0Page<Auth0Role> {
        val filter = RolesFilter().withPage(page.page, page.size).withTotals(true)
        if (nameFilter.isNotBlank()) filter.withName(nameFilter)
        return mgmt
            .roles()
            .list(filter)
            .execute()
            .body
            .toAuth0Page { it.toAuth0Role() }
    }

    fun findRoleById(roleId: String): Auth0Role =
        mgmt
            .roles()
            .get(roleId)
            .execute()
            .body
            .toAuth0Role()

    fun findRolePermissions(
        roleId: String,
        page: PaginationInput?,
    ): Auth0Page<Auth0Permission> =
        mgmt
            .roles()
            .listPermissions(roleId, page.pageFilter())
            .execute()
            .body
            .toAuth0Page { it.toAuth0Permission() }

    fun findRoleUsers(
        roleId: String,
        page: PaginationInput?,
    ): Auth0Page<Auth0User> =
        mgmt
            .roles()
            .listUsers(roleId, page.pageFilter())
            .execute()
            .body
            .toAuth0Page { it.toAuth0User() }

    fun findUserRoles(
        userId: String,
        page: PaginationInput?,
    ): Auth0Page<Auth0Role> =
        mgmt
            .users()
            .listRoles(userId, page.pageFilter())
            .execute()
            .body
            .toAuth0Page { it.toAuth0Role() }

    fun findUserPermissions(
        userId: String,
        page: PaginationInput?,
    ): Auth0Page<Auth0Permission> =
        mgmt
            .users()
            .listPermissions(userId, page.pageFilter())
            .execute()
            .body
            .toAuth0Page { it.toAuth0Permission() }

    fun assignRoles(
        roleId: String,
        userIds: List<String>,
    ): Auth0Role {
        mgmt.roles().assignUsers(roleId, userIds).execute()
        return findRoleById(roleId)
    }

    fun removeRoles(
        userId: String,
        roleIds: List<String>,
    ): Auth0User {
        mgmt.users().removeRoles(userId, roleIds).execute()
        return findById(userId)
    }

    fun removePermissions(
        userId: String,
        permissions: List<Auth0Permission>,
    ): Auth0User {
        val sdkPermissions =
            permissions.map {
                Permission().apply {
                    name = it.name
                    description = it.description
                    resourceServerId = it.resourceServerId
                    resourceServerName = it.resourceServerName
                }
            }
        mgmt.users().removePermissions(userId, sdkPermissions).execute()
        return findById(userId)
    }
}

/** No page requested: no paging parameters, so Auth0 returns its default first page without totals. */
private fun PaginationInput?.pageFilter(): PageFilter =
    if (this == null) PageFilter() else PageFilter().withPage(page, size).withTotals(true)

private fun <S, T> Page<S>.toAuth0Page(map: (S) -> T): Auth0Page<T> =
    Auth0Page(
        items = items.orEmpty().map(map),
        start = start,
        length = length,
        total = total,
        limit = limit,
    )

private fun User.toAuth0User() =
    Auth0User(
        userId = id,
        email = email,
        emailVerified = isEmailVerified,
        phoneNumber = phoneNumber,
        name = name,
        picture = picture,
        // Auth0 only accepts `connection` when creating a user; reads never return it.
        connection = null,
        lastLogin = lastLogin?.toString(),
        blocked = isBlocked,
        loginsCount = loginsCount,
        lastIp = lastIP,
    )

private fun Role.toAuth0Role() = Auth0Role(id = id, name = name, description = description)

private fun Permission.toAuth0Permission() =
    Auth0Permission(
        resourceServerId = resourceServerId,
        resourceServerName = resourceServerName,
        name = name,
        description = description,
    )
