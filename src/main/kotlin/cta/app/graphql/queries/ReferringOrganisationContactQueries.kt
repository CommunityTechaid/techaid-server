package cta.app.graphql.queries

import cta.app.ReferringOrganisationContact
import cta.app.ReferringOrganisationContactNote
import cta.app.ReferringOrganisationContactRepository
import cta.app.graphql.filters.ReferringOrganisationContactPublicWhereInput
import cta.app.graphql.filters.ReferringOrganisationContactWhereInput
import cta.graphql.KeyValuePair
import cta.graphql.PaginationInput
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import java.util.Optional

@Controller
class ReferringOrganisationContactQueries(
    private val referringOrganisationContacts: ReferringOrganisationContactRepository,
) {
    @PreAuthorize("hasAnyAuthority('app:admin', 'read:organisations')")
    @QueryMapping
    fun referringOrganisationContacts(
        @Argument where: ReferringOrganisationContactWhereInput,
        @Argument orderBy: MutableList<KeyValuePair>?,
    ): List<ReferringOrganisationContact> =
        if (orderBy != null) {
            val sort: Sort = Sort.by(orderBy.map { Sort.Order(Sort.Direction.fromString(it.value), it.key) })
            referringOrganisationContacts.findAll(where.build(), sort).toList()
        } else {
            referringOrganisationContacts.findAll(where.build()).toList()
        }

    @PreAuthorize("hasAnyAuthority('app:admin', 'read:organisations')")
    @QueryMapping
    fun referringOrganisationContactsConnection(
        @Argument page: PaginationInput?,
        @Argument where: ReferringOrganisationContactWhereInput?,
    ): Page<ReferringOrganisationContact> {
        val f: PaginationInput = page ?: PaginationInput()
        if (where == null) {
            return referringOrganisationContacts.findAll(f.create())
        }
        return referringOrganisationContacts.findAll(where.build(), f.create())
    }

    /**
     * Anonymous "is this address already registered" lookup, used by the public referral form.
     *
     * Hard-capped: the filter now requires an exact email
     * ([cta.graphql.ExactTextComparison]), so a bulk read should be impossible, and the page
     * size is the second line of defence. The dashboard branches on 0 / 1 / many rows, so the
     * cap has to stay comfortably above the number of contacts one address can legitimately
     * have rather than being 1 - otherwise a genuine duplicate is silently auto-selected
     * instead of being offered as a choice.
     */
    @QueryMapping
    fun referringOrganisationContactsPublic(
        @Argument where: ReferringOrganisationContactPublicWhereInput,
        @Argument orderBy: MutableList<KeyValuePair>?,
    ): List<ReferringOrganisationContactPublic> {
        val sort: Sort =
            orderBy
                ?.let { Sort.by(it.map { pair -> Sort.Order(Sort.Direction.fromString(pair.value), pair.key) }) }
                ?: Sort.unsorted()
        return referringOrganisationContacts
            .findAll(where.build(), PageRequest.of(0, MAX_PUBLIC_RESULTS, sort))
            .content
            .map { ReferringOrganisationContactPublic(it.id, it.fullName) }
    }

    @PreAuthorize("hasAnyAuthority('app:admin', 'read:organisations')")
    @QueryMapping
    fun referringOrganisationContact(
        @Argument where: ReferringOrganisationContactWhereInput,
    ): Optional<ReferringOrganisationContact> = referringOrganisationContacts.findOne(where.build())

    @SchemaMapping(typeName = "ReferringOrganisationContact", field = "notes")
    fun notes(contact: ReferringOrganisationContact): Set<ReferringOrganisationContactNote> = contact.referringOrganisationContactNotes

    companion object {
        /**
         * Most rows an anonymous caller can get back from one lookup.
         */
        const val MAX_PUBLIC_RESULTS: Int = 10
    }
}

data class ReferringOrganisationContactPublic(
    val id: Long,
    val fullName: String? = null,
)
