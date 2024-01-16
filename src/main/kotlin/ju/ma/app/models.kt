package ju.ma.app

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.vladmihalcea.hibernate.type.json.JsonBinaryType
import com.vladmihalcea.hibernate.type.json.JsonStringType
import ju.ma.app.services.Coordinates
import org.apache.commons.lang3.RandomStringUtils
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.Formula
import org.hibernate.annotations.OrderBy
import org.hibernate.annotations.Type
import org.hibernate.annotations.TypeDef
import org.hibernate.annotations.TypeDefs
import org.hibernate.annotations.UpdateTimestamp
import org.hibernate.envers.AuditTable
import org.hibernate.envers.Audited
import org.hibernate.envers.NotAudited
import org.hibernate.envers.RelationTargetAuditMode
import org.hibernate.envers.RevisionEntity
import org.hibernate.envers.RevisionNumber
import org.hibernate.envers.RevisionTimestamp
import java.io.Serializable
import java.time.Instant
import java.util.Objects
import javax.persistence.Basic
import javax.persistence.CascadeType
import javax.persistence.Column
import javax.persistence.Embeddable
import javax.persistence.EmbeddedId
import javax.persistence.Entity
import javax.persistence.EnumType
import javax.persistence.Enumerated
import javax.persistence.FetchType
import javax.persistence.GeneratedValue
import javax.persistence.GenerationType
import javax.persistence.Id
import javax.persistence.JoinColumn
import javax.persistence.ManyToOne
import javax.persistence.MappedSuperclass
import javax.persistence.MapsId
import javax.persistence.OneToMany
import javax.persistence.OneToOne
import javax.persistence.SequenceGenerator
import javax.persistence.Table


@TypeDefs(
    TypeDef(name = "json", typeClass = JsonStringType::class),
    TypeDef(name = "jsonb", typeClass = JsonBinaryType::class)
)
@MappedSuperclass
class BaseEntity

@Entity
@Table(name = "volunteers")
class Volunteer(
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "volunteer-seq-generator")
    @SequenceGenerator(name = "volunteer-seq-generator", sequenceName = "volunteer_sequence", allocationSize = 1)
    var id: Long = 0,
    var name: String,
    var phoneNumber: String,
    var email: String,
    var expertise: String,
    var subGroup: String,
    var storage: String,
    var transport: String,
    var postCode: String,
    var availability: String,
    var createdAt: Instant = Instant.now(),
    var consent: String,
    @Formula(
        """
        (SELECT COUNT(*) FROM kit_volunteers k where k.volunteer_id = id)
    """
    )
    var kitCount: Int = 0,
    @UpdateTimestamp
    var updatedAt: Instant = Instant.now(),
    @Type(type = "jsonb")
    @Column(columnDefinition = "jsonb")
    var coordinates: Coordinates? = null,
    @Type(type = "jsonb")
    @Column(columnDefinition = "jsonb")
    var attributes: VolunteerAttributes = VolunteerAttributes(),
    @JsonIgnore
    @OneToMany(mappedBy = "volunteer", fetch = FetchType.LAZY, orphanRemoval = true, cascade = [CascadeType.ALL])
    var kits: MutableSet<KitVolunteer> = mutableSetOf(),
    @OneToMany(
        mappedBy = "volunteer",
        fetch = FetchType.LAZY,
        cascade = [CascadeType.ALL],
        orphanRemoval = false
    )
    var organisations: MutableSet<Organisation> = mutableSetOf()
) : BaseEntity() {
    fun addOrganisation(org: Organisation) {
        organisations.add(org)
        org.volunteer = this
    }

    fun removeOrganisation(org: Organisation) {
        organisations.removeIf {
            if (org == it) {
                org.volunteer = null
                true
            } else {
                false
            }
        }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class VolunteerAttributes(
    var dropOffAvailability: String = "",
    var hasCapacity: Boolean = false,
    var accepts: List<String> = listOf(),
    var capacity: Capacity = Capacity()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Capacity(
    val phones: Int = 0,
    val tablets: Int = 0,
    val laptops: Int = 0,
    val allInOnes: Int = 0,
    val desktops: Int = 0,
    val other: Int = 0,
    val chromebooks: Int? = 0,
    val commsDevices: Int? = 0
)

@Entity
@Table(name = "donors")
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
    var consent: Boolean,
    var createdAt: Instant = Instant.now(),
    @Formula(
        """
        ( SELECT COUNT(*) FROM kits k where k.donor_id = id )
    """
    )
    var kitCount: Int = 0,
    @UpdateTimestamp
    var updatedAt: Instant = Instant.now(),
    @Type(type = "jsonb")
    @Column(columnDefinition = "jsonb")
    var coordinates: Coordinates? = null,
    @OneToMany(
        mappedBy = "donor",
        fetch = FetchType.LAZY,
        cascade = [CascadeType.ALL],
        orphanRemoval = false
    )
    var kits: MutableSet<Kit> = mutableSetOf()
) : BaseEntity() {
    fun addKit(kit: Kit) {
        kits.add(kit)
        kit.donor = this
    }

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
    var location: String,
    var age: Int,
    @Type(type = "yes_no")
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

    @Type(type = "jsonb")
    @Column(columnDefinition = "jsonb")
    @NotAudited
    var attributes: KitAttributes = KitAttributes(),
    @NotAudited
    @Type(type = "jsonb")
    @Column(columnDefinition = "jsonb")
    var coordinates: Coordinates? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "donor_id")
    var donor: Donor? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organisation_id")
    var organisation: Organisation? = null,
    @NotAudited
    @JsonIgnore
    @OneToMany(mappedBy = "kit", fetch = FetchType.LAZY, orphanRemoval = true, cascade = [CascadeType.ALL])
    var volunteers: MutableSet<KitVolunteer> = mutableSetOf(),
    // @OneToOne(mappedBy = "kit", cascade = [CascadeType.ALL], fetch = FetchType.LAZY)
    // @PrimaryKeyJoinColumn
    // var images: KitImage? = null
    @Column(unique = true)
    var serialNo: String? = null,
    var storageCapacity: Int? = null,
    var typeOfStorage: KitStorageType = KitStorageType.UNKNOWN,
    var ramCapacity: Int? = null,
    var cpu: String? = null,
    var tpmVersion: String? = null
) : BaseEntity() {
    fun addVolunteer(volunteer: Volunteer, type: KitVolunteerType) {
        val entity = KitVolunteer(this, volunteer, KitVolunteerId(this.id, volunteer.id, type))
        volunteers.add(entity)
        volunteer.kits.add(entity)
    }

    fun replaceVolunteers(entities: Iterable<Volunteer>, type: KitVolunteerType): List<Volunteer> {
        val incoming = entities.map { it.id to it }.toMap()
        val existing = volunteers.filter { it.id.type == type }
            .map { it.id.volunteerId to it }.toMap()
        existing.forEach { (k, v) ->
            if (!incoming.containsKey(k)) {
                removeVolunteer(v.volunteer, type)
            }
        }
        val added = mutableListOf<Volunteer>()
        incoming.forEach { (k, v) ->
            if (!existing.containsKey(k)) {
                addVolunteer(v, type)
                added.add(v)
            }
        }
        return added
    }

    fun removeVolunteer(type: KitVolunteerType): Boolean {
        return volunteers.removeIf { kv ->
            if (kv.id.type == type) {
                kv.volunteer.kits.remove(kv)
                true
            } else {
                false
            }
        }
    }

    fun removeVolunteer(volunteer: Volunteer, type: KitVolunteerType): Boolean {
        return volunteers.removeIf { kv ->
            if (kv.id.type == type && kv.kit == this && kv.volunteer == volunteer) {
                volunteer.kits.remove(kv)
                true
            } else {
                false
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Kit) return false
        return id != 0L && id == other.id
    }

    override fun hashCode() = 13
}

@Entity
@Table(name = "kit_images")
class KitImage(
    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "kit_id")
    var kit: Kit?,
    @Id
    @Column(name = "kit_id")
    var id: Long? = kit?.id,
    @Type(type = "jsonb")
    @Basic(fetch = FetchType.LAZY)
    var images: MutableList<DeviceImage> = mutableListOf()
) : BaseEntity() {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KitImage) return false
        if (id != other.id) return false
        return images == other.images
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
class KitAttributes(
    @JsonIgnore
    @NotAudited
    var kit: Kit? = null,
    var otherType: String? = null,
    var pickup: String = "",
    var state: String = "",
    var consent: String = "",
    var notes: String = "",
    var pickupAvailability: String? = null,
    var credentials: String? = null,
    var status: List<String> = listOf(),
    var network: String? = null,
    var otherNetwork: String? = "UNKNOWN"
) {
    @get:JsonIgnore
    val images by lazy { listOf<DeviceImage>() }
}
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

@JsonIgnoreProperties(ignoreUnknown = true)
class DeviceImage(
    val image: String,
    val id: String = RandomStringUtils.random(5, true, true)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DeviceImage) return false
        return id == other.id
    }
}

enum class KitType {
    OTHER,
    LAPTOP,
    DESKTOP,
    TABLET,
    SMARTPHONE,
    ALLINONE,
    CHROMEBOOK,
    COMMSDEVICE
}

enum class KitStatus {
    DONATION_NEW,
    DONATION_DECLINED,
    DONATION_ACCEPTED,
    DONATION_NO_RESPONSE,
    DONATION_ARRANGED,
    PROCESSING_START,
    PROCESSING_WIPED,
    PROCESSING_FAILED_WIPE,
    PROCESSING_OS_INSTALLED,
    PROCESSING_FAILED_INSTALLATION,
    PROCESSING_WITH_TECHIE,
    PROCESSING_MISSING_PART,
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

enum class KitVolunteerType { LOGISTICS, ORGANISER, TECHNICIAN }

@Embeddable
class KitVolunteerId(
    @Column(name = "course_id")
    var kitId: Long,
    @Column(name = "student_id")
    var volunteerId: Long,
    @Column(name = "type")
    @Enumerated(EnumType.STRING)
    var type: KitVolunteerType
) : Serializable {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null) return false
        if (other !is KitVolunteerId) return false
        return type == other.type && kitId == other.kitId && volunteerId == other.volunteerId
    }

    override fun hashCode(): Int {
        return Objects.hash(kitId, volunteerId, type)
    }

    override fun toString(): String {
        return "KitVolunteerId(type=$type, kitId=$kitId, volunteerId=$volunteerId)"
    }
}

@Entity
@Table(name = "kit_volunteers")
class KitVolunteer(
    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("kitId")
    var kit: Kit,
    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("volunteerId")
    var volunteer: Volunteer,
    @EmbeddedId
    var id: KitVolunteerId,
    var createdAt: Instant = Instant.now()
) {
    val type get() = id.type
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        other ?: return false
        if (other !is KitVolunteer) return false
        if (id != other.id) return false
        return kit == other.kit && volunteer == other.volunteer
    }

    override fun hashCode(): Int {
        return Objects.hash(kit, volunteer, id)
    }

    override fun toString(): String {
        return "KitVolunteer(id=$id, enrolledAt=$createdAt)"
    }
}

@Entity
@Table(name = "email_templates")
class EmailTemplate(
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "email-template-seq-generator")
    @SequenceGenerator(
        name = "email-template-seq-generator",
        sequenceName = "email_template_sequence",
        allocationSize = 1
    )
    var id: Long = 0,
    var body: String,
    var subject: String,
    var active: Boolean = true,
    var createdAt: Instant = Instant.now(),
    @UpdateTimestamp
    var updatedAt: Instant = Instant.now()
)

@Entity
@Table(name = "organisations")
class Organisation(
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "organisation-seq-generator")
    @SequenceGenerator(
        name = "organisation-seq-generator",
        sequenceName = "organisation_sequence",
        allocationSize = 1
    )
    var id: Long = 0,
    var name: String,
    // var website: String,
    var contact: String,
    var phoneNumber: String,
    var email: String,
    var address: String,
    var createdAt: Instant = Instant.now(),
    @Formula(
        """
        (SELECT COUNT(*) FROM kits k where k.organisation_id = id)
    """
    )
    var kitCount: Int = 0,
    @UpdateTimestamp
    var updatedAt: Instant = Instant.now(),
    @Type(type = "jsonb")
    @Column(columnDefinition = "jsonb")
    var attributes: OrganisationAttributes = OrganisationAttributes(),
    @Type(type = "yes_no")
    var archived: Boolean = false,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "volunteer_id")
    var volunteer: Volunteer? = null,
    @OneToMany(
        mappedBy = "organisation",
        fetch = FetchType.LAZY,
        cascade = [CascadeType.ALL],
        orphanRemoval = false
    )
    var kits: MutableSet<Kit> = mutableSetOf()
) {
    fun addKit(kit: Kit) {
        kits.add(kit)
        kit.organisation = this
    }

    fun removeKit(kit: Kit) {
        kits.removeIf {
            if (kit == it) {
                kit.organisation = null
                true
            } else {
                false
            }
        }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
class OrganisationAttributes(
    var request: Capacity = Capacity(),
    var alternateRequest: Capacity = Capacity(),
    var accepts: List<String> = listOf(),
    var alternateAccepts: List<String> = listOf(),
    var notes: String = "",
    var details: String = "",
    var isIndividual: Boolean = false,
    var isResident: Boolean = false,
    var needs: List<String> = listOf(),
    var clientRef: String = ""
)
