package cta.app

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.SequenceGenerator
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.Formula
import org.hibernate.annotations.SQLOrder
import org.hibernate.annotations.UpdateTimestamp
import org.hibernate.envers.AuditTable
import org.hibernate.envers.Audited
import org.hibernate.envers.NotAudited
import org.hibernate.envers.RelationTargetAuditMode
import java.time.Instant

enum class DeviceRequestStatus {
    NEW,
    PROCESSING_EQUALITIES_DATA_COMPLETE,
    PROCESSING_COLLECTION_DELIVERY_ARRANGED,
    PROCESSING_ON_HOLD,
    REQUEST_COMPLETED,
    REQUEST_COLLECTION_DELIVERY_FAILED,
    REQUEST_DECLINED,
    REQUEST_CANCELLED,
}

enum class CollectionMethod {
    COLLECTION,
    DELIVERY,
    UNKNOWN,
}

@JsonIgnoreProperties(ignoreUnknown = true)
@Embeddable
data class DeviceRequestItems(
    val phones: Int? = 0,
    val tablets: Int? = 0,
    val laptops: Int? = 0,
    val allInOnes: Int? = 0,
    val desktops: Int? = 0,
    val other: Int? = 0,
    val commsDevices: Int? = 0,
    val broadbandHubs: Int? = 0,
)

@JsonIgnoreProperties(ignoreUnknown = true)
@Embeddable
data class DeviceRequestNeeds(
    var hasInternet: Boolean?,
    var hasMobilityIssues: Boolean?,
    var needQuickStart: Boolean?,
)

@Entity
@Table(name = "device_requests")
@Audited(targetAuditMode = RelationTargetAuditMode.NOT_AUDITED)
@AuditTable(value = "device_requests_audit_trail")
class DeviceRequest(
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "device_requests-seq-generator")
    @SequenceGenerator(
        name = "device_requests-seq-generator",
        sequenceName = "device_requests_sequence",
        allocationSize = 1,
    )
    var id: Long = 0,
    @NotAudited
    var correlationId: Long? = null,
    @Embedded
    var deviceRequestItems: DeviceRequestItems,
    @Enumerated(EnumType.STRING)
    var status: DeviceRequestStatus = DeviceRequestStatus.NEW,
    @CreationTimestamp
    var createdAt: Instant = Instant.now(),
    @UpdateTimestamp
    var updatedAt: Instant = Instant.now(),
    @ManyToOne
    var referringOrganisationContact: ReferringOrganisationContact,
    var isSales: Boolean = false,
    var clientRef: String,
    var borough: String?,
    @Column(columnDefinition = "TEXT")
    var details: String,
    @NotAudited
    @Formula(
        """
        (SELECT COUNT(*) FROM kits k where k.device_request_id = id)
    """,
    )
    var kitCount: Int = 0,
    @Embedded
    var deviceRequestNeeds: DeviceRequestNeeds?,
    @NotAudited
    @OneToMany(
        fetch = FetchType.LAZY,
        cascade = [CascadeType.ALL],
        orphanRemoval = true,
        mappedBy = "deviceRequest",
    )
    @SQLOrder("updatedAt DESC")
    var deviceRequestNotes: MutableSet<DeviceRequestNote> = mutableSetOf(),
    @NotAudited
    @OneToMany(
        mappedBy = "deviceRequest",
        fetch = FetchType.LAZY,
        cascade = [CascadeType.ALL],
        orphanRemoval = false,
    )
    var kits: MutableSet<Kit> = mutableSetOf(),
    var collectionDate: Instant? = null,
    @Enumerated(EnumType.STRING)
    var collectionMethod: CollectionMethod? = null,
    var collectionContactName: String? = null,
    var isPrepped: Boolean = false,
) {
    fun addKit(kit: Kit) {
        kits.add(kit)
        kit.deviceRequest = this
    }

    fun removeKit(kit: Kit) {
        kits.removeIf {
            if (kit == it) {
                kit.deviceRequest = null
                true
            } else {
                false
            }
        }
    }
}

@Entity
@Table(name = "device_requests_notes")
class DeviceRequestNote(
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "device_requests_note-seq-generator")
    @SequenceGenerator(name = "device_requests_note-seq-generator", sequenceName = "device_requests_note_sequence", allocationSize = 1)
    var id: Long = 0,
    @Column(name = "content", length = 4096)
    var content: String,
    @CreationTimestamp
    var createdAt: Instant = Instant.now(),
    @UpdateTimestamp
    var updatedAt: Instant = Instant.now(),
    var volunteer: String? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    var deviceRequest: DeviceRequest,
)
