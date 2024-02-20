package ju.ma.app.graphql.queries

import com.coxautodev.graphql.tools.GraphQLQueryResolver
import ju.ma.app.ReferringOrganisation
import ju.ma.app.ReferringOrganisationRepository
import ju.ma.app.graphql.filters.ReferringOrganisationWhereInput
import ju.ma.graphql.KeyValuePair
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
    fun referringOrganisation(where: ReferringOrganisationWhereInput): Optional<ReferringOrganisation> = referringOrganisations.findOne(where.build())
}