package cta.app.graphql.mutations

import com.coxautodev.graphql.tools.GraphQLMutationResolver
import jakarta.persistence.EntityNotFoundException
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import cta.app.Capacity
import cta.app.Organisation
import cta.app.OrganisationAttributes
import cta.app.OrganisationRepository
import cta.app.Volunteer
import cta.app.VolunteerRepository
import cta.app.services.FilterService
import cta.app.services.MailService
import cta.app.services.createEmail
import cta.toNullable
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated

@Component
@Validated
@Transactional
class OrganisationMutations(
    private val organisations: OrganisationRepository,
    private val volunteerRepository: VolunteerRepository,
    private val filterService: FilterService,
    private val mailService: MailService
) : GraphQLMutationResolver {
    fun createOrganisation(@Valid data: CreateOrganisationInput): Organisation {
        // val entity = when {
        //     data.email.isNotBlank() -> organisations.findOne(QOrganisation.organisation.email.eq(data.email)).toNullable()
        //     data.website.isNotBlank() -> organisations.findOne(QOrganisation.organisation.website.eq(data.website)).toNullable()
        //     else -> null
        // }
        // entity?.let { org ->
        //     org.apply {
        //         website = if (data.website.isNotBlank()) data.website else website
        //         email = if (data.email.isNotBlank()) data.email else email
        //         contact = data.contact
        //         attributes = data.attributes?.apply(this) ?: attributes
        //     }
        //     return org
        // }
        return organisations.save(data.entity)
    }

    @PreAuthorize("hasAnyAuthority('write:organisations')")
    fun updateOrganisation(@Valid data: UpdateOrganisationInput): Organisation {
        val entity = organisations.findById(data.id).toNullable()
            ?: throw EntityNotFoundException("Unable to locate a organisation with id: ${data.id}")
        return data.apply(entity).apply {
            if (data.volunteerId == null) {
                volunteer?.removeOrganisation(this)
            } else if (data.volunteerId != volunteer?.id) {
                val owner = volunteerRepository.findById(data.volunteerId).toNullable()
                    ?: throw EntityNotFoundException("Unable to locate a volunteer with id: ${data.volunteerId}")
                owner.addOrganisation(this)
                notifyAssigned(listOf(owner), this)
            }
        }
    }

    @PreAuthorize("hasAnyAuthority('delete:organisations')")
    fun deleteOrganisation(id: Long): Boolean {
        val entity =
            organisations.findById(id).toNullable() ?: throw EntityNotFoundException("No organisation with id: $id")
        organisations.delete(entity)
        return true
    }

    fun notifyAssigned(volunteers: List<Volunteer>, org: Organisation) {
        val user = filterService.userDetails()
        volunteers.filter { it.email.isNotBlank() && it.email != user.email }.forEach { v ->
            val msg = createEmail(
                to = v.email,
                from = mailService.address,
                subject = "Community Techaid: Organisation Assigned",
                bodyText = """
                    Hi ${v.name},
                    
                    ${user.name} assigned you to the organisation ${org.name} https://app.communitytechaid.org.uk/dashboard/organisations/${org.id}.
                    
                    Community Techaid
                """.trimIndent(),
                mimeType = "plain",
                charset = "UTF-8"
            )
            try {
                mailService.sendMessage(msg)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

data class CreateOrganisationInput(
    @get:NotBlank
    val name: String,
    // val website: String,
    val phoneNumber: String,
    val email: String,
    @get:NotBlank
    val address: String,
    @get:NotBlank
    val contact: String,
    val attributes: OrganisationAttributesInput? = null
) {
    val entity by lazy {
        val org = Organisation(
            name = name,
            // website = website,
            phoneNumber = phoneNumber,
            email = email,
            address = address,
            contact = contact
        )
        attributes?.let {
            org.attributes = it.apply(org)
        }
        org
    }
}

data class OrganisationAttributesInput(
    var request: CapacityInput? = null,
    var alternateRequest: CapacityInput? = null,
    var accepts: List<String>? = null,
    var alternateAccepts: List<String>? = null,
    var notes: String? = null,
    var details: String? = null,
    var isIndividual: Boolean? = false,
    var isResident: Boolean? = false,
    var needs: List<String>? = null,
    var clientRef: String? = null

) {
    fun apply(entity: Organisation): OrganisationAttributes {
        val self = this
        return entity.attributes.apply {
            request = self.request?.entity ?: Capacity()
            alternateRequest = self.alternateRequest?.entity ?: Capacity()
            accepts = self.accepts ?: accepts
            notes = self.notes ?: notes
            details = self.details ?: details
            alternateAccepts = self.alternateAccepts ?: alternateAccepts
            isIndividual = self.isIndividual ?: isIndividual
            isResident = self.isResident ?: isResident
            needs = self.needs ?: needs
            clientRef = self.clientRef ?: clientRef
        }
    }
}

data class UpdateOrganisationInput(
    @get:NotNull
    val id: Long,
    @get:NotBlank
    val name: String,
    // val website: String,
    val phoneNumber: String,
    @get:NotBlank
    val email: String,
    @get:NotBlank
    val address: String,
    val contact: String,
    val archived: Boolean? = false,
    val volunteerId: Long? = null,
    val attributes: OrganisationAttributesInput? = null
) {
    fun apply(entity: Organisation): Organisation {
        val self = this
        return entity.apply {
            name = self.name
            // website = self.website
            phoneNumber = self.phoneNumber
            email = self.email
            address = self.address
            contact = self.contact
            archived = self.archived ?: false
            self.attributes?.let { attr ->
                attributes = attr.apply(this)
            }
        }
    }
}
