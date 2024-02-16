package cta.app.graphql.queries

import com.coxautodev.graphql.tools.GraphQLQueryResolver
import java.util.Optional
import cta.app.Organisation
import cta.app.OrganisationRepository
import cta.app.RequestCount
import cta.app.graphql.filters.OrganisationWhereInput
import cta.app.services.FilterService
import cta.graphql.KeyValuePair
import cta.graphql.PaginationInput
import org.springframework.data.domain.Page
import org.springframework.data.domain.Sort
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Component

@Component
class OrganisationQueries(
    private val organisations: OrganisationRepository,
    private val filterService: FilterService
) : GraphQLQueryResolver {
    fun requestCount(): RequestCount? {
        if (filterService.authenticated()) {
            return organisations.requestCount()
        }
        return null
    }

    @PreAuthorize("hasAnyAuthority('app:admin', 'read:organisations')")
    fun organisationsConnection(page: PaginationInput?, where: OrganisationWhereInput?): Page<Organisation> {
        val f: PaginationInput = page ?: PaginationInput()
        if (where == null) {
            return organisations.findAll(f.create())
        }
        return organisations.findAll(where.build(), f.create())
    }

    @PreAuthorize("hasAnyAuthority('app:admin', 'read:organisations')")
    fun organisations(where: OrganisationWhereInput, orderBy: MutableList<KeyValuePair>?): List<Organisation> {
        return if (orderBy != null) {
            val sort: Sort = Sort.by(orderBy.map { Sort.Order(Sort.Direction.fromString(it.value), it.key) })
            organisations.findAll(where.build(), sort).toList()
        } else {
            organisations.findAll(where.build()).toList()
        }
    }

    @PreAuthorize("hasAnyAuthority('app:admin', 'read:organisations')")
    fun organisation(where: OrganisationWhereInput): Optional<Organisation> = organisations.findOne(where.build())
}
