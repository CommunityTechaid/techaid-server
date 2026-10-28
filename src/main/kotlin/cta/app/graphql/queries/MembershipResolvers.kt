package cta.app.graphql.queries

import cta.app.ReferringOrganisation
import cta.app.ReferringOrganisationContact
import cta.app.ReferringOrganisationRepository
import cta.app.services.Membership
import cta.app.services.MembershipAllowanceService
import cta.app.services.MembershipPublic
import org.springframework.graphql.data.method.annotation.BatchMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller

/**
 * Field resolvers for the membership types (#311).
 *
 * `ReferringOrganisation.membership` is selected per row in three paged lists (organisations,
 * referees, requests), so it is a `@BatchMapping`: one call per GraphQL level with every
 * organisation on the page, costing one grouped count per distinct membership period rather than
 * one count per row. See [MembershipAllowanceService.membershipsFor].
 *
 * With schema inspection disabled, a missing or mistyped mapping here is a silent null / runtime
 * error, not a boot failure — MembershipResolverWiringTest pins the coordinates.
 */
@Controller
class MembershipResolvers(
    private val allowance: MembershipAllowanceService,
    private val referringOrganisations: ReferringOrganisationRepository,
) {
    // No @PreAuthorize here on purpose. A batch loader runs outside the data fetcher's own
    // security interceptor, and ReferringOrganisation is reachable only through queries that are
    // already gated (the public types are flat and never link to an organisation).
    @BatchMapping(typeName = "ReferringOrganisation", field = "membership")
    fun membership(organisations: List<ReferringOrganisation>): Map<ReferringOrganisation, Membership> {
        val byId = allowance.membershipsFor(organisations)
        return organisations.associateWith { byId.getValue(it.id) }
    }

    @PreAuthorize("hasAnyAuthority('app:admin', 'read:organisations', 'write:organisations')")
    @SchemaMapping(typeName = "ReferringOrganisation", field = "membershipContacts")
    fun membershipContacts(organisation: ReferringOrganisation): List<ReferringOrganisationContact> =
        organisation.referringOrganisationContacts.filter { it.isMembershipContact && !it.archived }.sortedBy { it.id }

    /**
     * The anonymous allowance on a referee lookup result.
     *
     * Gated by construction, not by a check here: this field only resolves under
     * `referringOrganisationContactsPublic`, whose where-input REQUIRES an exact referee email, so
     * the caller has already proved they know one of the organisation's referees. There is no
     * public query that returns an allowance by organisation id or name.
     *
     * The source type is [Any] because two producers feed `ReferringOrganisationContactPublic`:
     * the lookup (a [ReferringOrganisationContactPublic] row) and the staff-only
     * `createReferringOrganisationContact`, which returns the entity itself.
     */
    @BatchMapping(typeName = "ReferringOrganisationContactPublic", field = "membershipAllowance")
    fun membershipAllowance(contacts: List<Any>): Map<Any, MembershipPublic> {
        val organisationIds =
            contacts.associateWith { contact ->
                when (contact) {
                    is ReferringOrganisationContactPublic -> contact.referringOrganisationId
                    is ReferringOrganisationContact -> contact.referringOrganisation.id
                    else -> error("Unexpected ReferringOrganisationContactPublic source: ${contact::class}")
                }
            }
        val organisations = referringOrganisations.findAllById(organisationIds.values.toSet()).associateBy { it.id }
        val config = allowance.currentConfig()
        val memberships = allowance.membershipsFor(organisations.values, config = config)
        return organisationIds
            .mapNotNull { (contact, orgId) ->
                memberships[orgId]?.let { contact to allowance.publicView(it, config) }
            }.toMap()
    }
}
