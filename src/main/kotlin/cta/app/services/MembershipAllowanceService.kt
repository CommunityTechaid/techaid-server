package cta.app.services

import cta.app.AdminConfig
import cta.app.AdminConfigRepository
import cta.app.DeviceRequestRepository
import cta.app.FeatureFlagRepository
import cta.app.MEMBERSHIP_NON_COUNTING_STATUSES
import cta.app.MembershipOverLimitMode
import cta.app.MembershipPeriod
import cta.app.MembershipPeriods
import cta.app.MembershipState
import cta.app.MembershipTier
import cta.app.ReferringOrganisation
import mu.KotlinLogging
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.stereotype.Service
import java.time.LocalDate

private val logger = KotlinLogging.logger {}

/** Staff-facing allowance for one organisation: the GraphQL `Membership` type. */
data class Membership(
    val tier: MembershipTier,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    /** Null for an uncapped tier. */
    val cap: Int?,
    val used: Int,
    /** Null for an uncapped tier; never negative. */
    val remaining: Int?,
    val state: MembershipState,
    /** Requests accepted past the cap this period; always 0 unless the over-limit mode is GRACE. */
    val graceUsed: Int,
)

/**
 * What an anonymous caller may see once they have identified themselves with a referee's email:
 * the GraphQL `MembershipPublic` type. No contact names, no organisation id.
 */
data class MembershipPublic(
    val tier: MembershipTier,
    val state: MembershipState,
    val cap: Int?,
    val used: Int,
    val remaining: Int?,
    val periodEnd: LocalDate,
    val graceUsed: Int,
    /** Grace requests still available; 0 in BLOCK mode and for an uncapped tier. */
    val graceRemaining: Int,
)

/**
 * Refusal of `createDeviceRequest` because the organisation's membership allowance is used up and
 * no grace requests remain. A separate type from `ExceededDeviceRequestLimitException` (the
 * per-referee open-request cap) so the two can never be confused; the message always starts with
 * [MESSAGE_PREFIX] and the GraphQL error carries `extensions.code = "MEMBERSHIP_LIMIT_REACHED"`
 * (see ControllerExceptionHandler) so the dashboard need not match wording.
 */
class MembershipLimitReachedException(
    message: String,
) : RuntimeException(message) {
    companion object {
        const val MESSAGE_PREFIX = "Membership allowance reached:"
        const val CODE = "MEMBERSHIP_LIMIT_REACHED"
    }
}

/**
 * Yearly request allowance per organisation (#311), the sibling of [RefereeRequestLimitService].
 *
 * The two limits are independent and both apply: the per-referee open-request cap bounds how many
 * requests one person has in flight; this bounds how many requests an organisation may make in a
 * membership year, however many referees it has.
 *
 * Nothing is stored beyond the tier and the period's start date. The current period and the usage
 * are computed on read, so renewal needs no job (the app is scaled to zero outside office hours,
 * and a missed job would silently leave an organisation capped).
 *
 * **Enforcement is gated by the `membership-support` feature flag.** Every existing organisation
 * was defaulted to FREE (cap 10) by the migration; enforcing before staff have reviewed tiers
 * would start refusing established organisations the moment this deploys. Reading allowances is
 * not gated — it is harmless and the dashboard only asks once the flag is on.
 */
@Service
class MembershipAllowanceService(
    private val deviceRequests: DeviceRequestRepository,
    private val adminConfigs: AdminConfigRepository,
    private val featureFlags: FeatureFlagRepository,
) {
    fun enforcementEnabled(): Boolean =
        try {
            featureFlags.findById(FLAG_KEY).map { it.enabled }.orElse(false)
        } catch (e: Exception) {
            logger.warn(e) { "membership-support flag read failed; defaulting to off (no enforcement)" }
            false
        }

    /**
     * The over-limit settings. Falls back to the defaults (GRACE, 2) when there is no `admin_config`
     * row: nothing in Flyway seeds it (the row is created by hand per environment), and
     * `getAdminConfig` throws on an absent row. An absent row must not turn into a 500 on the
     * public createDeviceRequest path.
     */
    fun currentConfig(): AdminConfig =
        try {
            adminConfigs.getAdminConfig()
        } catch (e: EmptyResultDataAccessException) {
            AdminConfig()
        }

    /**
     * Allowances for many organisations at once, keyed by organisation id. Costs one grouped count
     * per DISTINCT period among the organisations (nearly all share the 1 February default), not
     * one per organisation — this is what keeps the three paged lists cheap.
     */
    fun membershipsFor(
        organisations: Collection<ReferringOrganisation>,
        today: LocalDate = MembershipPeriods.today(),
        config: AdminConfig = currentConfig(),
    ): Map<Long, Membership> {
        if (organisations.isEmpty()) return emptyMap()

        val byPeriod: Map<MembershipPeriod, List<ReferringOrganisation>> =
            organisations.distinctBy { it.id }.groupBy { MembershipPeriods.periodFor(it.membershipStartDate, today) }

        val result = HashMap<Long, Membership>()
        byPeriod.forEach { (period, orgs) ->
            val used =
                deviceRequests
                    .countMembershipUsage(
                        organisationIds = orgs.map { it.id },
                        excluded = MEMBERSHIP_NON_COUNTING_STATUSES,
                        from = period.startInstant,
                        to = period.endExclusiveInstant,
                    ).associate { it.organisationId to it.used.toInt() }
            orgs.forEach { org ->
                result[org.id] = compute(org.membershipTier, period, used[org.id] ?: 0, config.membershipOverLimitMode)
            }
        }
        return result
    }

    fun membershipFor(
        organisation: ReferringOrganisation,
        today: LocalDate = MembershipPeriods.today(),
        config: AdminConfig = currentConfig(),
    ): Membership = membershipsFor(listOf(organisation), today, config).getValue(organisation.id)

    /** The anonymous view of [membership]: adds how many grace requests remain, drops nothing sensitive. */
    fun publicView(
        membership: Membership,
        config: AdminConfig = currentConfig(),
    ): MembershipPublic =
        MembershipPublic(
            tier = membership.tier,
            state = membership.state,
            cap = membership.cap,
            used = membership.used,
            remaining = membership.remaining,
            periodEnd = membership.periodEnd,
            graceUsed = membership.graceUsed,
            graceRemaining = graceRemaining(membership, config),
        )

    /**
     * Decides whether a new request may be created for [organisation], and whether it is a grace
     * request. Returns the 1-based grace index to stamp on the request, or null when it is within
     * the allowance (or enforcement is off). Throws [MembershipLimitReachedException] to refuse.
     *
     * Run AFTER the per-referee cap check in createDeviceRequest, so the existing referee-cap
     * behaviour and message are unchanged when both would fail.
     *
     * [bypass] is the seam for the unconfirmed "staff may override the limit on admin-created
     * requests" question. Nothing passes true yet; it exists so that decision, when made, is a
     * one-line change at the call site rather than a redesign.
     */
    fun checkNewRequest(
        organisation: ReferringOrganisation,
        bypass: Boolean = false,
        today: LocalDate = MembershipPeriods.today(),
    ): Int? {
        if (bypass || !enforcementEnabled()) return null

        val config = currentConfig()
        val membership = membershipFor(organisation, today, config)
        val cap = membership.cap ?: return null
        if (membership.used < cap) return null

        if (graceRemaining(membership, config) > 0) return membership.graceUsed + 1

        throw MembershipLimitReachedException(
            "${MembershipLimitReachedException.MESSAGE_PREFIX} your organisation has used all $cap requests " +
                "for the membership year ending ${membership.periodEnd}. " +
                "Please contact Community TechAid about upgrading your membership.",
        )
    }

    private fun graceRemaining(
        membership: Membership,
        config: AdminConfig,
    ): Int {
        if (membership.cap == null || config.membershipOverLimitMode != MembershipOverLimitMode.GRACE) return 0
        return (config.membershipGraceRequests - membership.graceUsed).coerceAtLeast(0)
    }

    companion object {
        const val FLAG_KEY = "membership-support"

        /**
         * Pure derivation of an allowance from its inputs. `graceUsed` is derived (requests beyond
         * the cap) rather than counted from `membership_grace_index`, so it cannot drift: a declined
         * grace request frees its slot and the next one reuses the same index.
         */
        fun compute(
            tier: MembershipTier,
            period: MembershipPeriod,
            used: Int,
            mode: MembershipOverLimitMode,
        ): Membership {
            val cap = tier.cap
            val remaining = cap?.let { it - used }
            val state =
                when {
                    remaining == null -> MembershipState.OK
                    remaining <= 0 -> MembershipState.LIMIT_REACHED
                    remaining == 1 -> MembershipState.LAST_REQUEST
                    else -> MembershipState.OK
                }
            val graceUsed = if (cap != null && mode == MembershipOverLimitMode.GRACE) (used - cap).coerceAtLeast(0) else 0
            return Membership(
                tier = tier,
                periodStart = period.start,
                periodEnd = period.end,
                cap = cap,
                used = used,
                remaining = remaining?.coerceAtLeast(0),
                state = state,
                graceUsed = graceUsed,
            )
        }
    }
}
