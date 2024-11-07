package cta.app.graphql.queries

import cta.app.ReferringOrganisationContact
import cta.app.ReferringOrganisationContactRepository
import cta.app.graphql.filters.ReferringOrganisationContactPublicWhereInput
import cta.app.graphql.filters.ReferringOrganisationContactWhereInput
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
class ReferringOrganisationContactQueries(
    private val referringOrganisationContacts: ReferringOrganisationContactRepository
)  {

    @PreAuthorize("hasAnyAuthority('app:admin', 'read:organisations')")
    @QueryMapping
    fun referringOrganisationContacts(
        @Argument where: ReferringOrganisationContactWhereInput,
        @Argument orderBy: MutableList<KeyValuePair>?
    ): List<ReferringOrganisationContact> {
        return if (orderBy != null) {
            val sort: Sort = Sort.by(orderBy.map { Sort.Order(Sort.Direction.fromString(it.value), it.key) })
            referringOrganisationContacts.findAll(where.build(), sort).toList()
        } else {
            referringOrganisationContacts.findAll(where.build()).toList()
        }
    }

    @PreAuthorize("hasAnyAuthority('app:admin', 'read:organisations')")
    @QueryMapping
    fun referringOrganisationContactsConnection(@Argument page: PaginationInput?, @Argument where: ReferringOrganisationContactWhereInput?): Page<ReferringOrganisationContact> {
        val f: PaginationInput = page ?: PaginationInput()
        if (where == null) {
            return referringOrganisationContacts.findAll(f.create())
        }
        return referringOrganisationContacts.findAll(where.build(), f.create())
    }

    @QueryMapping
    fun referringOrganisationContactsPublic(
        @Argument where: ReferringOrganisationContactPublicWhereInput,
        @Argument orderBy: MutableList<KeyValuePair>?
    ): List<ReferringOrganisationContactPublic> {
        return if (orderBy != null) {
            val sort: Sort = Sort.by(orderBy.map { Sort.Order(Sort.Direction.fromString(it.value), it.key) })
            referringOrganisationContacts.findAll(where.build(), sort).map { ReferringOrganisationContactPublic(it.id, it.fullName) }.toList()
        } else {
            referringOrganisationContacts.findAll(where.build()).map { ReferringOrganisationContactPublic(it.id, it.fullName) }.toList()
        }
    }

    @PreAuthorize("hasAnyAuthority('app:admin', 'read:organisations')")
    @QueryMapping
    fun referringOrganisationContact(@Argument where: ReferringOrganisationContactWhereInput): Optional<ReferringOrganisationContact> =
        referringOrganisationContacts.findOne(where.build())
}

data class ReferringOrganisationContactPublic(
    val id: Long,
    val fullName: String? = null
){}