package cta.app.services

import cta.app.AdminConfig
import cta.app.AdminSettingChange
import cta.app.AdminSettingChangeRepository
import cta.app.BoroughGroup
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
    }
}
