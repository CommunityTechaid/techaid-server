package cta.app.graphql.queries

import com.auth0.client.mgmt.filter.PageFilter
import com.auth0.client.mgmt.filter.RolesFilter
import com.auth0.client.mgmt.filter.UserFilter
import com.auth0.json.mgmt.PermissionsPage
import com.auth0.json.mgmt.Role
import com.auth0.json.mgmt.RolesPage
import com.auth0.json.mgmt.users.User
import com.auth0.json.mgmt.users.UsersPage
import cta.auth.Auth0Service
import cta.graphql.PaginationInput
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.validation.annotation.Validated

@Controller
@PreAuthorize("hasAnyAuthority('read:users')")
class UserQueries(
    private val users: Auth0Service
)  {
    @QueryMapping
    fun users(page: PaginationInput, filter: String = ""): UsersPage {
        val userFilter = page.userFilter()
        if (filter.isNotBlank()) userFilter.withQuery(filter)
        return users.findAllUsers(userFilter)
    }

    @QueryMapping
    fun user(id: String): User {
        return users.findById(id)
    }

    @QueryMapping
    fun roles(page: PaginationInput, filter: String = ""): RolesPage {
        val roleFilter = RolesFilter()
            .withPage(page.page, page.size)
            .withTotals(true)
        if (filter.isNotBlank()) roleFilter.withName(filter)
        return users.findRoles(roleFilter)
    }

    @QueryMapping
    fun role(id: String): Role {
        return users.findRoleById(id)
    }
}

@Controller
@Validated
@PreAuthorize("hasAnyAuthority('write:users')")
class UserMutations(
    private val users: Auth0Service
)  {
    @QueryMapping
    fun assignRoles(roleId: String, userIds: List<String>): Role {
        return users.assignRoles(roleId, userIds)
    }

    @QueryMapping
    fun removeRoles(userId: String, roleIds: List<String>): User {
        return users.removeRoles(userId, roleIds)
    }

    @QueryMapping
    fun deleteUser(userId: String): Boolean {
        users.deleteById(userId)
        return true
    }

    @QueryMapping
    fun removePermissions(userId: String, permissions: List<PermissionInput>): User {
        users.mgmt.users().removePermissions(userId, permissions.map { it.permission }).execute()
        return users.findById(userId)
    }
}

data class PermissionInput(
    val name: String,
    val description: String,
    val resourceServerId: String,
    val resourceServerName: String
) {
    val permission by lazy {
        val permission = com.auth0.json.mgmt.Permission()
        permission.name = name
        permission.description = description
        permission.resourceServerId = resourceServerId
        permission.resourceServerName = resourceServerName
        permission
    }
}

@Controller
class RoleResolver(
    private val users: Auth0Service
) {

    fun permissions(role: Role, page: PaginationInput?): PermissionsPage {
        val filter = if (page == null) {
            PageFilter()
        } else {
            PageFilter().withPage(page.page, page.size).withTotals(true)
        }
        return users.mgmt.roles().listPermissions(role.id, filter).execute()
    }


    fun users(role: Role, page: PaginationInput?): UsersPage {
        val filter = if (page == null) {
            PageFilter()
        } else {
            PageFilter().withPage(page.page, page.size).withTotals(true)
        }
        return users.mgmt.roles().listUsers(role.id, filter).execute()
    }
}

@Controller
class UserResolver(
    private val users: Auth0Service
)  {

    fun roles(user: User, page: PaginationInput?): RolesPage {
        val filter = if (page == null) {
            PageFilter()
        } else {
            PageFilter().withPage(page.page, page.size).withTotals(true)
        }
        return users.mgmt.users().listRoles(user.id, filter).execute()
    }

    @QueryMapping
    fun permissions(user: User, page: PaginationInput?): PermissionsPage {
        val filter = if (page == null) {
            PageFilter()
        } else {
            PageFilter().withPage(page.page, page.size).withTotals(true)
        }
        return users.mgmt.users().listPermissions(user.id, filter).execute()
    }
}

fun PaginationInput.userFilter(): UserFilter {
    var filter = UserFilter().withPage(page, size).withTotals(true)
    var sorted = sort?.map { "${it.key}:${it.value}" }?.joinToString(" ") ?: ""
    if (sorted.isNotBlank()) filter.withSort(sorted)
    return filter
}
