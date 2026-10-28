package cta.app.graphql.mutations

import cta.app.MembershipPeriods
import cta.app.MembershipTier
import cta.app.ReferringOrganisation
import cta.app.ReferringOrganisationRepository
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
@Transactional
class ReferringOrganisationMutations(
    private val referringOrganisations: ReferringOrganisationRepository,
) {
    @PreAuthorize("hasAnyAuthority('write:organisations')")
    @MutationMapping
    fun createReferringOrganisation(
        @Argument @Valid data: CreateReferringOrganisationInput,
    ): ReferringOrganisation = referringOrganisations.save(data.entity)

    @PreAuthorize("hasAnyAuthority('write:organisations')")
    @MutationMapping
    fun updateReferringOrganisation(
        @Argument @Valid data: UpdateReferringOrganisationInput,
    ): ReferringOrganisation {
        val entity =
            referringOrganisations.findById(data.id).toNullable()
                ?: throw EntityNotFoundException("Unable to locate a organisation with id: ${data.id}")
        return data.apply(entity)
    }

    @PreAuthorize("hasAnyAuthority('delete:organisations')")
    @MutationMapping
    fun deleteReferringOrganisation(
        @Argument id: Long,
    ): Boolean {
        val entity =
            referringOrganisations.findById(id).toNullable()
                ?: throw EntityNotFoundException("No referring organisation with id: $id")
        referringOrganisations.delete(entity)
        return true
    }
}

data class CreateReferringOrganisationInput(
    @get:NotBlank
    var name: String,
    var website: String?,
    var phoneNumber: String?,
) {
    val entity by lazy {
        val org =
            ReferringOrganisation(
                name = name,
                website = website ?: "",
                phoneNumber = phoneNumber ?: "",
            )
        org
    }
}

data class UpdateReferringOrganisationInput(
    @get:NotNull
    val id: Long,
    @get:NotBlank
    var name: String,
    var website: String? = null,
    var phoneNumber: String? = null,
    val archived: Boolean? = null,
    // Null or absent = unchanged. Several dashboard callers update organisations without knowing
    // about membership; full-replace semantics would silently reset the tier and start date.
    val membershipTier: MembershipTier? = null,
    val membershipStartDate: String? = null,
) {
    fun apply(entity: ReferringOrganisation): ReferringOrganisation {
        val self = this
        // Parsed before any field is assigned so a bad date leaves the entity untouched.
        val newStartDate = self.membershipStartDate?.let { MembershipPeriods.parseDate("membershipStartDate", it) }
        return entity.apply {
            name = self.name
            website = self.website
            phoneNumber = self.phoneNumber ?: phoneNumber
            archived = self.archived ?: archived
            membershipTier = self.membershipTier ?: membershipTier
            membershipStartDate = newStartDate ?: membershipStartDate
        }
    }
}
