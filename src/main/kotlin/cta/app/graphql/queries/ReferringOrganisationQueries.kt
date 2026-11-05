package cta.app.graphql.queries

import cta.app.ReferringOrganisation
import cta.app.ReferringOrganisationRepository
import cta.app.graphql.filters.ReferringOrganisationPublicWhereInput
import cta.app.graphql.filters.ReferringOrganisationWhereInput
import cta.app.services.BookingRateLimiter
import cta.app.services.PublicCreateThrottle
import cta.graphql.KeyValuePair
import cta.graphql.PaginationInput
import cta.graphql.TextComparison
import cta.graphql.sortOf
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import java.util.Optional

@Controller
class ReferringOrganisationQueries(
    private val referringOrganisations: ReferringOrganisationRepository,
    private val publicThrottle: PublicCreateThrottle,
    @Qualifier("publicOrganisationLookupRateLimiter") private val lookupRateLimiter: BookingRateLimiter,
) {
    @PreAuthorize("hasAnyAuthority('app:admin', 'read:organisations')")
    @QueryMapping
    fun referringOrganisations(
        @Argument where: ReferringOrganisationWhereInput,
        @Argument orderBy: MutableList<KeyValuePair>?,
    ): List<ReferringOrganisation> =
        if (orderBy != null) {
            val sort: Sort = Sort.by(orderBy.map { Sort.Order(Sort.Direction.fromString(it.value), it.key) })
            referringOrganisations.findAll(where.build(), sort).toList()
        } else {
            referringOrganisations.findAll(where.build()).toList()
        }

    @PreAuthorize("hasAnyAuthority('app:admin', 'read:organisations')")
    @QueryMapping
    fun referringOrganisationsConnection(
        @Argument page: PaginationInput?,
        @Argument where: ReferringOrganisationWhereInput?,
    ): Page<ReferringOrganisation> {
        val f: PaginationInput = page ?: PaginationInput()
        if (where == null) {
            return referringOrganisations.findAll(f.create())
        }
        return referringOrganisations.findAll(where.build(), f.create())
    }

    /**
     * Anonymous: backs the public request form's typeahead, and the staff referee-page picker.
     * #352: an anonymous caller spends a per-IP budget, and only a name `_contains` of at least
     * [PUBLIC_MIN_TERM_LENGTH] characters is honoured (other name comparators would let a caller
     * page through every name). Every caller gets at most [PUBLIC_RESULT_LIMIT] results.
     */
    @QueryMapping
    fun referringOrganisationsPublic(
        @Argument where: ReferringOrganisationPublicWhereInput,
        @Argument orderBy: MutableList<KeyValuePair>?,
    ): List<ReferringOrganisationPublic> {
        publicThrottle.acquireLookupOrThrow(lookupRateLimiter)
        val filter =
            if (publicThrottle.isStaffReader()) {
                where
            } else {
                val term = where.name?._contains?.trim()
                if (term == null || term.length < PUBLIC_MIN_TERM_LENGTH) return emptyList()
                ReferringOrganisationPublicWhereInput(name = TextComparison(_contains = term), archived = where.archived)
            }
        val sort: Sort = if (orderBy != null) sortOf(orderBy, PUBLIC_SORT_KEYS) else Sort.by("name", "id")
        return referringOrganisations
            .findAll(filter.build(), PageRequest.of(0, PUBLIC_RESULT_LIMIT, sort))
            .map { ReferringOrganisationPublic(it.id, it.name) }
            .toList()
    }

    @PreAuthorize("hasAnyAuthority('app:admin', 'read:organisations')")
    @QueryMapping
    fun referringOrganisation(
        @Argument where: ReferringOrganisationWhereInput,
    ): Optional<ReferringOrganisation> = referringOrganisations.findOne(where.build())

    companion object {
        /** Anonymous callers may sort only by what ReferringOrganisationPublic shows. */
        val PUBLIC_SORT_KEYS: Set<String> = setOf("id", "name")

        /** The public form only searches from 3 characters, so this costs it nothing. */
        const val PUBLIC_MIN_TERM_LENGTH = 3

        /** Matches the referee-page picker's existing client-side cap. */
        const val PUBLIC_RESULT_LIMIT = 50
    }
}

data class ReferringOrganisationPublic(
    val id: Long,
    val name: String,
)
