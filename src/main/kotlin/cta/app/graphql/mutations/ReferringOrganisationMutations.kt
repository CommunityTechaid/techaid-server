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
    val membershipEndDate: String? = null,
) {
    fun apply(
        entity: ReferringOrganisation,
        today: java.time.LocalDate = MembershipPeriods.today(),
    ): ReferringOrganisation {
        val self = this
        // Parsed and validated before any field is assigned so a bad date leaves the entity untouched.
        val newStartDate = self.membershipStartDate?.let { MembershipPeriods.parseDate("membershipStartDate", it) }
        val newEndDate = self.membershipEndDate?.let { MembershipPeriods.parseDate("membershipEndDate", it) }
        val movesToPaid =
            self.membershipTier != null && self.membershipTier != MembershipTier.FREE &&
                self.membershipTier != entity.membershipTier
        val storedEnd = entity.membershipEndDate
        val periodOver = storedEnd == null || storedEnd.isBefore(today)

        var start = newStartDate ?: entity.membershipStartDate
        var end = newEndDate ?: storedEnd
        var datesChanged = newStartDate != null || newEndDate != null
        if (newEndDate == null) {
            if (movesToPaid && periodOver) {
                // Starting (or restarting) a paid membership: with no start given it begins today.
                if (newStartDate == null) start = today
                end = MembershipPeriods.defaultEndFor(start)
                datesChanged = true
            } else if (newStartDate != null) {
                end = MembershipPeriods.defaultEndFor(newStartDate)
            }
        }
        if (datesChanged && end != null && end.isBefore(start)) {
            throw IllegalArgumentException("membershipEndDate ($end) must not be before membershipStartDate ($start)")
        }
        return entity.apply {
            name = self.name
            website = self.website
            phoneNumber = self.phoneNumber ?: phoneNumber
            archived = self.archived ?: archived
            membershipTier = self.membershipTier ?: membershipTier
            membershipStartDate = start
            membershipEndDate = end
        }
    }
}
