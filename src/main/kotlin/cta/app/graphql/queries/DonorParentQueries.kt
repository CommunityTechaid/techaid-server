package cta.app.graphql.queries

import com.coxautodev.graphql.tools.GraphQLQueryResolver
import com.coxautodev.graphql.tools.GraphQLResolver
import java.util.Optional
import cta.app.DonorParent
import cta.app.DonorParentRepository
import cta.app.Volunteer
import cta.app.graphql.filters.DonorParentWhereInput
import cta.app.services.FilterService
import cta.graphql.KeyValuePair
import cta.graphql.PaginationInput
import org.springframework.data.domain.Page
import org.springframework.data.domain.Sort
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Component

@Component
@PreAuthorize("hasAnyAuthority('app:admin', 'read:donorParents')")
class DonorParentQueries(
    private val donorParents: DonorParentRepository
) : GraphQLQueryResolver {
    fun donorParentsConnection(page: PaginationInput?, where: DonorParentWhereInput?): Page<DonorParent> {
        val f: PaginationInput = page ?: PaginationInput()
        if (where == null) {
            return donorParents.findAll(f.create())
        }
        return donorParents.findAll(where.build(), f.create())
    }

    fun donorParents(where: DonorParentWhereInput, orderBy: MutableList<KeyValuePair>?): List<DonorParent> {
        return if (orderBy != null) {
            val sort: Sort = Sort.by(orderBy.map { Sort.Order(Sort.Direction.fromString(it.value), it.key) })
            donorParents.findAll(where.build(), sort).toList()
        } else {
            donorParents.findAll(where.build()).toList()
        }
    }

    fun donorParent(where: DonorParentWhereInput): Optional<DonorParent> = donorParents.findOne(where.build())
}