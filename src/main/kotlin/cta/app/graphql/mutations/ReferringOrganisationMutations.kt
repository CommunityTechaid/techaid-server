package cta.app.graphql.mutations

import com.coxautodev.graphql.tools.GraphQLMutationResolver
import cta.app.ReferringOrganisation
import cta.app.ReferringOrganisationRepository
import cta.toNullable
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated
import javax.persistence.EntityNotFoundException
import javax.validation.Valid
import javax.validation.constraints.NotBlank
import javax.validation.constraints.NotNull

@Component
@Validated
@Transactional
class ReferringOrganisationMutations(
    private val referringOrganisations: ReferringOrganisationRepository
) : GraphQLMutationResolver {
    fun createReferringOrganisation(@Valid data: CreateReferringOrganisationInput): ReferringOrganisation {
        return referringOrganisations.save(data.entity)
    }

    @PreAuthorize("hasAnyAuthority('write:organisations')")
    fun updateReferringOrganisation(@Valid data: UpdateReferringOrganisationInput): ReferringOrganisation {
        val entity = referringOrganisations.findById(data.id).toNullable()
            ?: throw EntityNotFoundException("Unable to locate a organisation with id: ${data.id}")
        return data.apply(entity)
    }

    @PreAuthorize("hasAnyAuthority('delete:organisations')")
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
    var domain: String?,
    var website: String?,
    var phoneNumber: String?,
    var address: String?
) {
    val entity by lazy {
        val org = ReferringOrganisation(
            name = name,
            domain = domain ?: "",
            address = address?: "",
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
    var domain: String? = null,
    var website: String? = null,
    var phoneNumber: String,
    @get:NotBlank
    var address: String,
    val archived: Boolean? = null
) {
    fun apply(entity: ReferringOrganisation): ReferringOrganisation {
        val self = this
        return entity.apply {
            name = self.name
            address = self.address
            domain = self.domain
            website = self.website
            phoneNumber = self.phoneNumber
            archived = self.archived ?: archived
        }
    }
}
