package cta.app.graphql.queries

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.graphql.data.method.annotation.SchemaMapping

/**
 * Regression test for the user/role detail pages showing empty/broken tabs.
 *
 * User.roles, User.permissions, Role.users and Role.permissions are backed by the
 * Auth0 `User`/`Role` POJOs, which have no such property — they require explicit
 * field resolvers (UserResolver / RoleResolver). After the migration off
 * graphql-java-kickstart to Spring for GraphQL those methods lost their wiring: with
 * no @SchemaMapping, graphql-java falls back to the default PropertyDataFetcher, finds
 * no matching property and resolves the field to null (no GraphQL error). The
 * dashboard's detail tabs then hung on the null relationship.
 *
 * This asserts each nested resolver carries @SchemaMapping pointing at the right
 * schema coordinates. It's a pure-reflection test (no Spring context / DB) so it runs
 * without Docker. Before the fix it fails (mapping is null); after it passes.
 */
class UserRoleNestedResolverWiringTest {
    private data class Case(val klass: Class<*>, val method: String, val typeName: String)

    @Test
    fun `nested user and role relationship fields are wired with @SchemaMapping`() {
        val cases =
            listOf(
                Case(UserResolver::class.java, "roles", "User"),
                Case(UserResolver::class.java, "permissions", "User"),
                Case(RoleResolver::class.java, "users", "Role"),
                Case(RoleResolver::class.java, "permissions", "Role"),
            )
        for (c in cases) {
            val method = c.klass.declaredMethods.firstOrNull { it.name == c.method }
            assertThat(method).describedAs("${c.klass.simpleName}.${c.method} method exists").isNotNull

            val mapping = method!!.getAnnotation(SchemaMapping::class.java)
            assertThat(mapping)
                .describedAs("${c.typeName}.${c.method} must be wired with @SchemaMapping (unwired resolvers resolve to null and break the detail tabs)")
                .isNotNull

            assertThat(mapping.typeName).describedAs("${c.typeName}.${c.method} typeName").isEqualTo(c.typeName)
            assertThat(mapping.field).describedAs("${c.typeName}.${c.method} field").isEqualTo(c.method)
        }
    }
}
