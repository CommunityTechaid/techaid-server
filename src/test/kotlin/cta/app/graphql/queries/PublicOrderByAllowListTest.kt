package cta.app.graphql.queries

import cta.app.ReferringOrganisation
import cta.app.ReferringOrganisationContact
import cta.app.ReferringOrganisationContactRepository
import cta.app.ReferringOrganisationRepository
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.json.JsonMapper

/**
 * The two anonymous list queries accept a client-chosen `orderBy` property path. Spring Data checks
 * that the path names a real entity property, but any property is accepted, including ones reached
 * through associations that the public return type deliberately hides - so the ORDER of public
 * results could be steered by private data. Sorting is now restricted to the fields the public
 * type exposes, with ASC/DESC as the only directions. Tested with real documents over HTTP because
 * the argument is schema-level input from an unauthenticated caller.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class PublicOrderByAllowListTest {
    @MockitoBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var referringOrganisations: ReferringOrganisationRepository

    @Autowired
    lateinit var referringOrganisationContacts: ReferringOrganisationContactRepository

    private val json = JsonMapper.builder().build()

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun anonymousGraphQl(
        document: String,
        variables: String,
    ): String =
        mockMvc
            .perform(
                post("/graphql")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"query":${quote(document)},"variables":$variables}"""),
            ).andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

    private fun organisations(
        term: String,
        key: String,
        direction: String,
    ): String =
        anonymousGraphQl(
            "query (\$term: String, \$key: String!, \$dir: String!) { referringOrganisationsPublic(where: { name: { _contains: \$term } }, orderBy: [{ key: \$key, value: \$dir }]) { id name } }",
            """{"term":"$term","key":"$key","dir":"$direction"}""",
        )

    private fun contacts(
        email: String,
        key: String,
        direction: String,
    ): String =
        anonymousGraphQl(
            "query (\$email: String, \$key: String!, \$dir: String!) { referringOrganisationContactsPublic(where: { email: { _ilike: \$email } }, orderBy: [{ key: \$key, value: \$dir }]) { id fullName } }",
            """{"email":"$email","key":"$key","dir":"$direction"}""",
        )

    private fun names(
        content: String,
        field: String,
        property: String,
    ): List<String> {
        val tree = json.readTree(content)
        assertTrue(tree.path("errors").isMissingNode, "expected no errors: $content")
        return tree
            .path("data")
            .path(field)
            .iterator()
            .asSequence()
            .map { it.path(property).asString() }
            .toList()
    }

    private fun assertRejected(
        content: String,
        field: String,
    ) {
        val tree = json.readTree(content)
        assertFalse(tree.path("errors").isMissingNode, "the sort must be refused: $content")
        assertTrue(
            tree
                .path("errors")[0]
                .path("message")
                .asString()
                .contains("sort"),
            "the refusal must say why: $content",
        )
        assertTrue(tree.path("data").path(field).isNull, "no rows may come back: $content")
    }

    @Test
    fun `organisations cannot be sorted by a property the public type hides`() {
        val term = organisationsNamed("hidden").first().name

        assertRejected(organisations(term, "referringOrganisationContacts.email", "ASC"), "referringOrganisationsPublic")
        assertRejected(organisations(term, "phoneNumber", "ASC"), "referringOrganisationsPublic")
    }

    @Test
    fun `organisations cannot be sorted in an unknown direction`() {
        val term = organisationsNamed("direction").first().name

        assertRejected(organisations(term, "name", "SIDEWAYS"), "referringOrganisationsPublic")
    }

    @Test
    fun `organisations still sort by name and id`() {
        val stamp = System.nanoTime().toString()
        organisationsNamed(stamp)

        assertEquals(
            listOf("A $stamp", "B $stamp", "C $stamp"),
            names(organisations(stamp, "name", "ASC"), "referringOrganisationsPublic", "name"),
        )
        assertEquals(
            listOf("C $stamp", "B $stamp", "A $stamp"),
            names(organisations(stamp, "name", "desc"), "referringOrganisationsPublic", "name"),
        )
        assertEquals(
            listOf("A $stamp", "B $stamp", "C $stamp"),
            names(organisations(stamp, "id", "DESC"), "referringOrganisationsPublic", "name"),
        )
    }

    @Test
    fun `contacts cannot be sorted by a property the public type hides`() {
        val email = contactsWithSharedEmail().first().email

        assertRejected(contacts(email, "phoneNumber", "ASC"), "referringOrganisationContactsPublic")
        assertRejected(contacts(email, "referringOrganisation.name", "ASC"), "referringOrganisationContactsPublic")
    }

    @Test
    fun `contacts still sort by fullName and id`() {
        val email = contactsWithSharedEmail().first().email

        assertEquals(listOf("Ann", "Bob"), names(contacts(email, "fullName", "ASC"), "referringOrganisationContactsPublic", "fullName"))
        assertEquals(listOf("Bob", "Ann"), names(contacts(email, "id", "DESC"), "referringOrganisationContactsPublic", "fullName"))
    }

    // Saved C, B, A so that id order and name order disagree.
    private fun organisationsNamed(stamp: String): List<ReferringOrganisation> =
        listOf("C", "B", "A").map { referringOrganisations.save(ReferringOrganisation(name = "$it $stamp")) }

    // Saved Ann then Bob in one organisation, sharing an email (the public lookup is by exact email).
    private fun contactsWithSharedEmail(): List<ReferringOrganisationContact> {
        val stamp = System.nanoTime()
        val organisation = referringOrganisations.save(ReferringOrganisation(name = "Org contacts $stamp"))
        return listOf("Ann", "Bob").map {
            referringOrganisationContacts.save(
                ReferringOrganisationContact(
                    fullName = it,
                    email = "shared-$stamp@example.com",
                    phoneNumber = "0$stamp",
                    address = "1 Test Street",
                    referringOrganisation = organisation,
                ),
            )
        }
    }
}
