package cta.auth

/*
 * GraphQL-facing models for the Auth0-backed admin types in users.graphqls.
 *
 * These used to be the Auth0 SDK's own classes, so the SDK's property names were the
 * dashboard's field names and an SDK upgrade could silently rename or null a field (issue
 * #242). Property names here must match users.graphqls exactly: graphql-java resolves an
 * unknown property to null without an error, and schema inspection is disabled.
 * UserAdminGraphQlContractTest pins the serialised output.
 */

/** GraphQL type `User`. */
data class Auth0User(
    val userId: String?,
    val email: String?,
    val emailVerified: Boolean?,
    val phoneNumber: String?,
    val name: String?,
    val picture: String?,
    val connection: String?,
    /**
     * Always `java.util.Date.toString()` of the last login instant (e.g. "Wed Sep 30 09:15:30
     * BST 2026", JVM default time zone). That is what the dashboard has always received —
     * graphql-java serialised the 2.x SDK's Date with toString() — and it renders it with
     * Angular's `date:'medium'` pipe. Not ISO-8601; changing it is a dashboard-visible change.
     */
    val lastLogin: String?,
    val blocked: Boolean?,
    val loginsCount: Int?,
    val lastIp: String?,
)

/** GraphQL type `Role`. */
data class Auth0Role(
    val id: String?,
    val name: String?,
    val description: String?,
)

/** GraphQL type `Permission`. */
data class Auth0Permission(
    val resourceServerId: String?,
    val resourceServerName: String?,
    val name: String?,
    val description: String?,
)

/**
 * GraphQL types `UserPage`, `RolePage` and `PermissionPage`: one page of results with Auth0's
 * offset-pagination metadata. The metadata is null when the caller did not ask for a page.
 */
data class Auth0Page<T>(
    val items: List<T>,
    val start: Int? = null,
    val length: Int? = null,
    val total: Int? = null,
    val limit: Int? = null,
)
