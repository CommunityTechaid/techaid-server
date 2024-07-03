package cta.app.graphql.mutations

import com.coxautodev.graphql.tools.GraphQLMutationResolver
import cta.app.ReferringOrganisationContact
import cta.app.ReferringOrganisationContactRepository
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

class ReferringOrganisationContactMutations(

    private val referringOrganisationContacts: ReferringOrganisationContactRepository,
    private val referringOrganisations: ReferringOrganisationRepository
) : GraphQLMutationResolver {
    fun createReferringOrganisationContact(@Valid data: CreateReferringOrganisationContactInput): ReferringOrganisationContact {

        val referringOrganisation = referringOrganisations.findById(data.referringOrganisation).toNullable()
            ?: throw EntityNotFoundException("No referring organisation was found with id {$data.referringOrganisation}")

        val referringOrganisationContact = ReferringOrganisationContact (
            firstName = data.firstName,
            surname = data.surname,
            email = data.email,
            phoneNumber = data.phoneNumber,
            referringOrganisation = referringOrganisation
        )

        return referringOrganisationContacts.save(referringOrganisationContact)
    }

    @PreAuthorize("hasAnyAuthority('write:organisations')")
    fun updateReferringOrganisationContact(@Valid data: UpdateReferringOrganisationContactInput): ReferringOrganisationContact {
        val entity = referringOrganisationContacts.findById(data.id).toNullable()
            ?: throw EntityNotFoundException("Unable to locate a organisation contact with id: ${data.id}")

        if (entity.referringOrganisation.id != data.referringOrganisation){
            val referringOrganisation = referringOrganisations.findById(data.referringOrganisation).toNullable()
                ?: throw EntityNotFoundException("No referring organisation was found with id {$data.referringOrganisation}")

            entity.referringOrganisation = referringOrganisation
        }

        return data.apply(entity)
    }

    @PreAuthorize("hasAnyAuthority('delete:organisations')")
    fun deleteReferringOrganisationContact(id: Long): Boolean {
        val entity =
            referringOrganisationContacts.findById(id).toNullable()
                ?: throw EntityNotFoundException("No referring organisation contact with id: $id")
        referringOrganisationContacts.delete(entity)
        return true
    }
}

data class CreateReferringOrganisationContactInput(
    @get:NotBlank
    var firstName: String,
    @get:NotBlank
    var surname: String,
    @get:NotBlank
    var email: String = "",
    var phoneNumber: String,
    @get:NotNull
    var referringOrganisation: Long

)


data class UpdateReferringOrganisationContactInput(
    @get:NotNull
    val id: Long,
    @get:NotBlank
    var firstName: String,
    @get:NotBlank
    var surname: String,
    @get:NotBlank
    var email: String = "",
    var phoneNumber: String,
    @get:NotNull
    var referringOrganisation: Long,
    val archived: Boolean? = null
) {
    fun apply(entity: ReferringOrganisationContact): ReferringOrganisationContact {
        val self = this
        return entity.apply {
            firstName = self.firstName
            surname  = self.surname
            email = self.email
            phoneNumber = self.phoneNumber
            archived = self.archived
        }
    }
}
