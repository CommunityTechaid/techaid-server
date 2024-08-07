package cta.app.graphql.queries

import com.coxautodev.graphql.tools.GraphQLQueryResolver
import cta.app.ReferringOrganisationContact
import cta.app.ReferringOrganisationContactRepository
import cta.app.graphql.filters.ReferringOrganisationContactWhereInput
import cta.graphql.KeyValuePair
import cta.graphql.PaginationInput
import org.springframework.data.domain.Page
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
    fun referringOrganisationContactsConnection(page: PaginationInput?, where: ReferringOrganisationContactWhereInput?): Page<ReferringOrganisationContact> {
        val f: PaginationInput = page ?: PaginationInput()
        if (where == null) {
            return referringOrganisationContacts.findAll(f.create())
        }
        return referringOrganisationContacts.findAll(where.build(), f.create())
    }

    fun referringOrganisationContactsPublic(
        where: ReferringOrganisationContactWhereInput,
        orderBy: MutableList<KeyValuePair>?
    ): List<Long> {
        return if (orderBy != null) {
            val sort: Sort = Sort.by(orderBy.map { Sort.Order(Sort.Direction.fromString(it.value), it.key) })
            referringOrganisationContacts.findAll(where.build(), sort).map { it.id }.toList()
        } else {
            referringOrganisationContacts.findAll(where.build()).map{ it.id }.toList()
        }
    }

    @PreAuthorize("hasAnyAuthority('app:admin', 'read:organisations')")
    fun referringOrganisationContact(where: ReferringOrganisationContactWhereInput): Optional<ReferringOrganisationContact> =
        referringOrganisationContacts.findOne(where.build())
}