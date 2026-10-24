package cta.graphql

import com.querydsl.core.types.Expression
import com.querydsl.core.types.Order
import com.querydsl.core.types.OrderSpecifier
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort

enum class PageOrderBy {
    /**
     * in the ascending order, nulls last
     */
    ASC,

    /**
     * in the ascending order, nulls first
     */
    ASC_NULLS_FIRST,

    /**
     * in the ascending order, nulls last
     */
    ASC_NULLS_LAST,

    /**
     * in the descending order, nulls first
     */
    DESC,

    /**
     * in the descending order, nulls first
     */
    DESC_NULLS_FIRST,

    /**
     * in the descending order, nulls last
     */
    DESC_NULLS_LAST,
}

/**
 * A representation of a filter for requesting paginated data
 */
class PaginationInput(
    /**
     * The current page being requested
     */
    var page: Int = 0,
    /**
     * The number of elements in the page
     */
    var size: Int = 10,
    /**
     * How to sort the returned data. The key is the name of the column
     * with the value being the sort order ASC|DESC
     */
    var sort: List<KeyValuePair>? = null,
) {
    companion object {
        fun sortBy(sorted: List<KeyValuePair>): Sort = Sort.by(sorted.map { Sort.Order(Sort.Direction.fromString(it.value), it.key) })

        fun <T : Comparable<*>> createOrder(
            order: PageOrderBy,
            target: Expression<T>,
        ): OrderSpecifier<T> =
            when (order) {
                PageOrderBy.ASC -> {
                    OrderSpecifier(Order.ASC, target)
                }

                PageOrderBy.ASC_NULLS_FIRST -> {
                    OrderSpecifier(Order.ASC, target, OrderSpecifier.NullHandling.NullsFirst)
                }

                PageOrderBy.ASC_NULLS_LAST -> {
                    OrderSpecifier(Order.ASC, target, OrderSpecifier.NullHandling.NullsLast)
                }

                PageOrderBy.DESC -> {
                    OrderSpecifier(Order.DESC, target)
                }

                PageOrderBy.DESC_NULLS_FIRST -> {
                    OrderSpecifier(
                        Order.DESC,
                        target,
                        OrderSpecifier.NullHandling.NullsFirst,
                    )
                }

                PageOrderBy.DESC_NULLS_LAST -> {
                    OrderSpecifier(Order.DESC, target, OrderSpecifier.NullHandling.NullsLast)
                }
            }
    }

    /**
     * Returns a page request that can be issued directly to a Spring Repository
     */
    fun create(): PageRequest {
        val sorted = sort ?: listOf()
        val sort: Sort = Sort.by(sorted.map { Sort.Order(Sort.Direction.fromString(it.value), it.key) })
        return PageRequest.of(page, size, sort)
    }

    fun create(sort: Sort): PageRequest = PageRequest.of(page, size, sort)
}

/**
 * A client asked to sort by a key or in a direction that the query does not allow. The message
 * describes only the caller's own input, so it is safe to return to anonymous callers.
 */
class InvalidSortException(
    message: String,
) : IllegalArgumentException(message)

/**
 * Turns client-supplied `orderBy` pairs into a [Sort], accepting only [allowedKeys] and ASC/DESC.
 *
 * Spring Data resolves any real entity property path, including ones through associations, so
 * without this a caller can order results by data the response type does not expose. Use it for
 * every orderBy that an untrusted caller can reach, with the keys the return type actually shows.
 */
fun sortOf(
    orderBy: List<KeyValuePair>,
    allowedKeys: Set<String>,
): Sort =
    Sort.by(
        orderBy.map {
            if (it.key !in allowedKeys) {
                throw InvalidSortException("Unsupported sort key. Allowed sort keys: ${allowedKeys.sorted().joinToString()}")
            }
            val direction =
                Sort.Direction
                    .fromOptionalString(it.value)
                    .orElseThrow { InvalidSortException("Unsupported sort direction. Allowed: ASC, DESC") }
            Sort.Order(direction, it.key)
        },
    )

/**
 * Representation of a key value pair for graphQL
 */
class KeyValuePair(
    /**
     * The name of the key
     */
    var key: String,
    /**
     * The value for the key
     */
    var value: String,
)

/**
 *  Global error handling class
 */
data class AppError(
    /**
     * Descriptive error message
     */
    var message: String,
    /**
     * The class of the error
     */
    var type: String = "Exception",
    /**
     * The extra details about the exception
     */
    var context: MutableList<KeyValuePair> = mutableListOf(),
) {
    companion object {
        fun fromException(
            exception: Exception,
            message: String? = null,
        ): AppError =
            AppError(
                message = message ?: exception.message ?: "",
                type = exception.javaClass.simpleName,
            )

        fun create(
            message: String,
            type: String = "Exception",
            context: Map<String, String> = mapOf(),
        ): AppError =
            AppError(
                message = message,
                type = type,
                context = context.map { KeyValuePair(it.key, it.value) }.toMutableList(),
            )
    }
}
