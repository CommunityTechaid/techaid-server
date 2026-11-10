package cta.app.services

import cta.app.AdminConfig
import cta.app.AdminSettingChange
import cta.app.AdminSettingChangeRepository
import cta.app.BoroughGroup
import cta.app.DeliveryBlockedDate
import cta.app.DeliveryConfig
import cta.app.DeliveryDayBorough
import cta.app.DeliveryWindow
import cta.app.ReferrerLimitException
import cta.app.SettingChangeSource
import cta.app.SettingChangeType
import cta.app.SettingType
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Records admin-panel setting changes (#327) as a semantic diff of two snapshots.
 *
 * A snapshot maps (settingType, settingKey, field) to an encoded value. Callers take one before a
 * change and one after, in the same transaction; [record] writes a row for every entry whose value
 * differs and nothing for entries that are equal, so a no-op save (including the borough screen's
 * delete-and-reinsert of identical exceptions) writes zero rows.
 *
 * - An entry only in the "after" snapshot is CREATED (old null); only in "before" is DELETED.
 * - Encoding ([encode]): booleans `true`/`false`, enums by name, ints as decimal, collections
 *   sorted and comma-joined.
 * - Keys: flag key (FEATURE_FLAG); group id (BOROUGH_GROUP; fields name, status, maxPerReferee,
 *   boroughs); `groupId:deviceType` (BOROUGH_AVAILABILITY; field mode); `groupId:orgId`
 *   (LIMIT_EXCEPTION; field maxPerReferee, subject = the organisation id and name); admin-config
 *   field name (ADMIN_CONFIG).
 * - Delivery configuration (#327 follow-up): DELIVERY_CONFIG key = field (enabled, daysOfWeek sorted
 *   and comma-joined, leadTimeDays, advanceDays, boroughSchedulingEnabled); DELIVERY_WINDOW key = window
 *   id (fields name, startTime, endTime, icon, capacity, sortOrder, active; subject = id and name);
 *   DELIVERY_BLOCKED_DATE key = ISO date (field reason; subject = row id, label = the date);
 *   DELIVERY_DAY_BOROUGH key = ISO day-of-week number (field boroughs, sorted and comma-joined; an
 *   unrestricted day has no entry, so restricting it is CREATED and clearing it DELETED).
 * - A created group records a CREATED row per group field and per device-type mode; a deleted group
 *   records the matching DELETED rows, with the name snapshotted as the subject label.
 */
@Service
class AdminSettingAuditService(
    private val history: AdminSettingChangeRepository,
    private val filterService: FilterService,
) {
    data class Ref(
        val type: SettingType,
        val key: String,
        val field: String,
    )

    data class Value(
        val value: String,
        val subjectId: Long? = null,
        val subjectLabel: String? = null,
    )

    data class Change(
        val ref: Ref,
        val changeType: SettingChangeType,
        val oldValue: String?,
        val newValue: String?,
        val subjectId: Long?,
        val subjectLabel: String?,
    )

    /** Writes one USER row per differing entry, attributed to the signed-in admin. */
    fun record(
        before: Map<Ref, Value>,
        after: Map<Ref, Value>,
    ) {
        val changes = diff(before, after)
        if (changes.isEmpty()) return
        val user = filterService.userDetails()
        val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
        history.saveAll(
            changes.map {
                AdminSettingChange(
                    changedAt = now,
                    source = SettingChangeSource.USER,
                    changedByEmail = user.email.ifBlank { null },
                    changedByName = user.name.ifBlank { null },
                    settingType = it.ref.type,
                    settingKey = it.ref.key,
                    subjectId = it.subjectId,
                    subjectLabel = it.subjectLabel,
                    field = it.ref.field,
                    changeType = it.changeType,
                    oldValue = it.oldValue,
                    newValue = it.newValue,
                )
            },
        )
    }

    companion object {
        fun encode(value: Any): String =
            when (value) {
                is Enum<*> -> value.name
                is Collection<*> -> value.map { it.toString() }.sorted().joinToString(",")
                else -> value.toString()
            }

        fun diff(
            before: Map<Ref, Value>,
            after: Map<Ref, Value>,
        ): List<Change> =
            (before.keys + after.keys).mapNotNull { ref ->
                val old = before[ref]
                val new = after[ref]
                if (old?.value == new?.value) return@mapNotNull null
                val changeType =
                    when {
                        old == null -> SettingChangeType.CREATED
                        new == null -> SettingChangeType.DELETED
                        else -> SettingChangeType.UPDATED
                    }
                val subject = new ?: old!!
                Change(ref, changeType, old?.value, new?.value, subject.subjectId, subject.subjectLabel)
            }

        fun featureFlag(
            key: String,
            enabled: Boolean?,
        ): Map<Ref, Value> =
            if (enabled == null) {
                emptyMap()
            } else {
                mapOf(Ref(SettingType.FEATURE_FLAG, key, "enabled") to Value(encode(enabled), subjectLabel = key))
            }

        fun adminConfig(config: AdminConfig): Map<Ref, Value> =
            mapOf(
                "canPublicRequestSIMCard" to config.canPublicRequestSIMCard,
                "canPublicRequestLaptop" to config.canPublicRequestLaptop,
                "canPublicRequestPhone" to config.canPublicRequestPhone,
                "canPublicRequestBroadbandHub" to config.canPublicRequestBroadbandHub,
                "canPublicRequestTablet" to config.canPublicRequestTablet,
                "canPublicRequestDesktop" to config.canPublicRequestDesktop,
                "membershipOverLimitMode" to config.membershipOverLimitMode,
                "membershipGraceRequests" to config.membershipGraceRequests,
            ).entries.associate { (field, value) -> Ref(SettingType.ADMIN_CONFIG, field, field) to Value(encode(value)) }

        fun boroughs(
            groups: Collection<BoroughGroup>,
            exceptions: Collection<ReferrerLimitException>,
        ): Map<Ref, Value> {
            val result = mutableMapOf<Ref, Value>()
            groups.forEach { group ->
                val key = group.id.toString()
                mapOf(
                    "name" to group.name,
                    "status" to group.status,
                    "maxPerReferee" to group.maxPerReferee,
                    "boroughs" to group.boroughs,
                ).forEach { (field, value) ->
                    result[Ref(SettingType.BOROUGH_GROUP, key, field)] = Value(encode(value), group.id, group.name)
                }
                group.availability.forEach {
                    result[Ref(SettingType.BOROUGH_AVAILABILITY, "$key:${it.deviceType}", "mode")] =
                        Value(encode(it.mode), group.id, group.name)
                }
            }
            exceptions.forEach {
                val org = it.referringOrganisation!!
                result[Ref(SettingType.LIMIT_EXCEPTION, "${it.group!!.id}:${org.id}", "maxPerReferee")] =
                    Value(encode(it.maxPerReferee), org.id, org.name)
            }
            return result
        }

        private fun sortedDays(raw: String): String =
            raw
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .sorted()
                .joinToString(",")

        fun deliveryConfig(config: DeliveryConfig): Map<Ref, Value> =
            mapOf(
                "enabled" to config.enabled,
                "daysOfWeek" to
                    config.daysOfWeek
                        .split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .sorted()
                        .joinToString(","),
                "leadTimeDays" to config.leadTimeDays,
                "advanceDays" to config.advanceDays,
                "boroughSchedulingEnabled" to config.boroughSchedulingEnabled,
            ).entries.associate { (field, value) -> Ref(SettingType.DELIVERY_CONFIG, field, field) to Value(encode(value)) }

        /** Snapshot of one window; pass null (a window that does not exist yet / any more) for an empty map. */
        fun deliveryWindow(window: DeliveryWindow?): Map<Ref, Value> {
            if (window == null) return emptyMap()
            return mapOf(
                "name" to window.name,
                "startTime" to window.startTime,
                "endTime" to window.endTime,
                "icon" to window.icon,
                "capacity" to window.capacity,
                "sortOrder" to window.sortOrder,
                "active" to window.active,
            ).entries.associate { (field, value) ->
                Ref(SettingType.DELIVERY_WINDOW, window.id.toString(), field) to Value(encode(value), window.id, window.name)
            }
        }

        fun deliveryBlockedDates(dates: Collection<DeliveryBlockedDate>): Map<Ref, Value> =
            dates.associate {
                Ref(SettingType.DELIVERY_BLOCKED_DATE, it.blockedDate.toString(), "reason") to
                    Value(it.reason.orEmpty(), it.id, it.blockedDate.toString())
            }

        fun deliveryDayBoroughs(rows: Collection<DeliveryDayBorough>): Map<Ref, Value> =
            rows.groupBy { it.dayOfWeek }.entries.associate { (day, boroughs) ->
                Ref(SettingType.DELIVERY_DAY_BOROUGH, day.toString(), "boroughs") to
                    Value(encode(boroughs.map { it.borough }), day.toLong(), day.toString())
            }
    }
}
