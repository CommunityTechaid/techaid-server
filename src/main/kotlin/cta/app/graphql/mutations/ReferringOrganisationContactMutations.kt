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
import jakarta.persistence.EntityNotFoundException
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull


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

        /**
         * We add another level of check here to verify that a user with the same full name and email does not already exist.
         * We do this to ensure that no duplicate users have the same full name and email ID due to errors in the frontend
         */
        var referringOrganisationContact: ReferringOrganisationContact?;
        try {
            referringOrganisationContact = referringOrganisationContacts.findOneByFullNameAndEmailAndReferringOrganisation(data.fullName, data.email, referringOrganisation)
            if (referringOrganisationContact != null){
                return referringOrganisationContact
            }
        }catch (exception: Exception){
            throw RuntimeException("There was an error when saving the details of user " + data.fullName + ". Please contact CTA.")
        }

        referringOrganisationContact = ReferringOrganisationContact (
            fullName = data.fullName,
            address = data.address ?: "",
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

        return data.apply(entity).apply {
            if (entity.referringOrganisation.id != data.referringOrganisationId){
                val referringOrganisation = referringOrganisations.findById(data.referringOrganisationId).toNullable()
                    ?: throw EntityNotFoundException("No referring organisation was found with id {$data.referringOrganisation}")
    
                referringOrganisation.addContact(this);
            }
        }
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
    var fullName: String,
    var address: String? = null,
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
    var fullName: String,
    var address: String? = null,
    @get:NotBlank
    var email: String = "",
    var phoneNumber: String,
    @get:NotNull
    var referringOrganisationId: Long,
    val archived: Boolean? = null
) {
    fun apply(entity: ReferringOrganisationContact): ReferringOrganisationContact {
        val self = this
        return entity.apply {
            fullName = self.fullName
            address  = self.address ?: ""
            email = self.email
            phoneNumber = self.phoneNumber
            archived = self.archived ?: archived
        }
    }
}
