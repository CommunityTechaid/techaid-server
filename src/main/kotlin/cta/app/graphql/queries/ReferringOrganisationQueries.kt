package cta.app.graphql.queries

import com.coxautodev.graphql.tools.GraphQLQueryResolver
import cta.app.ReferringOrganisation
import cta.app.ReferringOrganisationRepository
import cta.app.graphql.filters.ReferringOrganisationWhereInput
import cta.graphql.KeyValuePair
import cta.graphql.PaginationInput
import org.springframework.data.domain.Page
import org.springframework.data.domain.Sort
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Component
import java.util.Optional

@Component
class ReferringOrganisationQueries(
    private val referringOrganisations: ReferringOrganisationRepository
) : GraphQLQueryResolver {

    @PreAuthorize("hasAnyAuthority('app:admin', 'read:organisations')")
    fun referringOrganisations(where: ReferringOrganisationWhereInput, orderBy: MutableList<KeyValuePair>?): List<ReferringOrganisation> {
        return if (orderBy != null) {
            val sort: Sort = Sort.by(orderBy.map { Sort.Order(Sort.Direction.fromString(it.value), it.key) })
            referringOrganisations.findAll(where.build(), sort).toList()
        } else {
            referringOrganisations.findAll(where.build()).toList()
        }
    }

    @PreAuthorize("hasAnyAuthority('app:admin', 'read:organisations')")
    fun referringOrganisationsConnection(page: PaginationInput?, where: ReferringOrganisationWhereInput?): Page<ReferringOrganisation> {
        val f: PaginationInput = page ?: PaginationInput()
        if (where == null) {
            return referringOrganisations.findAll(f.create())
        }
        return referringOrganisations.findAll(where.build(), f.create())
    }

    fun referringOrganisationsPublic(where: ReferringOrganisationWhereInput, orderBy: MutableList<KeyValuePair>?): List<ReferringOrganisationPublic> {
        return if (orderBy != null) {
            val sort: Sort = Sort.by(orderBy.map { Sort.Order(Sort.Direction.fromString(it.value), it.key) })
            referringOrganisations.findAll(where.build(), sort).map{ReferringOrganisationPublic(it.id, it.name)}.toList()
        } else {
            referringOrganisations.findAll(where.build()).map{ReferringOrganisationPublic(it.id, it.name)}.toList()
        }
    }

    @PreAuthorize("hasAnyAuthority('app:admin', 'read:organisations')")
    fun referringOrganisation(where: ReferringOrganisationWhereInput): Optional<ReferringOrganisation> = referringOrganisations.findOne(where.build())
}

data class ReferringOrganisationPublic(
    val id: Long,
    val name: String
){
}