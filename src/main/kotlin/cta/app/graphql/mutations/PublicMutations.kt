package cta.app.graphql.mutations

import jakarta.validation.Valid
import cta.app.Donor
import cta.app.DonorRepository
import cta.app.Kit
import cta.app.KitRepository
import cta.app.Volunteer
import cta.app.VolunteerRepository
import cta.app.services.LocationService
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional

fun fetchDonor(repo: DonorRepository, donor: Donor): Donor {
    var existingDonor: Donor? = if (donor.email.trim().isNotBlank()) {
        repo.findByEmail(donor.email) ?: if (donor.phoneNumber.trim().isNotBlank()) {
            repo.findByPhoneNumber(donor.phoneNumber)
        } else null
    } else {
        if (donor.phoneNumber.trim().isNotBlank()) {
            repo.findByPhoneNumber(donor.phoneNumber)
        } else null
    }
    return if (existingDonor != null) {
        repo.save(existingDonor.apply {
            referral = if (donor.referral.isNotBlank()) donor.referral else referral
            postCode = if (donor.postCode.isNotBlank()) donor.postCode else postCode
            email = if (donor.email.isNotBlank()) donor.email else email
            phoneNumber = if (donor.phoneNumber.isNotBlank()) donor.phoneNumber else phoneNumber
        })
    } else {
        repo.save(donor)
    }
}

@Controller
class PublicMutations(
    private val donors: DonorRepository,
    private val kits: KitRepository,
    private val volunteers: VolunteerRepository,
    private val locationService: LocationService
) {
    @MutationMapping
    fun createVolunteer(@Argument @Valid data: CreateVolunteerInput): Volunteer {
        if (data.email.isNotBlank()) {
            volunteers.findByEmail(data.email)?.let {
                throw IllegalArgumentException("A volunteer with the email address ${data.email} already exits!")
            }
        }

        return volunteers.save(data.entity.apply {
            if (postCode.isNotBlank()) {
                coordinates = locationService.findCoordinates(postCode)
            }
        })
    }

    @Transactional
    @MutationMapping
    fun donateItem(@Argument @Valid data: DonateItemInput): DonateItemPayload {
        if (data.kits.isEmpty()) throw IllegalArgumentException("kits cannot be empty")
        var donor = fetchDonor(donors, data.donor.entity).apply {
            if (postCode.isNotBlank()) {
                coordinates = locationService.findCoordinates(postCode)
            }
        }
        val payload = DonateItemPayload(donor)
        data.kits.forEach {
            var kit = kits.save(it.entity.apply {
                if (location.isNotBlank()) {
                    coordinates = locationService.findCoordinates(location)
                }
            })
            donor.addKit(kit)
            payload.kits.add(kit)
        }
        return payload
    }
}

class DonateItemInput(
    val kits: List<CreateKitInput>,
    val donor: CreateDonorInput
)

class DonateItemPayload(
    val donor: Donor,
    val kits: MutableList<Kit> = mutableListOf()
)
