package cta.app.graphql.queries

import com.coxautodev.graphql.tools.GraphQLQueryResolver
import com.coxautodev.graphql.tools.GraphQLResolver
import java.util.Optional
import cta.app.Donor
import cta.app.DonorRepository
import cta.app.graphql.filters.DonorWhereInput
import cta.app.services.FilterService
import cta.graphql.KeyValuePair
import cta.graphql.PaginationInput
import org.springframework.data.domain.Page
import org.springframework.data.domain.Sort
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Component

@Component
@PreAuthorize("hasAnyAuthority('app:admin', 'read:donors')")
class DonorQueries(
    private val donors: DonorRepository,
    private val filterService: FilterService
) : GraphQLQueryResolver {
    fun donorsConnection(page: PaginationInput?, where: DonorWhereInput?): Page<Donor> {
        val filter = filterService.donorFilter()
        val f: PaginationInput = page ?: PaginationInput()
        if (where == null) {
            return donors.findAll(filter, f.create())
        }
        return donors.findAll(filter.and(where.build()), f.create())
    }

    fun donors(where: DonorWhereInput, orderBy: MutableList<KeyValuePair>?): List<Donor> {
        val filter = filterService.donorFilter()
        return if (orderBy != null) {
            val sort: Sort = Sort.by(orderBy.map { Sort.Order(Sort.Direction.fromString(it.value), it.key) })
            donors.findAll(filter.and(where.build()), sort).toList()
        } else {
            donors.findAll(filter.and(where.build())).toList()
        }
    }

    fun donor(where: DonorWhereInput): Optional<Donor> = donors.findOne(filterService.donorFilter().and(where.build()))
}
