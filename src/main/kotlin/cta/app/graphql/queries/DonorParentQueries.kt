package cta.app.graphql.queries

import cta.app.DonorParent
import cta.app.DonorParentRepository
import cta.app.graphql.filters.DonorParentWhereInput
import cta.graphql.KeyValuePair
import cta.graphql.PaginationInput
import org.springframework.data.domain.Page
import org.springframework.data.domain.Sort
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Component
import java.util.Optional

@Component
@PreAuthorize("hasAnyAuthority('app:admin', 'read:donorParents')")
class DonorParentQueries(
    private val donorParents: DonorParentRepository
) {
    @QueryMapping
    fun donorParentsConnection(@Argument page: PaginationInput?, @Argument where: DonorParentWhereInput?): Page<DonorParent> {
        val f: PaginationInput = page ?: PaginationInput()
        if (where == null) {
            return donorParents.findAll(f.create())
        }
        return donorParents.findAll(where.build(), f.create())
    }

    @QueryMapping
    fun donorParents(@Argument where: DonorParentWhereInput, @Argument orderBy: MutableList<KeyValuePair>?): List<DonorParent> {
        return if (orderBy != null) {
            val sort: Sort = Sort.by(orderBy.map { Sort.Order(Sort.Direction.fromString(it.value), it.key) })
            donorParents.findAll(where.build(), sort).toList()
        } else {
            donorParents.findAll(where.build()).toList()
        }
    }

    @QueryMapping
    fun donorParent(@Argument where: DonorParentWhereInput): Optional<DonorParent> = donorParents.findOne(where.build())
}