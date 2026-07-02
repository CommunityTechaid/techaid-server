package cta.app.graphql.queries

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.graphql.data.method.annotation.MutationMapping

/**
 * The user-admin operations (assignRoles, removeRoles, deleteUser, removePermissions)
 * are declared under `extend type Mutation` in users.graphqls, so their handlers must
 * be registered with @MutationMapping. With @QueryMapping the data fetcher is
 * registered under Query instead; the Mutation.* fields then fall back to the default
 * PropertyDataFetcher, silently resolve to null and perform no Auth0 change. Schema
 * inspection is disabled (spring.graphql.schema.inspection.enabled: false), so the
 * mismatch never surfaces at startup — only through a test like this one.
 */
class UserMutationsWiringTest {
    @Test
    fun `user admin mutations are wired with @MutationMapping`() {
        val mutations = listOf("assignRoles", "removeRoles", "deleteUser", "removePermissions")
        for (name in mutations) {
            val method = UserMutations::class.java.declaredMethods.firstOrNull { it.name == name }
            assertThat(method)
                .describedAs("Mutation.$name handler method exists")
                .isNotNull

            assertThat(method!!.getAnnotation(MutationMapping::class.java))
                .describedAs("Mutation.$name must be wired with @MutationMapping (users.graphqls declares it under Mutation)")
                .isNotNull
        }
    }
}
