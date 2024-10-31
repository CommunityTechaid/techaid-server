package cta.app.graphql.mutations

import cta.app.ReferringOrganisation
import cta.app.ReferringOrganisationRepository
import cta.toNullable
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated
import jakarta.persistence.EntityNotFoundException
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.stereotype.Controller

@Controller
@Validated
@Transactional
class ReferringOrganisationMutations(
    private val referringOrganisations: ReferringOrganisationRepository
) {
    fun createReferringOrganisation(@Valid data: CreateReferringOrganisationInput): ReferringOrganisation {
        return referringOrganisations.save(data.entity)
    }

    @PreAuthorize("hasAnyAuthority('write:organisations')")
    @MutationMapping
    fun updateReferringOrganisation(@Valid data: UpdateReferringOrganisationInput): ReferringOrganisation {
        val entity = referringOrganisations.findById(data.id).toNullable()
            ?: throw EntityNotFoundException("Unable to locate a organisation with id: ${data.id}")
        return data.apply(entity)
    }

    @PreAuthorize("hasAnyAuthority('delete:organisations')")
    @MutationMapping
    fun deleteReferringOrganisation(id: Long): Boolean {
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
    var phoneNumber: String?
) {
    val entity by lazy {
        val org = ReferringOrganisation(
            name = name,
            website = website ?: "",
            phoneNumber = phoneNumber?: ""
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
    val archived: Boolean? = null
) {
    fun apply(entity: ReferringOrganisation): ReferringOrganisation {
        val self = this
        return entity.apply {
            name = self.name
            website = self.website
            phoneNumber = self.phoneNumber ?: phoneNumber
            archived = self.archived ?: archived
        }
    }
}
