package cta.app.services

import cta.app.AdminConfig
import cta.app.AvailabilityMode
import cta.app.BoroughAvailability
import cta.app.BoroughGroup
import cta.app.BoroughGroupStatus
import cta.app.MembershipOverLimitMode
import cta.app.ReferrerLimitException
import cta.app.ReferringOrganisation
import cta.app.SettingChangeType
import cta.app.SettingType
import cta.app.services.AdminSettingAuditService.Companion.adminConfig
import cta.app.services.AdminSettingAuditService.Companion.boroughs
import cta.app.services.AdminSettingAuditService.Companion.diff
import cta.app.services.AdminSettingAuditService.Companion.encode
import cta.app.services.AdminSettingAuditService.Companion.featureFlag
import cta.app.services.AdminSettingAuditService.Ref
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The pure diff/encoding helpers behind the admin-setting history (#327). */
class AdminSettingAuditServiceTest {
    @Test
    fun `encodes booleans, enums, ints and collections`() {
        assertThat(encode(true)).isEqualTo("true")
        assertThat(encode(false)).isEqualTo("false")
        assertThat(encode(MembershipOverLimitMode.BLOCK)).isEqualTo("BLOCK")
        assertThat(encode(AvailabilityMode.AUTO)).isEqualTo("AUTO")
        assertThat(encode(12)).isEqualTo("12")
        assertThat(encode(setOf("Southwark", "Lambeth"))).isEqualTo("Lambeth,Southwark")
        assertThat(encode(listOf("Lambeth", "Southwark"))).isEqualTo(encode(setOf("Southwark", "Lambeth")))
    }

    @Test
    fun `equal snapshots produce nothing`() {
        assertThat(diff(featureFlag("f", true), featureFlag("f", true))).isEmpty()
        assertThat(diff(adminConfig(AdminConfig()), adminConfig(AdminConfig()))).isEmpty()
        assertThat(diff(emptyMap(), emptyMap())).isEmpty()
    }

    @Test
    fun `flag created, updated and deleted`() {
        val created = diff(featureFlag("f", null), featureFlag("f", true)).single()
        assertThat(created.changeType).isEqualTo(SettingChangeType.CREATED)
        assertThat(created.oldValue).isNull()
        assertThat(created.newValue).isEqualTo("true")
        assertThat(created.subjectLabel).isEqualTo("f")
        assertThat(created.ref).isEqualTo(Ref(SettingType.FEATURE_FLAG, "f", "enabled"))

        val updated = diff(featureFlag("f", true), featureFlag("f", false)).single()
        assertThat(updated.changeType).isEqualTo(SettingChangeType.UPDATED)
        assertThat(listOf(updated.oldValue, updated.newValue)).containsExactly("true", "false")

        val deleted = diff(featureFlag("f", false), featureFlag("f", null)).single()
        assertThat(deleted.changeType).isEqualTo(SettingChangeType.DELETED)
        assertThat(deleted.newValue).isNull()
    }

    @Test
    fun `admin config records only the fields that differ`() {
        val after = AdminConfig(canPublicRequestTablet = false, membershipOverLimitMode = MembershipOverLimitMode.BLOCK)
        val changes = diff(adminConfig(AdminConfig()), adminConfig(after)).associateBy { it.ref.field }
        assertThat(changes.keys).containsExactlyInAnyOrder("canPublicRequestTablet", "membershipOverLimitMode")
        assertThat(changes.getValue("membershipOverLimitMode").oldValue).isEqualTo("GRACE")
        assertThat(changes.getValue("membershipOverLimitMode").newValue).isEqualTo("BLOCK")
        assertThat(changes.getValue("canPublicRequestTablet").ref.key).isEqualTo("canPublicRequestTablet")
    }

    private fun group(
        name: String = "Lambeth & Southwark",
        boroughs: Set<String> = setOf("Lambeth", "Southwark"),
        laptops: AvailabilityMode = AvailabilityMode.ON,
    ) = BoroughGroup(id = 7, name = name, status = BoroughGroupStatus.LIVE, maxPerReferee = 3, boroughs = boroughs.toMutableSet())
        .also { it.availability.add(BoroughAvailability(group = it, deviceType = "laptops", mode = laptops)) }

    private fun exception(
        group: BoroughGroup,
        limit: Int,
    ) = ReferrerLimitException(
        id = limit.toLong(),
        referringOrganisation = ReferringOrganisation(id = 42, name = "Org A"),
        group = group,
        maxPerReferee = limit,
    )

    @Test
    fun `boroughs - reordered sets and re-inserted identical exceptions are not changes`() {
        val before = group(boroughs = linkedSetOf("Southwark", "Lambeth"))
        val after = group(boroughs = linkedSetOf("Lambeth", "Southwark"))
        assertThat(diff(boroughs(listOf(before), listOf(exception(before, 5))), boroughs(listOf(after), listOf(exception(after, 5)))))
            .isEmpty()
    }

    @Test
    fun `boroughs - cell, field and exception changes`() {
        val before = group()
        val after = group(name = "L&S", laptops = AvailabilityMode.OFF)
        val changes = diff(boroughs(listOf(before), listOf(exception(before, 5))), boroughs(listOf(after), emptyList()))
        val byRef = changes.associateBy { it.ref }

        val mode = byRef.getValue(Ref(SettingType.BOROUGH_AVAILABILITY, "7:laptops", "mode"))
        assertThat(listOf(mode.oldValue, mode.newValue, mode.subjectLabel)).containsExactly("ON", "OFF", "L&S")
        assertThat(mode.subjectId).isEqualTo(7L)

        val name = byRef.getValue(Ref(SettingType.BOROUGH_GROUP, "7", "name"))
        assertThat(listOf(name.oldValue, name.newValue)).containsExactly("Lambeth & Southwark", "L&S")

        val removed = byRef.getValue(Ref(SettingType.LIMIT_EXCEPTION, "7:42", "maxPerReferee"))
        assertThat(removed.changeType).isEqualTo(SettingChangeType.DELETED)
        assertThat(listOf(removed.oldValue, removed.subjectLabel)).containsExactly("5", "Org A")
        assertThat(removed.subjectId).isEqualTo(42L)
        assertThat(changes).hasSize(3)
    }

    @Test
    fun `boroughs - deleted group keeps its name as the label`() {
        val changes = diff(boroughs(listOf(group()), emptyList()), emptyMap())
        assertThat(changes).allMatch { it.changeType == SettingChangeType.DELETED && it.subjectLabel == "Lambeth & Southwark" }
        assertThat(changes.map { it.ref.field }).containsExactlyInAnyOrder("name", "status", "maxPerReferee", "boroughs", "mode")
    }
}
