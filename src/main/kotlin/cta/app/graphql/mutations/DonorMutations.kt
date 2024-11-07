package cta.app.graphql.mutations

import cta.app.Donor
import cta.app.DonorRepository
import cta.app.QDonor
import cta.app.services.FilterService
import cta.app.services.LocationService
import cta.toNullable
import jakarta.persistence.EntityNotFoundException
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated

@Controller
@Validated
@PreAuthorize("hasAnyAuthority('write:donors')")
@Transactional
class DonorMutations(
    private val donors: DonorRepository,
    private val locationService: LocationService,
    private val filterService: FilterService
) {

    @MutationMapping
    fun createDonor(@Argument @Valid data: CreateDonorInput): Donor {
        val donor = fetchDonor(donors, data.entity)
        if (donor.postCode.isNotBlank()) {
            donor.coordinates = locationService.findCoordinates(donor.postCode)
        }
        return donor
    }

    @MutationMapping
    fun updateDonor(@Argument @Valid data: UpdateDonorInput): Donor {
        val entity = donors.findOne(filterService.donorFilter().and(QDonor.donor.id.eq(data.id))).toNullable()
            ?: throw EntityNotFoundException("Unable to locate a donor with id: ${data.id}")
        return data.apply(entity).apply {
            if (postCode.isNotBlank() && (coordinates == null || coordinates?.input != postCode)) {
                coordinates = locationService.findCoordinates(postCode)
            }
        }
    }

    @PreAuthorize("hasAnyAuthority('delete:donors')")
    @MutationMapping
    fun deleteDonor(@Argument id: Long): Boolean {
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
    val consent: Boolean
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
    val postCode: String,
    val phoneNumber: String,
    val email: String,
    var name: String,
    val referral: String,
    val consent: Boolean
) {
    fun apply(entity: Donor): Donor {
        val self = this
        return entity.apply {
            postCode = self.postCode
            phoneNumber = if (phoneNumber != self.phoneNumber) self.phoneNumber else phoneNumber
            email = if (email != self.email) self.email else this.email
            referral = self.referral
            name = self.name
            consent = self.consent
        }
    }
}
