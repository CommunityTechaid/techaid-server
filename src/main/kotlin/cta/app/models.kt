package cta.app

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.vladmihalcea.hibernate.type.json.JsonBinaryType
import com.vladmihalcea.hibernate.type.json.JsonStringType
import cta.app.services.Coordinates
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Converts
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
import jakarta.persistence.MappedSuperclass
import jakarta.persistence.OneToMany
import jakarta.persistence.SequenceGenerator
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.Formula
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.OrderBy
import org.hibernate.annotations.UpdateTimestamp
import org.hibernate.envers.AuditTable
import org.hibernate.envers.Audited
import org.hibernate.envers.NotAudited
import org.hibernate.envers.RelationTargetAuditMode
import org.hibernate.envers.RevisionEntity
import org.hibernate.envers.RevisionNumber
import org.hibernate.envers.RevisionTimestamp
import org.hibernate.type.SqlTypes
import org.hibernate.type.YesNoConverter
import java.time.Instant


@Converts(
    Convert(attributeName = "json", converter = JsonStringType::class),
    Convert(attributeName = "jsonb", converter = JsonBinaryType::class)
)
@MappedSuperclass
class BaseEntity

@Entity
@Table(name = "donors")
@Audited(targetAuditMode = RelationTargetAuditMode.NOT_AUDITED)
@AuditTable(value = "donors_audit_trail")
class Donor(
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "donor-seq-generator")
    @SequenceGenerator(name = "donor-seq-generator", sequenceName = "donor_sequence", allocationSize = 1)
    var id: Long = 0,
    var postCode: String,
    var phoneNumber: String,
    var email: String,
    var name: String,
    var referral: String,
    var createdAt: Instant = Instant.now(),
    @NotAudited
    @Formula(
        """
        ( SELECT COUNT(*) FROM kits k where k.donor_id = id )
    """
    )
    var kitCount: Int = 0,
    @UpdateTimestamp
    var updatedAt: Instant = Instant.now(),
    @NotAudited
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    var coordinates: Coordinates? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "donor_parent_id")
    var donorParent: DonorParent? = null,
    @NotAudited
    @OneToMany(
        mappedBy = "donor",
        fetch = FetchType.LAZY,
        cascade = [CascadeType.ALL],
        orphanRemoval = false
    )
    var kits: MutableSet<Kit> = mutableSetOf(),
    @Convert(converter = YesNoConverter::class)
    var archived: Boolean = false,
    var isLeadContact: Boolean = false
) : BaseEntity() {
    @NotAudited
    fun addKit(kit: Kit) {
        kits.add(kit)
        kit.donor = this
    }

    @NotAudited
    fun removeKit(kit: Kit) {
        kits.removeIf {
            if (kit == it) {
                kit.donor = null
                true
            } else {
                false
            }
        }
    }
}

enum class DonorParentType {
    BUSINESS,
    DROPPOINT
}

@Entity
@Table(name = "donor_parents")
@Audited(targetAuditMode = RelationTargetAuditMode.NOT_AUDITED)
@AuditTable(value = "donorParents_audit_trail")
class DonorParent(
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "donor-parent-seq-generator")
    @SequenceGenerator(name = "donor-parent-seq-generator", sequenceName = "donor_parent_sequence", allocationSize = 1)
    var id: Long = 0,
    var name: String,
    var address: String,
    var website: String,
    var createdAt: Instant = Instant.now(),
    @UpdateTimestamp
    var updatedAt: Instant = Instant.now(),
    @NotAudited
    @Formula(
        """
        ( SELECT COUNT(*) FROM donors d where d.donor_parent_id = id )
    """
    )
    var donorCount: Int = 0,
    @NotAudited
    @OneToMany(
        mappedBy = "donorParent",
        fetch = FetchType.LAZY,
        cascade = [CascadeType.ALL],
        orphanRemoval = false
    )
    var donors: MutableSet<Donor> = mutableSetOf(),
    @Enumerated(EnumType.STRING)
    var type: DonorParentType? = DonorParentType.DROPPOINT,
    @Convert(converter = YesNoConverter::class)
    var archived: Boolean = false
) : BaseEntity() {
    fun addDonor(donor: Donor) {
        donors.add(donor)
        donor.donorParent = this
    }

    fun removeDonor(donor: Donor) {
        donors.removeIf {
            if (donor == it) {
                donor.donorParent = null
                true
            } else {
                false
            }
        }
    }
}

/*
* This entity is used to hold the information of audits used by Hibernate Enver.
* It is completely managed by the plugin. The id and the timestamp are mandatory fields
*/
@Entity
@Table(name = "custom_rev_info")
@RevisionEntity(CustomRevisionEntityListener::class)
class CustomRevisionInfo {

    @Id
    @GeneratedValue
    @RevisionNumber
    val id: Long? = null

    @RevisionTimestamp
    val timestamp: Long? = null

    @Column(name = "custom_user")
    var customUser: String = "user"
}

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
    @Convert(converter=YesNoConverter::class)
    var archived: Boolean = false,
    var createdAt: Instant = Instant.now(),
    @UpdateTimestamp
    var updatedAt: Instant = Instant.now(),

    @OneToMany(
        fetch = FetchType.LAZY,
        cascade = [CascadeType.ALL],
        orphanRemoval = true,
        mappedBy = "kit"
    )
    @OrderBy(clause = "updatedAt DESC")
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
    @NotAudited
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
    @Embedded
    var subStatus: KitSubStatus = KitSubStatus()
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
    var lockedToUser: Boolean? = false
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
    var otherNetwork: String? = "UNKNOWN"
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
    var kit: Kit
) {}

enum class KitType {
    OTHER,
    LAPTOP,
    DESKTOP,
    TABLET,
    SMARTPHONE,
    ALLINONE,
    COMMSDEVICE,
    BROADBANDHUB
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
    DISTRIBUTION_REPAIR_RETURN
}

enum class KitStorageType {HDD, SSD, HYBRID, UNKNOWN}

@Entity
@Table(name = "referring_organisations")
@Audited(targetAuditMode = RelationTargetAuditMode.NOT_AUDITED)
@AuditTable(value = "referring_organisations_audit_trail")
class ReferringOrganisation(
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "referring_organisation-seq-generator")
    @SequenceGenerator(
        name = "referring_organisation-seq-generator",
        sequenceName = "referring_organisation_sequence",
        allocationSize = 1
    )
    var id: Long = 0,
    var name: String,
    var website: String? = null,
    var phoneNumber: String? = null,
    @NotAudited
    @Formula(
        """
        (SELECT COUNT(*) 
        FROM device_requests dr 
        INNER JOIN referring_organisation_contacts roc 
            ON dr.referring_organisation_contact_id = roc.id 
        WHERE dr.status='NEW' 
            AND roc.referring_organisation_id = id)
    """
    )
    var requestCount: Int = 0,
    @Convert(converter = org.hibernate.type.YesNoConverter::class)
    var archived: Boolean = false,
    @CreationTimestamp
    var createdAt: Instant = Instant.now(),
    @UpdateTimestamp
    var updatedAt: Instant = Instant.now(),
    @NotAudited
    @OneToMany(
        fetch = FetchType.LAZY,
        cascade = [CascadeType.ALL],
        orphanRemoval = true,
        mappedBy = "referringOrganisation"
    )
    @OrderBy(clause = "updatedAt DESC")
    var referringOrganisationContacts: MutableSet<ReferringOrganisationContact> = mutableSetOf(),
    @NotAudited
    @OneToMany(
        fetch = FetchType.LAZY,
        cascade = [CascadeType.ALL],
        orphanRemoval = true,
        mappedBy = "referringOrganisation"
    )
    @OrderBy(clause = "updatedAt DESC")
    var referringOrganisationNotes: MutableSet<ReferringOrganisationNote> = mutableSetOf()
) {
    fun addContact(contact: ReferringOrganisationContact) {
        referringOrganisationContacts.add(contact)
        contact.referringOrganisation = this
    }
}


@Entity
@Table(name = "referring_organisation_contacts")
@Audited(targetAuditMode = RelationTargetAuditMode.NOT_AUDITED)
@AuditTable(value = "referring_organisation_contacts_audit_trail")
class ReferringOrganisationContact(
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "referring_organisation_contacts-seq-generator")
    @SequenceGenerator(
        name = "referring_organisation_contacts-seq-generator",
        sequenceName = "referring_organisation_contacts_sequence",
        allocationSize = 1
    )
    var id: Long = 0,
    var fullName: String,
    var email: String = "",
    var phoneNumber: String,
    var address: String,
    @Convert(converter = org.hibernate.type.YesNoConverter::class)
    var archived: Boolean = false,
    @CreationTimestamp
    var createdAt: Instant = Instant.now(),
    @UpdateTimestamp
    var updatedAt: Instant = Instant.now(),
    @ManyToOne
    var referringOrganisation: ReferringOrganisation,
    // A better way would be to use the DeviceRequestStatus enum here but for some reason, it is not considered a constant expression.
    @NotAudited
    @Formula(
        """
         (
            SELECT COUNT(*) FROM device_requests d where d.referring_organisation_contact_id = id 
            AND d.status NOT IN ('REQUEST_CANCELLED','REQUEST_COMPLETED','REQUEST_DECLINED')
         )
    """
    )
    var requestCount: Int = 0,
    @NotAudited
    @OneToMany(
        fetch = FetchType.LAZY,
        cascade = [CascadeType.ALL],
        orphanRemoval = true,
        mappedBy = "referringOrganisationContact"
    )
    @OrderBy(clause = "updatedAt DESC")
    var referringOrganisationContactNotes: MutableSet<ReferringOrganisationContactNote> = mutableSetOf()


) {
    companion object {
        const val STATUS = "FINISHED"
    }
}

enum class DeviceRequestStatus {
    NEW,
    PROCESSING_EQUALITIES_DATA_COMPLETE,
    PROCESSING_COLLECTION_DELIVERY_ARRANGED,
    PROCESSING_ON_HOLD,
    REQUEST_COMPLETED,
    REQUEST_DECLINED,
    REQUEST_CANCELLED
}

enum class CollectionMethod {
    COLLECTION,
    DELIVERY,
    UNKNOWN
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
    val broadbandHubs: Int? = 0
)

@JsonIgnoreProperties(ignoreUnknown = true)
@Embeddable
data class DeviceRequestNeeds(
    var hasInternet: Boolean?,
    var hasMobilityIssues: Boolean?,
    var needQuickStart: Boolean?
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
        allocationSize = 1
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
    var details: String,
    @NotAudited
    @Formula(
        """
        (SELECT COUNT(*) FROM kits k where k.device_request_id = id)
    """
    )
    var kitCount: Int = 0,
    @Embedded
    var deviceRequestNeeds: DeviceRequestNeeds?,
    @NotAudited
    @OneToMany(
        fetch = FetchType.LAZY,
        cascade = [CascadeType.ALL],
        orphanRemoval = true,
        mappedBy = "deviceRequest"
    )
    @OrderBy(clause = "updatedAt DESC")
    var deviceRequestNotes: MutableSet<DeviceRequestNote> = mutableSetOf(),
    @NotAudited
    @OneToMany(
        mappedBy = "deviceRequest",
        fetch = FetchType.LAZY,
        cascade = [CascadeType.ALL],
        orphanRemoval = false
    )
    var kits: MutableSet<Kit> = mutableSetOf(),
    var collectionDate: Instant? = null,
    @Enumerated(EnumType.STRING)
    var collectionMethod: CollectionMethod? = null,
    var collectionContactName: String? = null

){
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
    var deviceRequest: DeviceRequest
) {}


@Entity
@Table(name = "referring_organisations_notes")
class ReferringOrganisationNote(
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "referring_organisations_note-seq-generator")
    @SequenceGenerator(name = "referring_organisations_note-seq-generator", sequenceName = "referring_organisations_note_sequence", allocationSize = 1)
    var id: Long = 0,
    @Column(name = "content", length = 4096)
    var content: String,
    @CreationTimestamp
    var createdAt: Instant = Instant.now(),
    @UpdateTimestamp
    var updatedAt: Instant = Instant.now(),
    var volunteer: String? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    var referringOrganisation: ReferringOrganisation
) {}


@Entity
@Table(name = "referring_organisation_contacts_notes")
class ReferringOrganisationContactNote(
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "referring_organisation_contacts_note-seq-generator")
    @SequenceGenerator(name = "referring_organisation_contacts_note-seq-generator", sequenceName = "referring_organisation_contacts_note_sequence", allocationSize = 1)
    var id: Long = 0,
    @Column(name = "content", length = 4096)
    var content: String,
    @CreationTimestamp
    var createdAt: Instant = Instant.now(),
    @UpdateTimestamp
    var updatedAt: Instant = Instant.now(),
    var volunteer: String? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    var referringOrganisationContact: ReferringOrganisationContact
) {}
