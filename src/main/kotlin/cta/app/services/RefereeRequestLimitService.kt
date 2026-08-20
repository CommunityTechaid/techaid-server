package cta.app.services

import cta.app.BoroughGroup
import cta.app.BoroughGroupRepository
import cta.app.CLOSED_REQUEST_STATUSES
import cta.app.DeviceRequestRepository
import cta.app.ReferrerLimitExceptionRepository
import cta.app.ReferringOrganisationContact
import org.springframework.stereotype.Service

/**
 * The default cap, used where no borough group governs the request.
 *
 * This is the number the whole service ran on before limits became per-group: three open requests
 * per referee, everywhere. It stays as the fallback so a request that cannot be attributed to a
 * group — a blank borough on a legacy record, or a borough nobody has configured yet — is capped
 * exactly as it is today rather than being waved through.
 */
const val DEFAULT_REQUEST_LIMIT = 3

/**
 * How many open requests a referee may hold, and how many they currently have.
 *
 * [scope] names what the numbers are about ("Tower Hamlets", or "overall" for the fallback) so the
 * rejection message can tell a referrer *which* limit they hit. With per-group limits, "you have 3
 * requests open" is no longer enough information to act on.
 */
data class RefereeRequestLimit(
    val limit: Int,
    val open: Long,
    val scope: String,
) {
    val exceeded: Boolean
        get() = open >= limit
}

/**
 * Resolves the per-referee open-request cap for a device request.
 *
 * Two things moved when limits became per-group, and they have to move together:
 *
 * 1. **The limit** comes from the borough group governing the request's borough, with an
 *    organisation-level exception taking precedence over the group's own value.
 * 2. **The count** is scoped to that same group's boroughs. Leaving the count global while the
 *    limit went per-group would have made Tower Hamlets' limit of 1 unreachable in practice — any
 *    referrer with a single open Lambeth request would have been refused, and the pilot would have
 *    rejected almost everyone on day one.
 *
 * A consequence worth being explicit about: because each group is counted separately, a referrer's
 * total across all groups can exceed any single limit. Three open Lambeth requests plus one Tower
 * Hamlets request is four in total and is allowed, because it is within both caps. That is what
 * "a borough's rules are one horizontal read" means — it is the intent of per-group limits, not a
 * hole in them.
 *
 * See dashboard #179.
 */
@Service
class RefereeRequestLimitService(
    private val boroughGroups: BoroughGroupRepository,
    private val limitExceptions: ReferrerLimitExceptionRepository,
    private val deviceRequests: DeviceRequestRepository,
    private val rules: BoroughAvailabilityRules,
) {
    fun resolve(
        contact: ReferringOrganisationContact,
        borough: String?,
    ): RefereeRequestLimit {
        // Off, the borough configuration must not reach a referrer at all — not the group's
        // number and not its scoped count. Both have to fall back together: keeping the global
        // cap but counting per group, or vice versa, is a third behaviour that has never run
        // anywhere and is not what turning this off is meant to restore.
        if (!rules.enabled()) return globalLimit(contact)

        val group = groupFor(borough) ?: return globalLimit(contact)

        val exception =
            limitExceptions.findByReferringOrganisationIdAndGroupId(
                contact.referringOrganisation.id,
                group.id,
            )

        return RefereeRequestLimit(
            limit = exception?.maxPerReferee ?: group.maxPerReferee,
            open =
                deviceRequests.countOpenForContactInBoroughs(
                    contactId = contact.id,
                    closedStatuses = CLOSED_REQUEST_STATUSES,
                    // Normalised to match how groupFor resolves the group. Without this the two
                    // halves disagree: a request stored as "lambeth" resolves TO the group but is
                    // not counted BY it, so the referee silently gets extra headroom.
                    boroughs = group.boroughs.map { it.trim().lowercase() },
                ),
            scope = group.name,
        )
    }

    /**
     * The cap as it stood before borough configuration existed: one number for everyone, checked
     * against the referee's total open requests wherever they are.
     */
    private fun globalLimit(contact: ReferringOrganisationContact) =
        RefereeRequestLimit(
            limit = DEFAULT_REQUEST_LIMIT,
            open = contact.requestCount.toLong(),
            scope = "overall",
        )

    /**
     * The group governing a borough, if any.
     *
     * Matched case- and whitespace-insensitively because the borough on a request is a string that
     * has arrived from several places over the years — the old iframe lookup, the new postcode
     * table, and hand entry. A capitalisation difference must not silently drop a referrer into
     * the fallback cap.
     *
     * Internal (not private): DeliveryMutations' borough-availability check reuses this exact
     * matching rather than writing a second one.
     */
    internal fun groupFor(borough: String?): BoroughGroup? {
        val wanted = borough?.trim()?.lowercase()
        if (wanted.isNullOrEmpty()) return null
        return boroughGroups.findAll().firstOrNull { group ->
            group.boroughs.any { it.trim().lowercase() == wanted }
        }
    }
}
