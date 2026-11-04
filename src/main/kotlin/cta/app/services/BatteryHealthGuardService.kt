package cta.app.services

import cta.app.FeatureFlagRepository
import cta.app.Kit
import cta.app.KitStatus
import cta.app.KitType
import mu.KotlinLogging
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

/**
 * Raised when a low-battery kit attempts a guarded transition while enforcement is on.
 * Surfaced to the client as a BAD_REQUEST GraphQL error.
 */
class BatteryHealthTooLowException(
    message: String,
) : RuntimeException(message)

/**
 * Battery-health guard (#264, planning sheet item #23). A battery-bearing kit whose battery
 * health is 50% or below must not move into an allocation status. "50% or below" matches the
 * dashboard's red colour band (item #24), so red and blocked mean the same thing.
 *
 * Scope:
 * - Types: laptops, tablets and smartphones only. Desktops and all-in-ones have no battery,
 *   so their reading is always null and the rule would trap them for good.
 * - A null reading on an in-scope type counts as blocked (item #24 shows it red). The
 *   collection script doesn't always capture battery (#65), so shadow mode logs
 *   `batteryHealth=null` separately; count those before turning enforcement on.
 * - Only the three ALLOCATION_* statuses. DISTRIBUTION_DELIVERED stays open on purpose
 *   (Kocycle devices, repair returns and the request automation all need to reach it), as do
 *   the recycle and repair-return exits.
 *
 * The 'battery-health-enforcement' feature flag means ENFORCE. Seeded off = shadow mode:
 * the guard always evaluates, WARN-logs would-blocks and allows. Shadow evaluation must
 * never fail the host mutation, so a failed flag read degrades to shadow.
 */
@Service
class BatteryHealthGuardService(
    private val featureFlags: FeatureFlagRepository,
) {
    fun checkStatusChange(
        kit: Kit,
        from: KitStatus,
        to: KitStatus,
        enforcementPoint: String,
    ) {
        if (from == to || to !in BLOCKED_TARGET_STATUSES || kit.type !in BATTERY_TYPES) return
        val health = kit.batteryHealth
        if (health != null && health > THRESHOLD_PERCENT) return

        if (isEnforcementEnabled()) {
            val reading = health?.let { "$it%" } ?: "no reading"
            throw BatteryHealthTooLowException(
                "Kit ${kit.id} cannot progress to $to: Battery health too low ($reading), please see guide for next steps",
            )
        }
        logger.warn {
            "battery-health would-block: kitId=${kit.id} batteryHealth=$health type=${kit.type} " +
                "transition=$from->$to enforcementPoint=$enforcementPoint (shadow mode: allowed)"
        }
    }

    private fun isEnforcementEnabled(): Boolean =
        try {
            featureFlags.findById(FLAG_KEY).map { it.enabled }.orElse(false)
        } catch (e: Exception) {
            logger.warn(e) { "battery-health enforcement flag read failed; defaulting to shadow mode" }
            false
        }

    companion object {
        const val FLAG_KEY = "battery-health-enforcement"
        const val THRESHOLD_PERCENT = 50

        val BATTERY_TYPES = setOf(KitType.LAPTOP, KitType.TABLET, KitType.SMARTPHONE)

        val BLOCKED_TARGET_STATUSES =
            setOf(
                KitStatus.ALLOCATION_READY,
                KitStatus.ALLOCATION_QC_COMPLETED,
                KitStatus.ALLOCATION_DELIVERY_ARRANGED,
            )
    }
}
