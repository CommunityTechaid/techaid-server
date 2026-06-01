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
    @Test
    fun `nested user and role relationship fields are wired with @SchemaMapping`() {
        val cases =
            listOf(
                Triple(UserResolver::class.java, "roles", "User"),
                Triple(UserResolver::class.java, "permissions", "User"),
                Triple(RoleResolver::class.java, "users", "Role"),
                Triple(RoleResolver::class.java, "permissions", "Role"),
            )
        for ((klass, field, typeName) in cases) {
            val method = klass.declaredMethods.firstOrNull { it.name == field }
            assertThat(method)
                .describedAs("$typeName.$field resolver method exists")
                .isNotNull

            val mapping = method!!.getAnnotation(SchemaMapping::class.java)
            assertThat(mapping)
                .describedAs("$typeName.$field must be wired with @SchemaMapping")
                .isNotNull
            assertThat(mapping.typeName).isEqualTo(typeName)
            assertThat(mapping.field).isEqualTo(field)
        }
    }
}
