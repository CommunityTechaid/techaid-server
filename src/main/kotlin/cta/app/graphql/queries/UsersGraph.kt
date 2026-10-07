package cta.app.graphql.queries

import cta.auth.Auth0Page
import cta.auth.Auth0Permission
import cta.auth.Auth0Role
import cta.auth.Auth0Service
import cta.auth.Auth0User
import cta.graphql.PaginationInput
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.validation.annotation.Validated

@Controller
@PreAuthorize("hasAnyAuthority('read:users')")
class UserQueries(
    private val users: Auth0Service,
) {
    @QueryMapping
    fun users(
        @Argument page: PaginationInput,
        @Argument filter: String = "",
    ): Auth0Page<Auth0User> = users.findAllUsers(page, filter)

    @QueryMapping
    fun user(
        @Argument id: String,
    ): Auth0User = users.findById(id)

    @QueryMapping
    fun roles(
        @Argument page: PaginationInput,
        @Argument filter: String = "",
    ): Auth0Page<Auth0Role> = users.findRoles(page, filter)

    @QueryMapping
    fun role(
        @Argument id: String,
    ): Auth0Role = users.findRoleById(id)
}

@Controller
@Validated
@PreAuthorize("hasAnyAuthority('write:users')")
class UserMutations(
    private val users: Auth0Service,
) {
    @MutationMapping
    fun assignRoles(
        @Argument roleId: String,
        @Argument userIds: List<String>,
    ): Auth0Role = users.assignRoles(roleId, userIds)

    @MutationMapping
    fun removeRoles(
        @Argument userId: String,
        @Argument roleIds: List<String>,
    ): Auth0User = users.removeRoles(userId, roleIds)

    @MutationMapping
    fun deleteUser(
        @Argument userId: String,
    ): Boolean {
        users.deleteById(userId)
        return true
    }

    @MutationMapping
    fun removePermissions(
        @Argument userId: String,
        @Argument permissions: List<PermissionInput>,
    ): Auth0User = users.removePermissions(userId, permissions.map { it.permission })
}

data class PermissionInput(
    val name: String,
    val description: String,
    val resourceServerId: String,
    val resourceServerName: String,
) {
    val permission get() = Auth0Permission(resourceServerId, resourceServerName, name, description)
}

@Controller
class RoleResolver(
    private val users: Auth0Service,
) {
    @SchemaMapping(typeName = "Role", field = "permissions")
    fun permissions(
        role: Auth0Role,
        @Argument page: PaginationInput?,
    ): Auth0Page<Auth0Permission> = users.findRolePermissions(role.id!!, page)

    @SchemaMapping(typeName = "Role", field = "users")
    fun users(
        role: Auth0Role,
        @Argument page: PaginationInput?,
    ): Auth0Page<Auth0User> = users.findRoleUsers(role.id!!, page)
}

@Controller
class UserResolver(
    private val users: Auth0Service,
) {
    @SchemaMapping(typeName = "User", field = "roles")
    fun roles(
        user: Auth0User,
        @Argument page: PaginationInput?,
    ): Auth0Page<Auth0Role> = users.findUserRoles(user.userId!!, page)

    @SchemaMapping(typeName = "User", field = "permissions")
    fun permissions(
        user: Auth0User,
        @Argument page: PaginationInput?,
    ): Auth0Page<Auth0Permission> = users.findUserPermissions(user.userId!!, page)
}
