package cta.app.graphql.queries

import cta.app.ReferringOrganisationContact
import cta.app.ReferringOrganisationContactRepository
import cta.app.services.RefereeRequestLimitService
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.stereotype.Controller

/** What an anonymous caller may learn about a referee's cap: the verdict and the limit, never the open count. */
data class RequestLimitStatus(
    val atLimit: Boolean,
    val limit: Int,
)

/**
 * `ReferringOrganisationContactPublic.requestLimitStatus` (#336).
 *
 * Reachable anonymously exactly like `membershipAllowance`: no @PreAuthorize here, because the
 * field only resolves under `referringOrganisationContactsPublic`, whose where-input requires an
 * exact referee email. It delegates to [RefereeRequestLimitService.resolve], the same call (and so
 * the same open-request definition, group limit, org exception and flag-off fallback) that
 * `createDeviceRequest` enforces, so the form and the submit-time check cannot disagree.
 */
@Controller
class RequestLimitResolvers(
    private val contacts: ReferringOrganisationContactRepository,
    private val limits: RefereeRequestLimitService,
) {
    @SchemaMapping(typeName = "ReferringOrganisationContactPublic", field = "requestLimitStatus")
    fun requestLimitStatus(
        source: Any,
        @Argument borough: String?,
    ): RequestLimitStatus? {
        val contactId =
            when (source) {
                is ReferringOrganisationContactPublic -> source.id
                is ReferringOrganisationContact -> source.id
                else -> error("Unexpected ReferringOrganisationContactPublic source: ${source::class}")
            }
        val contact = contacts.findById(contactId).orElse(null) ?: return null
        val resolved = limits.resolve(contact, borough)
        return RequestLimitStatus(atLimit = resolved.exceeded, limit = resolved.limit)
    }
}
