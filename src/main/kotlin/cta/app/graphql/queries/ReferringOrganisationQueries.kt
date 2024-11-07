package cta.app.graphql.queries

import cta.app.ReferringOrganisation
import cta.app.ReferringOrganisationRepository
import cta.app.graphql.filters.ReferringOrganisationWhereInput
import cta.graphql.KeyValuePair
import cta.graphql.PaginationInput
import org.springframework.data.domain.Page
import org.springframework.data.domain.Sort
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import java.util.Optional

@Controller
class ReferringOrganisationQueries(
    private val referringOrganisations: ReferringOrganisationRepository
)  {

    @PreAuthorize("hasAnyAuthority('app:admin', 'read:organisations')")
    @QueryMapping
    fun referringOrganisations(@Argument where: ReferringOrganisationWhereInput ,@Argument orderBy: MutableList<KeyValuePair>?): List<ReferringOrganisation> {
        return if (orderBy != null) {
            val sort: Sort = Sort.by(orderBy.map { Sort.Order(Sort.Direction.fromString(it.value), it.key) })
            referringOrganisations.findAll(where.build(), sort).toList()
        } else {
            referringOrganisations.findAll(where.build()).toList()
        }
    }

    @PreAuthorize("hasAnyAuthority('app:admin', 'read:organisations')")
    @QueryMapping
    fun referringOrganisationsConnection(@Argument page: PaginationInput?, @Argument where: ReferringOrganisationWhereInput?): Page<ReferringOrganisation> {
        val f: PaginationInput = page ?: PaginationInput()
        if (where == null) {
            return referringOrganisations.findAll(f.create())
        }
        return referringOrganisations.findAll(where.build(), f.create())
    }

    @QueryMapping
    fun referringOrganisationsPublic(@Argument where: ReferringOrganisationWhereInput ,@Argument orderBy: MutableList<KeyValuePair>?): List<ReferringOrganisationPublic> {
        return if (orderBy != null) {
            val sort: Sort = Sort.by(orderBy.map { Sort.Order(Sort.Direction.fromString(it.value), it.key) })
            referringOrganisations.findAll(where.build(), sort).map { ReferringOrganisationPublic(it.id, it.name) }.toList()
        } else {
            referringOrganisations.findAll(where.build()).map { ReferringOrganisationPublic(it.id, it.name) }.toList()
        }
    }

    @PreAuthorize("hasAnyAuthority('app:admin', 'read:organisations')")
    @QueryMapping
    fun referringOrganisation(@Argument where: ReferringOrganisationWhereInput): Optional<ReferringOrganisation> = referringOrganisations.findOne(where.build())
}

data class ReferringOrganisationPublic(
    val id: Long,
    val name: String
){
}