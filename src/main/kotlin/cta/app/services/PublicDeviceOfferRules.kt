package cta.app.services

import cta.app.AdminConfig
import cta.app.AdminConfigRepository
import cta.app.DeviceRequestItems
import cta.app.FeatureFlagRepository
import mu.KotlinLogging
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

/** A public device request that falls outside what the public form offers; surfaced as BAD_REQUEST. */
class PublicDeviceOfferException(
    message: String,
) : RuntimeException(message)

/**
 * Server-side copy of the rules the dashboard's public device request form applies, so that the
 * offer cannot be widened by posting to /graphql directly. Mirrors techaid-dashboard
 * `org-request.ts`, `postcode-location-step.component.ts`, `shared/utils/boroughs.ts` and
 * `shared/services/borough-availability.service.ts` as of 2026-10-24. If the form's rules change,
 * this must change with them.
 *
 * 1. **Borough.** The form only confirms a postcode in a supported borough: Lambeth and Southwark
 *    always, Tower Hamlets while `tower-hamlets-borough-support` is on. (The list lives in the
 *    dashboard's boroughs.ts; it is duplicated here because the server has no copy of it.)
 * 2. **Device types.** The form offers only the types adminConfig's canPublicRequest* switches
 *    allow (never allInOnes or other). While `borough-availability-rules` is on, that list is
 *    narrowed to what the borough's group offers; a borough with no group record, or the flag
 *    off, is not narrowed.
 * 3. **Shape.** One main device from a radio list, plus optional SIM card and broadband hub
 *    check-boxes, each sent as quantity 1. At least one item.
 *
 * Staff (write:organisations or app:admin) get the dashboard's unrestricted admin device list,
 * so they are not checked here.
 */
@Service
class PublicDeviceOfferRules(
    private val featureFlags: FeatureFlagRepository,
    private val adminConfigs: AdminConfigRepository,
    private val boroughRules: BoroughAvailabilityRules,
    private val refereeRequestLimits: RefereeRequestLimitService,
) {
    fun checkUnlessStaff(
        borough: String?,
        items: DeviceRequestItems,
    ) {
        if (isStaff()) return

        val wanted = borough?.trim()?.lowercase()
        if (wanted.isNullOrEmpty() || wanted !in supportedBoroughs()) {
            throw PublicDeviceOfferException("We can't currently accept requests for clients in that borough.")
        }

        val requested = quantities(items).filterValues { it != 0 }
        if (requested.isEmpty()) throw PublicDeviceOfferException("Please select the item your client needs.")
        if (requested.values.any { it != 1 } || requested.keys.count { it in MAIN_DEVICES } > 1) {
            throw PublicDeviceOfferException(
                "Please select one device, plus a SIM card and/or broadband hub if your client needs them.",
            )
        }

        val available = offered(borough)
        if (!available.containsAll(requested.keys)) {
            throw PublicDeviceOfferException("One or more of the items you selected isn't currently available in $borough.")
        }
    }

    private fun supportedBoroughs(): Set<String> = if (flagOn(TOWER_HAMLETS_FLAG)) CORE_BOROUGHS + TOWER_HAMLETS else CORE_BOROUGHS

    /** adminConfig ∩ the borough's group offer (only while the rules flag is on and a group exists). */
    private fun offered(borough: String?): Set<String> {
        val config = adminConfigs.findById(1L).orElseGet { AdminConfig() }
        val permitted =
            buildSet {
                if (config.canPublicRequestLaptop) add("laptops")
                if (config.canPublicRequestDesktop) add("desktops")
                if (config.canPublicRequestTablet) add("tablets")
                if (config.canPublicRequestPhone) add("phones")
                if (config.canPublicRequestSIMCard) add("commsDevices")
                if (config.canPublicRequestBroadbandHub) add("broadbandHubs")
            }
        if (!boroughRules.enabled()) return permitted
        val group = refereeRequestLimits.groupFor(borough) ?: return permitted
        return permitted intersect group.offeredDeviceTypes().toSet()
    }

    private fun quantities(items: DeviceRequestItems): Map<String, Int> =
        mapOf(
            "phones" to (items.phones ?: 0),
            "tablets" to (items.tablets ?: 0),
            "laptops" to (items.laptops ?: 0),
            "allInOnes" to (items.allInOnes ?: 0),
            "desktops" to (items.desktops ?: 0),
            "other" to (items.other ?: 0),
            "commsDevices" to (items.commsDevices ?: 0),
            "broadbandHubs" to (items.broadbandHubs ?: 0),
        )

    /** Fails closed, as the dashboard does: an unreadable flag means Tower Hamlets is not offered. */
    private fun flagOn(key: String): Boolean =
        try {
            featureFlags.findById(key).map { it.enabled }.orElse(false)
        } catch (e: Exception) {
            logger.warn(e) { "$key flag read failed; treating as off" }
            false
        }

    private fun isStaff(): Boolean {
        val authorities = SecurityContextHolder.getContext().authentication?.authorities ?: return false
        return authorities.any { it.authority in STAFF_AUTHORITIES }
    }

    companion object {
        const val TOWER_HAMLETS_FLAG = "tower-hamlets-borough-support"
        private val CORE_BOROUGHS = setOf("lambeth", "southwark")
        private const val TOWER_HAMLETS = "tower hamlets"

        /** The radio choices that are devices; SIM card and hub can also be added via check-boxes. */
        private val MAIN_DEVICES = setOf("laptops", "desktops", "tablets", "phones")
        private val STAFF_AUTHORITIES = setOf("app:admin", "write:organisations")
    }
}
