package cta.app.graphql

import com.querydsl.core.BooleanBuilder
import cta.app.Post
import cta.app.PostRepository
import cta.app.QPost
import cta.graphql.BooleanComparison
import cta.graphql.KeyValuePair
import cta.graphql.LongComparision
import cta.graphql.PaginationInput
import cta.graphql.TextComparison
import cta.graphql.TimeComparison
import cta.toNullable
import jakarta.persistence.EntityNotFoundException
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.data.domain.Page
import org.springframework.data.domain.Sort
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated
import java.time.Instant

@Controller
class BlogQueries(
    private val posts: PostRepository
) {
    @PreAuthorize("hasAnyAuthority('read:content')")
    @QueryMapping
    fun postsConnection(@Argument page: PaginationInput?, @Argument where: PostWhereInput?): Page<Post> {
        val f: PaginationInput = page ?: PaginationInput()
        if (where == null) {
            return posts.findAll(f.create())
        }
        return posts.findAll(where.build(), f.create())
    }

    @PreAuthorize("hasAnyAuthority('read:content')")
    @QueryMapping
    fun posts(@Argument where: PostWhereInput, @Argument orderBy: MutableList<KeyValuePair>?): List<Post> {
        return if (orderBy != null) {
            val sort: Sort = Sort.by(orderBy.map { Sort.Order(Sort.Direction.fromString(it.value), it.key) })
            posts.findAll(where.build(), sort).toList()
        } else {
            posts.findAll(where.build()).toList()
        }
    }

    @QueryMapping
    fun post(@Argument where: PostWhereInput): Post? {
        val post = posts.findOne(where.build()).toNullable() ?: return null
        if (post.secured) {
            SecurityContextHolder.getContext().authentication?.let { auth ->
                auth.authorities.firstOrNull { it.authority == "read:content" } ?: return null
            }
        }
        return post
    }
}

@Controller
@Transactional
@Validated
@PreAuthorize("hasAnyAuthority('write:content')")
class BlogMutations(
    private val posts: PostRepository
) {
    @MutationMapping
    fun createPost(@Argument @Valid data: CreatePostInput): Post {
        return posts.save(data.entity)
    }

    @MutationMapping
    fun updatePost(@Argument  @Valid data: UpdatePostInput): Post {
        val entity = posts.findById(data.id).toNullable()
            ?: throw EntityNotFoundException("Unable to locate a post with id: ${data.id}")
        return data.apply(entity)
    }

    @MutationMapping
    fun deletePost(@Argument  id: Long): Boolean {
        posts.deleteById(id)
        return true
    }
}

data class CreatePostInput(
    @get:NotBlank
    val title: String,
    @get:NotBlank
    val slug: String,
    val content: String,
    val secured: Boolean = false,
    val published: Boolean = true
) {
    val entity by lazy {
        Post(
            slug = slug,
            content = content,
            published = published,
            secured = secured,
            title = title
        )
    }
}

data class UpdatePostInput(
    val id: Long,
    @get:NotBlank
    val slug: String,
    @get:NotBlank
    val title: String,
    val content: String,
    val secured: Boolean = false,
    val published: Boolean = true
) {
    fun apply(entity: Post): Post {
        val self = this
        return entity.apply {
            slug = self.slug
            content = self.content
            published = self.published
            title = self.title
            secured = self.secured
        }
    }
}

class PostWhereInput(
    var id: LongComparision? = null,
    var content: TextComparison? = null,
    var slug: TextComparison? = null,
    var title: TextComparison? = null,
    var published: BooleanComparison? = null,
    var createdAt: TimeComparison<Instant>? = null,
    var updatedAt: TimeComparison<Instant>? = null,
    var AND: MutableList<PostWhereInput> = mutableListOf(),
    var OR: MutableList<PostWhereInput> = mutableListOf(),
    var NOT: MutableList<PostWhereInput> = mutableListOf()
) {
    fun build(entity: QPost = QPost.post): BooleanBuilder {
        val builder = BooleanBuilder()
        id?.let { builder.and(it.build(entity.id)) }
        content?.let { builder.and(it.build(entity.content)) }
        slug?.let { builder.and(it.build(entity.slug)) }
        title?.let { builder.and(it.build(entity.title)) }
        published?.let { builder.and(it.build(entity.published)) }
        createdAt?.let { builder.and(it.build(entity.createdAt)) }
        updatedAt?.let { builder.and(it.build(entity.updatedAt)) }
        if (AND.isNotEmpty()) {
            AND.forEach {
                builder.and(it.build(entity))
            }
        }

        if (OR.isNotEmpty()) {
            OR.forEach {
                builder.or(it.build(entity))
            }
        }

        if (NOT.isNotEmpty()) {
            NOT.forEach {
                builder.andNot(it.build(entity))
            }
        }
        return builder
    }
}
