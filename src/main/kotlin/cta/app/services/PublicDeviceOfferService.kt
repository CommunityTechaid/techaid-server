package cta.app.services

import cta.app.AdminConfig
import cta.app.AdminConfigRepository
import cta.app.DeviceRequestItems
import cta.app.DeviceType
import mu.KotlinLogging
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

/** A request asked for device types that are not currently on offer (#367). */
class DeviceTypeNotOfferedException(
    message: String,
) : RuntimeException(message) {
    companion object {
        const val CODE = "DEVICE_TYPE_NOT_OFFERED"
    }
}

/**
 * Server-side enforcement of the public device offer, so a stale form tab or a direct GraphQL
 * call cannot request a type the dashboard form would have hidden (#367).
 *
 * "Offered" is the same two layers the form applies, and only ever narrows:
 *
 *  1. The service-wide `AdminConfig.canPublicRequest*` switches. They cover the six types the
 *     form can show; all-in-ones and "other" have no switch and are not narrowed here.
 *  2. While [BoroughAvailabilityRules] is on, the request's borough group's
 *     [cta.app.BoroughGroup.offeredDeviceTypes] - the same value `boroughAvailabilityPublic`
 *     returns - found with the same case/whitespace-insensitive match
 *     ([RefereeRequestLimitService.groupFor]). A borough with no group is not narrowed, exactly
 *     as the form treats a borough it has no row for.
 *
 * Only types with a quantity above zero count. A failed read of either layer fails open (logged),
 * matching how the form treats a failed availability read, so a config fault cannot block every
 * referral.
 */
@Service
class PublicDeviceOfferService(
    private val adminConfigs: AdminConfigRepository,
    private val rules: BoroughAvailabilityRules,
    private val refereeLimits: RefereeRequestLimitService,
) {
    fun requireOffered(
        items: DeviceRequestItems,
        borough: String?,
    ) {
        val requested = requestedTypes(items)
        if (requested.isEmpty()) return

        val notOffered = requested.filter { it !in offeredKeys(requested, borough) }
        if (notOffered.isEmpty()) return

        // Sentence case: only the first name is capitalised (labels are stored lowercase).
        val names =
            notOffered.mapIndexed { i, key ->
                LABELS.getValue(key).let { if (i == 0) it.replaceFirstChar { c -> c.uppercase() } else it }
            }
        val list = if (names.size == 1) names[0] else names.dropLast(1).joinToString(", ") + " and " + names.last()
        val verb = "are"
        val where = borough?.trim()?.takeIf { it.isNotEmpty() }?.let { " in $it" } ?: ""
        throw DeviceTypeNotOfferedException("$list $verb not currently available$where. Please refresh the form.")
    }

    private fun requestedTypes(items: DeviceRequestItems): List<String> =
        listOf(
            DeviceType.PHONES to items.phones,
            DeviceType.TABLETS to items.tablets,
            DeviceType.LAPTOPS to items.laptops,
            DeviceType.ALL_IN_ONES to items.allInOnes,
            DeviceType.DESKTOPS to items.desktops,
            DeviceType.COMMS_DEVICES to items.commsDevices,
            DeviceType.BROADBAND_HUBS to items.broadbandHubs,
            DeviceType.OTHER to items.other,
        ).filter { (it.second ?: 0) > 0 }.map { it.first.key }

    /** Of [requested], the keys that pass both layers. */
    private fun offeredKeys(
        requested: List<String>,
        borough: String?,
    ): Set<String> {
        var offered = requested.toSet()

        val config = readConfig()
        if (config != null) offered = offered.filter { adminSwitch(config, it) }.toSet()

        if (rules.enabled()) {
            val group =
                try {
                    refereeLimits.groupFor(borough)
                } catch (e: Exception) {
                    logger.warn(e) { "Device offer: group lookup failed for '$borough'; not applying the borough matrix." }
                    null
                }
            if (group != null) offered = offered.intersect(group.offeredDeviceTypes().toSet())
        }
        return offered
    }

    private fun readConfig(): AdminConfig? =
        try {
            adminConfigs.getAdminConfig()
        } catch (e: Exception) {
            logger.warn(e) { "Device offer: admin config read failed; not applying the service-wide switches." }
            null
        }

    private fun adminSwitch(
        config: AdminConfig,
        key: String,
    ): Boolean =
        when (key) {
            DeviceType.PHONES.key -> config.canPublicRequestPhone
            DeviceType.TABLETS.key -> config.canPublicRequestTablet
            DeviceType.LAPTOPS.key -> config.canPublicRequestLaptop
            DeviceType.DESKTOPS.key -> config.canPublicRequestDesktop
            DeviceType.COMMS_DEVICES.key -> config.canPublicRequestSIMCard
            DeviceType.BROADBAND_HUBS.key -> config.canPublicRequestBroadbandHub
            else -> true // all-in-ones and other have no service-wide switch
        }

    private companion object {
        val LABELS =
            mapOf(
                DeviceType.PHONES.key to "phones",
                DeviceType.TABLETS.key to "tablets",
                DeviceType.LAPTOPS.key to "laptops",
                DeviceType.ALL_IN_ONES.key to "all-in-ones",
                DeviceType.DESKTOPS.key to "desktops",
                DeviceType.COMMS_DEVICES.key to "SIM cards",
                DeviceType.BROADBAND_HUBS.key to "broadband hubs",
                DeviceType.OTHER.key to "other devices",
            )
    }
}
