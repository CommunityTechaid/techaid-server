package cta.app.graphql.mutations

import com.coxautodev.graphql.tools.GraphQLMutationResolver
import javax.persistence.EntityNotFoundException
import javax.validation.Valid
import javax.validation.constraints.NotBlank
import javax.validation.constraints.NotNull
import cta.app.Donor
import cta.app.DonorRepository
import cta.app.DonorParentRepository
import cta.app.QDonor
import cta.app.services.FilterService
import cta.app.services.LocationService
import cta.toNullable
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated

@Component
@Validated
@PreAuthorize("hasAnyAuthority('write:donors')")
@Transactional
class DonorMutations(
    private val donors: DonorRepository,
    private val donorParents: DonorParentRepository,
    private val locationService: LocationService,
    private val filterService: FilterService
) : GraphQLMutationResolver {

    fun createDonor(@Valid data: CreateDonorInput): Donor {
        val donor = fetchDonor(donors, data.entity)
        if (donor.postCode.isNotBlank()) {
            donor.coordinates = locationService.findCoordinates(donor.postCode)
        }

        if (data.donorParentId != null) {
            val donorParent = donorParents.findById(data.donorParentId).toNullable()
                ?: throw EntityNotFoundException("Unable to locate a donor with id: ${data.donorParentId}")
            donorParent.addDonor(donor)
        }

        return donor
    }

    fun updateDonor(@Valid data: UpdateDonorInput): Donor {
        val entity = donors.findOne(filterService.donorFilter().and(QDonor.donor.id.eq(data.id))).toNullable()
            ?: throw EntityNotFoundException("Unable to locate a donor with id: ${data.id}")
        return data.apply(entity).apply {
            if (postCode.isNotBlank() && (coordinates == null || coordinates?.input != postCode)) {
                coordinates = locationService.findCoordinates(postCode)
            }

            if (data.donorParentId == null) {
                donorParent?.removeDonor(this)
            } else if (data.donorParentId != donorParent?.id) {
                val donorParent = donorParents.findById(data.donorParentId).toNullable()
                    ?: throw EntityNotFoundException("Unable to locate a parent donor with id: ${data.donorParentId}")
                donorParent.addDonor(this)
            }

        }
    }

    @PreAuthorize("hasAnyAuthority('delete:donors')")
    fun deleteDonor(id: Long): Boolean {
        val donor = donors.findOne(filterService.donorFilter().and(QDonor.donor.id.eq(id))).toNullable()
            ?: throw EntityNotFoundException("No donor with id: $id")
        donor.kits.forEach { donor.removeKit(it) }
        donors.delete(donor)
        return true
    }
}

data class CreateDonorInput(
    @get:NotBlank
    val postCode: String,
    val phoneNumber: String = "",
    val email: String = "",
    val referral: String = "",
    val name: String,
    val consent: Boolean,
    val donorParentId: Long? = null
) {
    val entity by lazy {
        Donor(
            postCode = postCode,
            phoneNumber = phoneNumber,
            email = email,
            referral = referral,
            name = name,
            consent = consent
        )
    }
}

data class UpdateDonorInput(
    @get:NotNull
    val id: Long,
    val postCode: String? = null,
    val phoneNumber: String,
    val email: String,
    var name: String,
    val referral: String? = null,
    val consent: Boolean? = null,
    val donorParentId: Long? = null,
    val archived: Boolean? = null
) {
    fun apply(entity: Donor): Donor {
        val self = this
        return entity.apply {
            postCode = if (self.postCode == null) postCode else self.postCode.toString()
            phoneNumber = if (phoneNumber != self.phoneNumber) self.phoneNumber else phoneNumber
            email = if (email != self.email) self.email else this.email
            referral = if (self.referral == null) referral else self.referral.toString()
            name = self.name
            consent = if (self.consent == null) consent else self.consent 
            archived = self.archived ?: archived
        }
    }
}
