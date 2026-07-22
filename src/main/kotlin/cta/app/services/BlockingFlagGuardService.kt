package cta.app.services

import cta.app.FeatureFlagRepository
import cta.app.Kit
import cta.app.KitStatus
import cta.app.KitSubStatus
import mu.KotlinLogging
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

/**
 * Raised when a kit carrying a blocking sub-status flag attempts a guarded transition —
 * or an assignment — while enforcement is on. Surfaced to the client as a BAD_REQUEST
 * GraphQL error (see ControllerExceptionHandler) so the bench UIs inherit the rejection.
 */
class BlockingFlagSetException(
    message: String,
) : RuntimeException(message)

/**
 * Blocking-flag sub-status guard (#90). A kit flagged wipeFailed,
 * installationOfOSFailed, needsFurtherInvestigation, needsSparePart or lockedToUser must
 * not be advanced into an allocation/delivered status — nor assigned to a device request.
 * The rule existed only as a client-side convention (kit-info's disabledStatusGroup);
 * updateKits and any direct API caller bypassed it entirely.
 *
 * The 'blocking-flag-enforcement' feature flag means ENFORCE. Seeded off = shadow mode:
 * the guard always evaluates, WARN-logs would-blocks (kit id, flags, transition,
 * enforcement point — watched in App Insights alongside the wipe-cert guard's line), and
 * allows. Shadow evaluation must never fail the host mutation, so any unexpected error
 * degrades to allow-and-log.
 *
 * The blocked set is kit-info's four statuses, held explicitly rather than as an ordinal
 * comparison so the DISTRIBUTION_RECYCLED / DISTRIBUTION_REPAIR_RETURN exits stay open —
 * a failed-wipe or spare-part kit is precisely the one that gets recycled or returned.
 */
@Service
class BlockingFlagGuardService(
    private val featureFlags: FeatureFlagRepository,
) {
    fun checkStatusChange(
        kit: Kit,
        from: KitStatus,
        to: KitStatus,
        enforcementPoint: String,
    ) {
        // Full-replace updates resend the current status; only a real transition into a
        // guarded status is checked, so flagged kits that already progressed stay editable.
        if (from == to || to !in BLOCKED_TARGET_STATUSES) return
        check(kit, action = "progress from $from to $to", detail = "transition=$from->$to", enforcementPoint = enforcementPoint)
    }

    fun checkAssignment(
        kit: Kit,
        enforcementPoint: String,
    ) {
        check(kit, action = "be assigned to a device request", detail = "transition=assignment", enforcementPoint = enforcementPoint)
    }

    private fun check(
        kit: Kit,
        action: String,
        detail: String,
        enforcementPoint: String,
    ) {
        val flags = blockingFlagsOf(kit.subStatus)
        if (flags.isEmpty()) return

        if (isEnforcementEnabled()) {
            throw BlockingFlagSetException(
                "Kit ${kit.id} cannot $action: blocking flags set (${flags.joinToString(", ")})",
            )
        }
        logger.warn {
            "blocking-flag would-block: kitId=${kit.id} flags=${flags.joinToString(",")} $detail " +
                "enforcementPoint=$enforcementPoint (shadow mode: allowed)"
        }
    }

    // The flag read is the only I/O the guard performs; if it fails, degrade to shadow
    // so a guard-internal error can never fail the host mutation.
    private fun isEnforcementEnabled(): Boolean =
        try {
            featureFlags.findById(FLAG_KEY).map { it.enabled }.orElse(false)
        } catch (e: Exception) {
            logger.warn(e) { "blocking-flag enforcement flag read failed; defaulting to shadow mode" }
            false
        }

    companion object {
        const val FLAG_KEY = "blocking-flag-enforcement"

        // Ordered so the logged flags= token is stable across runs (App Insights grouping).
        fun blockingFlagsOf(subStatus: KitSubStatus): List<String> =
            buildList {
                if (subStatus.wipeFailed == true) add("wipeFailed")
                if (subStatus.installationOfOSFailed == true) add("installationOfOSFailed")
                if (subStatus.needsFurtherInvestigation == true) add("needsFurtherInvestigation")
                if (subStatus.needsSparePart == true) add("needsSparePart")
                if (subStatus.lockedToUser == true) add("lockedToUser")
            }

        val BLOCKED_TARGET_STATUSES =
            setOf(
                KitStatus.ALLOCATION_DELIVERY_ARRANGED,
                KitStatus.ALLOCATION_QC_COMPLETED,
                KitStatus.ALLOCATION_READY,
                KitStatus.DISTRIBUTION_DELIVERED,
            )
    }
}
