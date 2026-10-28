package cta.app.graphql.queries

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.graphql.data.method.annotation.BatchMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.security.access.prepost.PreAuthorize

/**
 * Pins the coordinates of the membership field resolvers (#311). With schema inspection disabled
 * a resolver on the wrong type or field name is not a boot failure: the field silently resolves
 * to null (or to the default property fetcher), which for `membership: Membership!` surfaces as a
 * non-null violation only at query time.
 *
 * `ReferringOrganisation.membership` must stay a `@BatchMapping`: it is selected per row in three
 * paged lists, and a plain `@SchemaMapping` would cost one usage count per row.
 *
 * Pure reflection (no Spring context / DB).
 */
class MembershipResolverWiringTest {
    private fun method(name: String) = MembershipResolvers::class.java.declaredMethods.first { it.name == name }

    @Test
    fun `ReferringOrganisation membership is batched`() {
        val mapping = method("membership").getAnnotation(BatchMapping::class.java)

        assertThat(mapping).describedAs("membership must be a @BatchMapping, not a per-row @SchemaMapping").isNotNull
        assertThat(mapping.typeName).isEqualTo("ReferringOrganisation")
        assertThat(mapping.field).isEqualTo("membership")
        assertThat(method("membership").getAnnotation(SchemaMapping::class.java)).isNull()
    }

    @Test
    fun `ReferringOrganisationContactPublic membershipAllowance is batched on the public type`() {
        val mapping = method("membershipAllowance").getAnnotation(BatchMapping::class.java)

        assertThat(mapping).isNotNull
        assertThat(mapping.typeName).isEqualTo("ReferringOrganisationContactPublic")
        assertThat(mapping.field).isEqualTo("membershipAllowance")
    }

    @Test
    fun `ReferringOrganisation membershipContacts is wired and gated like the other staff-only links`() {
        val method = method("membershipContacts")
        val mapping = method.getAnnotation(SchemaMapping::class.java)

        assertThat(mapping).isNotNull
        assertThat(mapping.typeName).isEqualTo("ReferringOrganisation")
        assertThat(mapping.field).isEqualTo("membershipContacts")
        assertThat(method.getAnnotation(PreAuthorize::class.java)).describedAs("staff-only field needs a gate").isNotNull
    }
}
