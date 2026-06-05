package cta.app.graphql.queries

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.graphql.data.method.annotation.SchemaMapping

/**
 * Guards the ReferringOrganisationContact.notes nested field resolver.
 *
 * The entity collection is named `referringOrganisationContactNotes`, but the GraphQL field is
 * `notes` (matching Kit's convention so the dashboard can reuse the same shape). With no
 * @SchemaMapping bridging the name mismatch, graphql-java falls back to the default
 * PropertyDataFetcher, finds no `notes` property and resolves the field to null with no error —
 * the same silent-null failure mode covered by UserRoleNestedResolverWiringTest.
 *
 * Pure-reflection test (no Spring context / DB) so it runs without Docker.
 */
class ReferringOrganisationContactNotesResolverWiringTest {
    @Test
    fun `ReferringOrganisationContact notes field is wired with @SchemaMapping`() {
        val method =
            ReferringOrganisationContactQueries::class.java.declaredMethods
                .firstOrNull { it.name == "notes" }
        assertThat(method)
            .describedAs("ReferringOrganisationContact.notes resolver method exists")
            .isNotNull

        val mapping = method!!.getAnnotation(SchemaMapping::class.java)
        assertThat(mapping)
            .describedAs("ReferringOrganisationContact.notes must be wired with @SchemaMapping")
            .isNotNull
        assertThat(mapping.typeName).isEqualTo("ReferringOrganisationContact")
        assertThat(mapping.field).isEqualTo("notes")
    }
}
