package cta.app.graphql.queries

import com.querydsl.core.types.Predicate
import cta.app.QReferringOrganisationContact
import cta.app.ReferringOrganisation
import cta.app.ReferringOrganisationContact
import cta.app.ReferringOrganisationContactRepository
import cta.app.ReferringOrganisationRepository
import cta.app.config.GraphQLScalarConfig
import cta.app.graphql.filters.ReferringOrganisationContactPublicWhereInput
import cta.graphql.ExactTextComparison
import graphql.scalars.ExtendedScalars
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLNamedType
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLSchema
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.TypeDefinitionRegistry
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

/**
 * Pins the anonymous referee lookup to a single exact address.
 *
 * Measured against production on 2026-09-21, before this was tightened:
 *
 *     { referringOrganisationContactsPublic(where: {email: {_like: "%"}}) { id fullName } }
 *
 * answered an unauthenticated caller with 2,387 rows - every referring-organisation contact's
 * id and full name. The query is deliberately anonymous, because the public referral form needs
 * it, but it accepted the full staff `TextComparison`: `_like`/`_contains` turned a lookup into
 * a bulk export of personal data, and the ids it hands out are the same ids the anonymous
 * `createDeviceRequest` accepts.
 *
 * The compatibility test matters as much as the refusals. The deployed dashboard sends
 * `email: { _ilike: $email }` with no wildcard (org-request.ts, findOrganisationContact), and
 * that document has to keep working with no client change.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class PublicContactLookupTest {
    @MockBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var contacts: ReferringOrganisationContactRepository

    @Autowired
    lateinit var organisations: ReferringOrganisationRepository

    private fun graphQl(body: String): String =
        mockMvc
            .perform(
                post("/graphql")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andReturn()
            .response.contentAsString

    private fun query(q: String): String = graphQl("""{"query":"$q"}""")

    private fun email() = QReferringOrganisationContact.referringOrganisationContact.email

    @Test
    fun `anonymous wildcard email filter is rejected`() {
        val content = query("{ referringOrganisationContactsPublic(where: {email: {_like: \\\"%\\\"}}) { id fullName } }")

        assertTrue(content.contains("\"errors\""), "a wildcard email filter must be rejected: $content")
        assertFalse(
            content.contains("\"fullName\":\""),
            "no contact name may be returned to an anonymous wildcard query: $content",
        )
    }

    @Test
    fun `anonymous substring email filter is rejected`() {
        val content = query("{ referringOrganisationContactsPublic(where: {email: {_contains: \\\"@\\\"}}) { id fullName } }")

        assertTrue(content.contains("\"errors\""), "a substring email filter must be rejected: $content")
    }

    @Test
    fun `anonymous lookup without an email is rejected`() {
        val content = query("{ referringOrganisationContactsPublic(where: {archived: {_eq: false}}) { id fullName } }")

        assertTrue(
            content.contains("\"errors\""),
            "omitting the address must be rejected rather than listing everyone: $content",
        )
    }

    @Test
    fun `the dashboard's own lookup still works unchanged`() {
        val document =
            "query findOrganisationContact(${'$'}email: String, ${'$'}refOrgId: Long) " +
                "{ referringOrganisationContactsPublic(where: { email: { _ilike: ${'$'}email } " +
                "referringOrganisation: { id: { _eq: ${'$'}refOrgId } } archived: { _eq: false } }) " +
                "{ id fullName } }"

        val content =
            graphQl(
                """{"query":"$document","variables":{"email":"nobody@example.com","refOrgId":1}}""",
            )

        assertFalse(
            content.contains("\"errors\""),
            "the deployed dashboard's lookup must keep working with no client change: $content",
        )
        assertTrue(
            content.contains("\"referringOrganisationContactsPublic\":[]"),
            "an address nobody holds should come back empty: $content",
        )
    }

    /**
     * The positive path, and the one whose absence would be silent: if the exact match stopped
     * matching, the public form would simply never recognise a returning referee and would
     * offer to create a duplicate contact instead. Nothing would error.
     */
    @Test
    fun `a real contact is still found, whatever the caller's capitalisation`() {
        val organisation = organisations.save(ReferringOrganisation(name = "Positive Path Org ${System.nanoTime()}"))
        val address = "Returning.Referee.${System.nanoTime()}@Example.ORG"
        val saved =
            contacts.save(
                ReferringOrganisationContact(
                    fullName = "Returning Referee",
                    email = address,
                    phoneNumber = "07000000000",
                    address = "1 Test Street",
                    referringOrganisation = organisation,
                ),
            )

        val content = lookup(address.uppercase(), organisation.id)

        assertFalse(content.contains("\"errors\""), "a differently-cased exact address must still match: $content")
        assertTrue(
            content.contains("\"id\":\"${saved.id}\""),
            "the stored contact should come back so the form reuses it instead of creating a duplicate: $content",
        )
    }

    private fun lookup(
        address: String,
        organisationId: Long,
    ): String {
        val document =
            "query findOrganisationContact(${'$'}email: String, ${'$'}refOrgId: Long) " +
                "{ referringOrganisationContactsPublic(where: { email: { _ilike: ${'$'}email } " +
                "referringOrganisation: { id: { _eq: ${'$'}refOrgId } } }) { id fullName } }"
        return graphQl(
            """{"query":"$document","variables":{"email":"$address","refOrgId":$organisationId}}""",
        )
    }

    @Test
    fun `an absent address matches nothing rather than everything`() {
        val builder = ExactTextComparison().build(email())

        assertTrue(
            builder.hasValue(),
            "with no address the filter must still carry a predicate - an empty one returns the whole table",
        )
    }

    @Test
    fun `the match is case-insensitive and trimmed`() {
        val rendered = ExactTextComparison(_eq = "  Someone@Example.COM  ").build(email()).toString()

        assertTrue(
            rendered.contains("eqIc", ignoreCase = true),
            "expected a case-insensitive equality (QueryDSL eqIc), got: $rendered",
        )
        assertTrue(
            rendered.contains("Someone@Example.COM"),
            "the address should be trimmed, not otherwise altered: $rendered",
        )
        assertFalse(rendered.contains("  Someone"), "leading whitespace should be trimmed: $rendered")
    }

    @Test
    fun `ilike means exact match, so a wildcard is matched literally`() {
        val viaIlike = ExactTextComparison(_ilike = "%").build(email()).toString()
        val viaEq = ExactTextComparison(_eq = "%").build(email()).toString()

        assertEquals(viaEq, viaIlike, "_ilike must behave exactly like _eq")
        assertFalse(
            viaIlike.contains("like", ignoreCase = true),
            "a wildcard must not become a LIKE predicate: $viaIlike",
        )
    }

    @Test
    fun `the public lookup asks the database for a bounded page`() {
        val repository = Mockito.mock(ReferringOrganisationContactRepository::class.java)
        Mockito
            .`when`(repository.findAll(Mockito.any(Predicate::class.java), Mockito.any(Pageable::class.java)))
            .thenReturn(PageImpl(emptyList<ReferringOrganisationContact>()))
        val pageable = ArgumentCaptor.forClass(Pageable::class.java)

        ReferringOrganisationContactQueries(repository).referringOrganisationContactsPublic(
            ReferringOrganisationContactPublicWhereInput(email = ExactTextComparison(_eq = "someone@example.com")),
            null,
        )

        Mockito.verify(repository).findAll(Mockito.any(Predicate::class.java), pageable.capture())
        assertEquals(
            ReferringOrganisationContactQueries.MAX_PUBLIC_RESULTS,
            pageable.value.pageSize,
            "the anonymous lookup must be capped in SQL, not just by the caller's good manners",
        )
    }

    @Test
    fun `the public where input exposes no wildcard operators in the schema`() {
        val schema = assembleSchema()
        val input = schema.getType("ReferringOrganisationContactPublicWhereInput") as GraphQLInputObjectType

        val emailField = input.getField("email")
        assertTrue(emailField.type is GraphQLNonNull, "email must be required on the public input")
        assertEquals(
            "ExactTextComparison",
            ((emailField.type as GraphQLNonNull).wrappedType as GraphQLNamedType).name,
            "email must use the narrow public comparison, not TextComparison",
        )

        listOf("AND", "OR", "NOT").forEach { combinator ->
            assertNull(
                input.getField(combinator),
                "$combinator lets an anonymous caller rebuild a broad query",
            )
        }

        val comparison = schema.getType("ExactTextComparison") as GraphQLInputObjectType
        assertEquals(
            setOf("_eq", "_ilike"),
            comparison.fields.map { it.name }.toSet(),
            "ExactTextComparison must stay exact-match only",
        )
    }

    private fun assembleSchema(): GraphQLSchema {
        val scalars = GraphQLScalarConfig()
        val registry = TypeDefinitionRegistry()
        val parser = SchemaParser()
        PathMatchingResourcePatternResolver()
            .getResources("classpath*:graphql/*.graphqls")
            .forEach { resource ->
                resource.inputStream.reader().use { reader -> registry.merge(parser.parse(reader)) }
            }
        val wiring =
            RuntimeWiring
                .newRuntimeWiring()
                .scalar(ExtendedScalars.GraphQLBigDecimal)
                .scalar(ExtendedScalars.GraphQLLong)
                .scalar(scalars.instantScalar())
                .scalar(scalars.lenientStringScalar())
                .build()
        return SchemaGenerator().makeExecutableSchema(registry, wiring)
    }
}
