package cta.app.services

import cta.app.FeatureFlagRepository
import cta.app.Kit
import cta.app.KitStatus
import cta.app.KitType
import mu.KotlinLogging
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

/**
 * Raised when a drive-bearing kit without a wipe certificate or exemption attempts a
 * guarded transition while enforcement is on. Surfaced to the client as a BAD_REQUEST
 * GraphQL error (see ControllerExceptionHandler) so the scan UIs inherit the rejection.
 */
class WipeCertMissingException(
    message: String,
) : RuntimeException(message)

/**
 * Wipe-cert status-progression guard (#68). Drive-bearing kits (laptop/desktop/all-in-one)
 * must not advance beyond PROCESSING_WIPED — or be assigned to a device request — unless a
 * wipe certificate reference is recorded or an admin exemption (e.g. NO_DRIVE) is set.
 *
 * The 'wipe-cert-enforcement' feature flag means ENFORCE. Seeded off = shadow mode: the
 * guard always evaluates, WARN-logs would-blocks (kit id, transition, enforcement point —
 * watched in App Insights), and allows. Shadow evaluation must never fail the host
 * mutation, so any unexpected error degrades to allow-and-log.
 *
 * The exit statuses DISTRIBUTION_RECYCLED / DISTRIBUTION_REPAIR_RETURN are exempt: the
 * control protects client-bound devices; recycling or returning for repair must not
 * require a cert. An explicit blocked set is used instead of an ordinal comparison so
 * those exits stay open (KitStatus ordinal order would wrongly catch them).
 */
@Service
class WipeCertGuardService(
    private val featureFlags: FeatureFlagRepository,
) {
    fun checkStatusChange(
        kit: Kit,
        from: KitStatus,
        to: KitStatus,
        enforcementPoint: String,
    ) {
        // Full-replace updates resend the current status; only a real transition into a
        // guarded status is checked, so certless kits that already progressed stay editable.
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
        if (kit.type !in DRIVE_BEARING_TYPES) return
        if (!kit.wipeCertReference.isNullOrBlank()) return
        if (kit.wipeCertExemption != null) return

        if (isEnforcementEnabled()) {
            throw WipeCertMissingException(
                "Kit ${kit.id} cannot $action: no wipe certificate recorded and no exemption set",
            )
        }
        logger.warn {
            "wipe-cert would-block: kitId=${kit.id} type=${kit.type} $detail " +
                "enforcementPoint=$enforcementPoint (shadow mode: allowed)"
        }
    }

    // The flag read is the only I/O the guard performs; if it fails, degrade to shadow
    // so a guard-internal error can never fail the host mutation.
    private fun isEnforcementEnabled(): Boolean =
        try {
            featureFlags.findById(FLAG_KEY).map { it.enabled }.orElse(false)
        } catch (e: Exception) {
            logger.warn(e) { "wipe-cert enforcement flag read failed; defaulting to shadow mode" }
            false
        }

    companion object {
        const val FLAG_KEY = "wipe-cert-enforcement"

        val DRIVE_BEARING_TYPES = setOf(KitType.LAPTOP, KitType.DESKTOP, KitType.ALLINONE)

        val BLOCKED_TARGET_STATUSES =
            setOf(
                KitStatus.PROCESSING_OS_INSTALLED,
                KitStatus.PROCESSING_STORED,
                KitStatus.ALLOCATION_READY,
                KitStatus.ALLOCATION_QC_COMPLETED,
                KitStatus.ALLOCATION_DELIVERY_ARRANGED,
                KitStatus.DISTRIBUTION_DELIVERED,
            )
    }
}
