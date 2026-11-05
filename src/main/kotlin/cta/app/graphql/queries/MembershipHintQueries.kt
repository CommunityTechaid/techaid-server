package cta.app.graphql.queries

import cta.app.ReferringOrganisationRepository
import cta.app.services.Membership
import cta.app.services.MembershipAllowanceService
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller

class MembershipContactHint(
    val id: Long,
    val fullName: String,
)

class ReferringOrganisationMembershipHint(
    val id: Long,
    val name: String,
    val membership: Membership,
    val membershipContacts: List<MembershipContactHint>,
)

/**
 * #345: the staff request form's allowance hint, readable with `write:requests` (the authority that
 * lets staff raise a request for an organisation) without `read:organisations`. Returns only what
 * the hint displays, never the full organisation record.
 */
@Controller
class MembershipHintQueries(
    private val allowance: MembershipAllowanceService,
    private val referringOrganisations: ReferringOrganisationRepository,
) {
    @PreAuthorize("hasAnyAuthority('app:admin', 'read:organisations', 'write:requests')")
    @QueryMapping
    fun referringOrganisationMembershipHint(
        @Argument id: Long,
    ): ReferringOrganisationMembershipHint? {
        val org = referringOrganisations.findById(id).orElse(null) ?: return null
        return ReferringOrganisationMembershipHint(
            id = org.id,
            name = org.name,
            membership = allowance.membershipFor(org),
            membershipContacts =
                org.referringOrganisationContacts
                    .filter { it.isMembershipContact && !it.archived }
                    .sortedBy { it.id }
                    .map { MembershipContactHint(it.id, it.fullName) },
        )
    }
}
