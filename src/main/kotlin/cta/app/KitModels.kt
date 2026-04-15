package cta.app

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import cta.app.services.Coordinates
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Embeddable
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.SequenceGenerator
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.SQLOrder
import org.hibernate.annotations.UpdateTimestamp
import org.hibernate.envers.AuditTable
import org.hibernate.envers.Audited
import org.hibernate.envers.NotAudited
import org.hibernate.envers.RelationTargetAuditMode
import org.hibernate.type.SqlTypes
import org.hibernate.type.YesNoConverter
import java.time.Instant

@Entity
@Table(name = "kits")
@Audited(targetAuditMode = RelationTargetAuditMode.NOT_AUDITED)
@AuditTable(value = "kit_audit_trail")
class Kit(
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "kit-seq-generator")
    @SequenceGenerator(name = "kit-seq-generator", sequenceName = "kit_sequence", allocationSize = 1)
    var id: Long = 0,
    @Enumerated(EnumType.STRING)
    var type: KitType = KitType.OTHER,
    @Enumerated(EnumType.STRING)
    var status: KitStatus = KitStatus.DONATION_NEW,
    var model: String,
    var location: String = "",
    var age: Int? = null,
    @Convert(converter = YesNoConverter::class)
    var archived: Boolean = false,
    var createdAt: Instant = Instant.now(),
    @UpdateTimestamp
    var updatedAt: Instant = Instant.now(),
    var statusUpdatedAt: Instant = Instant.now(),
    @OneToMany(
        fetch = FetchType.LAZY,
        cascade = [CascadeType.ALL],
        orphanRemoval = true,
        mappedBy = "kit",
    )
    @SQLOrder("updatedAt DESC")
    var notes: MutableSet<Note> = mutableSetOf(),
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    @NotAudited
    var attributes: KitAttributes = KitAttributes(),
    @NotAudited
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    var coordinates: Coordinates? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "donor_id")
    @Audited(targetAuditMode = RelationTargetAuditMode.NOT_AUDITED)
    var donor: Donor? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_request_id")
    @Audited(targetAuditMode = RelationTargetAuditMode.NOT_AUDITED)
    var deviceRequest: DeviceRequest? = null,
    var make: String? = null,
    var deviceVersion: String? = null,
    var serialNo: String? = null,
    var storageCapacity: Int? = null,
    @Enumerated(EnumType.STRING)
    var typeOfStorage: KitStorageType = KitStorageType.UNKNOWN,
    var ramCapacity: Int? = null,
    var cpuType: String? = null,
    var tpmVersion: String? = null,
    var cpuCores: Int? = null,
    var batteryHealth: Int? = null,
    var lotId: String? = null,
    var locationCode: String? = null,
    @Embedded
    var subStatus: KitSubStatus = KitSubStatus(),
) : BaseEntity() {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Kit) return false
        return id != 0L && id == other.id
    }

    override fun hashCode() = 13
}

@JsonIgnoreProperties(ignoreUnknown = true)
@Embeddable
data class KitSubStatus(
    var installationOfOSFailed: Boolean? = false,
    var wipeFailed: Boolean? = false,
    var needsSparePart: Boolean? = false,
    var needsFurtherInvestigation: Boolean? = false,
    var network: String? = null,
    var installedOSName: String? = null,
    var lockedToUser: Boolean? = false,
)

@JsonIgnoreProperties(ignoreUnknown = true)
class KitAttributes(
    @JsonIgnore
    @NotAudited
    var kit: Kit? = null,
    var otherType: String? = null,
    var state: String? = null,
    var notes: String? = "",
    var credentials: String? = null,
    var status: List<String> = listOf(),
    var network: String? = null,
    var otherNetwork: String? = "UNKNOWN",
)

@Entity
@Table(name = "note")
@Audited(targetAuditMode = RelationTargetAuditMode.NOT_AUDITED)
class Note(
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "note-seq-generator")
    @SequenceGenerator(name = "note-seq-generator", sequenceName = "note_sequence", allocationSize = 1)
    var id: Long = 0,
    @Column(name = "content", length = 4096)
    var content: String,
    @CreationTimestamp
    var createdAt: Instant = Instant.now(),
    @UpdateTimestamp
    var updatedAt: Instant = Instant.now(),
    var volunteer: String? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    var kit: Kit,
)

enum class KitType {
    OTHER,
    LAPTOP,
    DESKTOP,
    TABLET,
    SMARTPHONE,
    ALLINONE,
    COMMSDEVICE,
    BROADBANDHUB,
}

enum class KitStatus {
    DONATION_NEW,
    PROCESSING_START,
    PROCESSING_WIPED,
    PROCESSING_OS_INSTALLED,
    PROCESSING_STORED,
    ALLOCATION_ASSESSMENT,
    ALLOCATION_READY,
    ALLOCATION_QC_COMPLETED,
    ALLOCATION_DELIVERY_ARRANGED,
    DISTRIBUTION_DELIVERED,
    DISTRIBUTION_RECYCLED,
    DISTRIBUTION_REPAIR_RETURN,
}

enum class KitStorageType { HDD, SSD, HYBRID, UNKNOWN }
