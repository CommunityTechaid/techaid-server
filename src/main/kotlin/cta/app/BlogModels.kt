package cta.app

import java.time.Instant
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.SequenceGenerator
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import org.springframework.data.querydsl.QuerydslPredicateExecutor
import org.springframework.data.repository.CrudRepository
import org.springframework.data.repository.PagingAndSortingRepository

@Entity
@Table(name = "posts")
class Post(
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "post-seq-generator")
    @SequenceGenerator(name = "post-seq-generator", sequenceName = "post_sequence", allocationSize = 1)
    var id: Long = 0,
    var title: String,
    var slug: String,
    var published: Boolean,
    var secured: Boolean,
    var content: String,
    @CreationTimestamp
    var createdAt: Instant = Instant.now(),
    @UpdateTimestamp
    var updatedAt: Instant = Instant.now()
)

interface PostRepository : PagingAndSortingRepository<Post, Long>, QuerydslPredicateExecutor<Post>, CrudRepository<Post, Long>
