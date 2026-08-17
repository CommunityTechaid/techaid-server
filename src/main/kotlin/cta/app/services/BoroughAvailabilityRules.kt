package cta.app.services

import cta.app.FeatureFlagRepository
import mu.KotlinLogging
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

/**
 * Whether the borough configuration governs the PUBLIC device request journey.
 *
 * The admin side is not gated by this: `boroughGroups`, `referrerLimitExceptions` and
 * `saveBoroughAvailability` always work, so staff can build and review the borough x device-type
 * matrix — Tower Hamlets included — long before any of it is visible to a referrer. What this
 * flag decides is whether that configuration is allowed to change what a member of the public
 * sees or is permitted to do:
 *
 *  - [RefereeRequestLimitService] enforces the per-group cap only when this is on; off, it uses
 *    the global cap of 3 counted against the referee's total open requests, exactly as before the
 *    config existed.
 *  - `boroughAvailabilityPublic` returns an empty list when this is off, which the dashboard's
 *    BoroughAvailabilityService already reads as "no restriction recorded" and so offers every
 *    device type.
 *
 * It exists as its own bean rather than a constant in each consumer because two call sites now
 * read the same switch, and a flag key duplicated across files is a flag that eventually gets
 * turned half-on.
 *
 * Off is the safe default and the safe failure mode: a flag read that throws degrades to off,
 * which is the behaviour production has today, rather than silently applying configuration
 * nobody has verified.
 */
@Service
class BoroughAvailabilityRules(
    private val featureFlags: FeatureFlagRepository,
) {
    fun enabled(): Boolean =
        try {
            featureFlags.findById(FLAG_KEY).map { it.enabled }.orElse(false)
        } catch (e: Exception) {
            logger.warn(e) { "borough-availability-rules flag read failed; defaulting to off (pre-config behaviour)" }
            false
        }

    companion object {
        const val FLAG_KEY = "borough-availability-rules"
    }
}
