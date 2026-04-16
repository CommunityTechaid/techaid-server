package cta.app

import cta.app.services.Coordinates
import jakarta.persistence.CascadeType
import jakarta.persistence.Convert
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
import org.hibernate.annotations.Formula
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.UpdateTimestamp
import org.hibernate.envers.AuditTable
import org.hibernate.envers.Audited
import org.hibernate.envers.NotAudited
import org.hibernate.envers.RelationTargetAuditMode
import org.hibernate.type.SqlTypes
import org.hibernate.type.YesNoConverter
import java.time.Instant

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
    """,
    )
    var kitCount: Int = 0,
    @UpdateTimestamp
    var updatedAt: Instant = Instant.now(),
    @NotAudited
    @JdbcTypeCode(SqlTypes.JSON)
    var coordinates: Coordinates? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "donor_parent_id")
    var donorParent: DonorParent? = null,
    @NotAudited
    @OneToMany(
        mappedBy = "donor",
        fetch = FetchType.LAZY,
        cascade = [CascadeType.ALL],
        orphanRemoval = false,
    )
    var kits: MutableSet<Kit> = mutableSetOf(),
    @Convert(converter = YesNoConverter::class)
    var archived: Boolean = false,
    var isLeadContact: Boolean = false,
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
    DROPPOINT,
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
    """,
    )
    var donorCount: Int = 0,
    @NotAudited
    @OneToMany(
        mappedBy = "donorParent",
        fetch = FetchType.LAZY,
        cascade = [CascadeType.ALL],
        orphanRemoval = false,
    )
    var donors: MutableSet<Donor> = mutableSetOf(),
    @Enumerated(EnumType.STRING)
    var type: DonorParentType? = DonorParentType.DROPPOINT,
    @Convert(converter = YesNoConverter::class)
    var archived: Boolean = false,
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
