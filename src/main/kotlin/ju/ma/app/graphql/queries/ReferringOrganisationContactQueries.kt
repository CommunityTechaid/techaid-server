package ju.ma.app.graphql.queries

import com.coxautodev.graphql.tools.GraphQLQueryResolver
import ju.ma.app.ReferringOrganisationContact
import ju.ma.app.ReferringOrganisationContactRepository
import ju.ma.app.graphql.filters.ReferringOrganisationContactWhereInput
import ju.ma.graphql.KeyValuePair
import org.springframework.data.domain.Sort
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Component
import java.util.Optional

@Component
class ReferringOrganisationContactQueries(
    private val referringOrganisationContacts: ReferringOrganisationContactRepository
) : GraphQLQueryResolver {

    @PreAuthorize("hasAnyAuthority('app:admin', 'read:organisations')")
    fun referringOrganisationContacts(
        where: ReferringOrganisationContactWhereInput,
        orderBy: MutableList<KeyValuePair>?
    ): List<ReferringOrganisationContact> {
        return if (orderBy != null) {
            val sort: Sort = Sort.by(orderBy.map { Sort.Order(Sort.Direction.fromString(it.value), it.key) })
            referringOrganisationContacts.findAll(where.build(), sort).toList()
        } else {
            referringOrganisationContacts.findAll(where.build()).toList()
        }
    }

    @PreAuthorize("hasAnyAuthority('app:admin', 'read:organisations')")
    fun referringOrganisationContact(where: ReferringOrganisationContactWhereInput): Optional<ReferringOrganisationContact> =
        referringOrganisationContacts.findOne(where.build())
}