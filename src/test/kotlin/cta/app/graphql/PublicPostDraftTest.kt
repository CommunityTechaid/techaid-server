package cta.app.graphql

import cta.app.Post
import cta.app.PostRepository
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * The `post` query is public on purpose: the anonymous referral form and the dashboard's catch-all
 * content page both read a post by slug. But it returned ANY post that was not `secured`, including
 * unpublished drafts, and its text filters (`content`, `title`, ...) let an anonymous caller probe a
 * draft's wording even without knowing its slug. Anonymous callers now see published posts only;
 * staff with `read:content` still read drafts in the post editor.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class PublicPostDraftTest {
    @MockitoBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var posts: PostRepository

    private fun postBySlug(
        slug: String,
        customise: (MockHttpServletRequestBuilder) -> MockHttpServletRequestBuilder = { it },
    ): ResultActions =
        mockMvc
            .perform(
                customise(
                    post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"query":"query (${'$'}slug: String) { post(where: { slug: { _eq: ${'$'}slug } }) { id content } }","variables":{"slug":"$slug"}}""",
                        ),
                ),
            ).andExpect(status().isOk)

    private fun save(published: Boolean): Post {
        val stamp = System.nanoTime()
        return posts.save(Post(title = "T $stamp", slug = "/slug-$stamp", published = published, secured = false, content = "body $stamp"))
    }

    @Test
    fun `an anonymous caller cannot read an unpublished draft`() {
        val draft = save(published = false)

        postBySlug(draft.slug)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.post").doesNotExist())
    }

    // org-request.ts reads its intro text anonymously by slug; post-data.component.ts likewise.
    @Test
    fun `an anonymous caller still reads a published post`() {
        val published = save(published = true)

        postBySlug(published.slug)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.post.content").value(published.content))
    }

    // post-info.component.ts: the staff editor opens drafts.
    @Test
    fun `staff with read content still read a draft`() {
        val draft = save(published = false)

        postBySlug(draft.slug) { it.with(jwt().authorities(SimpleGrantedAuthority("read:content"))) }
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.post.content").value(draft.content))
    }

    @Test
    fun `an authenticated caller without read content cannot read a draft`() {
        val draft = save(published = false)

        postBySlug(draft.slug) { it.with(jwt().authorities(SimpleGrantedAuthority("read:kits"))) }
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.post").doesNotExist())
    }
}
