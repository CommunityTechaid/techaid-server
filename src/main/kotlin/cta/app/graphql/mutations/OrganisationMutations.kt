package cta.app.graphql.mutations

import com.coxautodev.graphql.tools.GraphQLMutationResolver
import javax.persistence.EntityNotFoundException
import javax.validation.Valid
import javax.validation.constraints.NotBlank
import javax.validation.constraints.NotNull
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
import javax.mail.internet.InternetAddress
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
        
        var savedOrg = organisations.save(data.entity);
        if(mailService.emailEnabled) {
            acknowledgeSubmission(savedOrg);
        }
        return savedOrg;
    }

    fun formatDeviceRequests(request: Capacity) : String {
        var deviceRequest = "";
        if(request.phones > 0) deviceRequest += "Phones: ${request.phones}\n";
        if(request.tablets > 0) deviceRequest += "Tablets: ${request.tablets}\n";
        if(request.laptops > 0) deviceRequest += "Laptops: ${request.laptops}\n";
        if(request.allInOnes > 0) deviceRequest += "All-in-ones: ${request.allInOnes}\n";
        if(request.desktops > 0) deviceRequest += "Desktops: ${request.desktops}\n";
        if(request.other > 0) deviceRequest += "Other: ${request.other}\n";
        if((request.chromebooks?: 0) > 0) deviceRequest += "Chromebooks: ${request.chromebooks}\n";
        if((request.commsDevices?: 0) > 0) deviceRequest += "SIM card (6 months, 20GB data, unlimited UK calls): ${request.commsDevices}\n";
        return deviceRequest;
    }

    fun acknowledgeSubmission(org: Organisation) {
        var deviceRequest = formatDeviceRequests(org.attributes.request);
        val msg = createEmail(
            to = org.email,
            from = mailService.address,
            subject = "Community TechAid: Device Request Acknowledged",
            bodyText = """
            Dear ${org.name}<br>
            <br>
            <b>Your Community TechAid reference: ${org.id}. Your client reference: ${org.attributes.clientRef}. Your device request(s): <br>
            <br>
            ${deviceRequest} <br>
            </b> <br>
            Thank you for your request. We need you to complete our recipient data form <a href="https://ghjngk6ao4g.typeform.com/to/TzlNC6kN">here</a>.<br> 
            <br>
            The request should be for <b>one individual</b> and they must be a resident of Lambeth or Southwark. Please make <b>no more than 3 requests</b> at a time. If you have made more than 3, we will have to close your other requests down and ask that you resubmit them when your first 3 have been completed. <br>
            <br>            
            Your request will take between 4-6 weeks to fulfil after we have received your data form. If we do not have this back within 7 days we will be unable to continue with your request and it will be closed down. <br>
            <br>
            If you have any questions, please email <u><a href="mailto:distributions@communitytechaid.org.uk">distributions@communitytechaid.org.uk</a></u> or call 020 3488 7724. <br>
            <br>
            Best wishes <br>
            <br>
            <b>Community TechAid Distribution Team</b>
            """.trimIndent(),
            mimeType = "html",
            charset = "UTF-8"
        )

        if(!mailService.bcc_address.isNullOrEmpty()) {
            msg.addRecipient(
                javax.mail.Message.RecipientType.BCC,
                InternetAddress(mailService.bcc_address))
        }
        
        try {
            mailService.sendMessage(msg)
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
