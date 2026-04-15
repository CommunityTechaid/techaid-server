package cta.app

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
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
import org.hibernate.type.YesNoConverter
import java.time.Instant

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
        allocationSize = 1,
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
    """,
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
        mappedBy = "referringOrganisation",
    )
    @SQLOrder("updatedAt DESC")
    var referringOrganisationContacts: MutableSet<ReferringOrganisationContact> = mutableSetOf(),
    @NotAudited
    @OneToMany(
        fetch = FetchType.LAZY,
        cascade = [CascadeType.ALL],
        orphanRemoval = true,
        mappedBy = "referringOrganisation",
    )
    @SQLOrder("updatedAt DESC")
    var referringOrganisationNotes: MutableSet<ReferringOrganisationNote> = mutableSetOf(),
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
        allocationSize = 1,
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
    """,
    )
    var requestCount: Int = 0,
    @NotAudited
    @OneToMany(
        fetch = FetchType.LAZY,
        cascade = [CascadeType.ALL],
        orphanRemoval = true,
        mappedBy = "referringOrganisationContact",
    )
    @SQLOrder("updatedAt DESC")
    var referringOrganisationContactNotes: MutableSet<ReferringOrganisationContactNote> = mutableSetOf(),
) {
    companion object {
        const val STATUS = "FINISHED"
    }
}

@Entity
@Table(name = "referring_organisations_notes")
class ReferringOrganisationNote(
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "referring_organisations_note-seq-generator")
    @SequenceGenerator(
        name = "referring_organisations_note-seq-generator",
        sequenceName = "referring_organisations_note_sequence",
        allocationSize = 1,
    )
    var id: Long = 0,
    @Column(name = "content", length = 4096)
    var content: String,
    @CreationTimestamp
    var createdAt: Instant = Instant.now(),
    @UpdateTimestamp
    var updatedAt: Instant = Instant.now(),
    var volunteer: String? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    var referringOrganisation: ReferringOrganisation,
)

@Entity
@Table(name = "referring_organisation_contacts_notes")
class ReferringOrganisationContactNote(
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "referring_organisation_contacts_note-seq-generator")
    @SequenceGenerator(
        name = "referring_organisation_contacts_note-seq-generator",
        sequenceName = "referring_organisation_contacts_note_sequence",
        allocationSize = 1,
    )
    var id: Long = 0,
    @Column(name = "content", length = 4096)
    var content: String,
    @CreationTimestamp
    var createdAt: Instant = Instant.now(),
    @UpdateTimestamp
    var updatedAt: Instant = Instant.now(),
    var volunteer: String? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    var referringOrganisationContact: ReferringOrganisationContact,
)
