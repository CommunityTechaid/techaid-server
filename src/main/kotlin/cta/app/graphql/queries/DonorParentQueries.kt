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
@PreAuthorize("hasAnyAuthority('app:admin', 'read:dropPoints')")
class DonorParentQueries(
    private val dropPoints: DonorParentRepository
) : GraphQLQueryResolver {
    fun dropPointsConnection(page: PaginationInput?, where: DonorParentWhereInput?): Page<DonorParent> {
        val f: PaginationInput = page ?: PaginationInput()
        if (where == null) {
            return dropPoints.findAll(f.create())
        }
        return dropPoints.findAll(where.build(), f.create())
    }

    fun dropPoints(where: DonorParentWhereInput, orderBy: MutableList<KeyValuePair>?): List<DonorParent> {
        return if (orderBy != null) {
            val sort: Sort = Sort.by(orderBy.map { Sort.Order(Sort.Direction.fromString(it.value), it.key) })
            dropPoints.findAll(where.build(), sort).toList()
        } else {
            dropPoints.findAll(where.build()).toList()
        }
    }

    fun dropPoint(where: DonorParentWhereInput): Optional<DonorParent> = dropPoints.findOne(where.build())
}