package cta.app

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.SequenceGenerator
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.querydsl.QuerydslPredicateExecutor
import java.time.Instant

/** Who made a setting change: a signed-in admin, a Flyway migration, or application code. */
enum class SettingChangeSource { USER, MIGRATION, SYSTEM }

enum class SettingType {
    FEATURE_FLAG,
    BOROUGH_GROUP,
    BOROUGH_AVAILABILITY,
    LIMIT_EXCEPTION,
    ADMIN_CONFIG,
    DELIVERY_CONFIG,
    DELIVERY_WINDOW,
    DELIVERY_BLOCKED_DATE,
    DELIVERY_DAY_BOROUGH,
}

enum class SettingChangeType { CREATED, UPDATED, DELETED }

/**
 * One field of one admin-panel setting changing value (#327). Append-only, written by
 * AdminSettingAuditService in the same transaction as the change, and by migrations that change a
 * flag (source = MIGRATION). Deliberately not Envers - see the issue for why.
 */
@Entity
@Table(name = "admin_setting_history")
class AdminSettingChange(
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "admin_setting_history-seq-generator")
    @SequenceGenerator(
        name = "admin_setting_history-seq-generator",
        sequenceName = "admin_setting_history_sequence",
        allocationSize = 1,
    )
    var id: Long = 0,
    var changedAt: Instant = Instant.now(),
    @Enumerated(EnumType.STRING)
    var source: SettingChangeSource = SettingChangeSource.USER,
    var changedByEmail: String? = null,
    var changedByName: String? = null,
    @Enumerated(EnumType.STRING)
    var settingType: SettingType = SettingType.FEATURE_FLAG,
    var settingKey: String = "",
    var subjectId: Long? = null,
    var subjectLabel: String? = null,
    var field: String = "",
    @Enumerated(EnumType.STRING)
    var changeType: SettingChangeType = SettingChangeType.UPDATED,
    @Column(columnDefinition = "text")
    var oldValue: String? = null,
    @Column(columnDefinition = "text")
    var newValue: String? = null,
)

interface AdminSettingChangeRepository :
    JpaRepository<AdminSettingChange, Long>,
    QuerydslPredicateExecutor<AdminSettingChange>
