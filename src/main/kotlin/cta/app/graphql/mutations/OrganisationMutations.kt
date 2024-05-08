package cta.app.graphql.mutations

import com.coxautodev.graphql.tools.GraphQLMutationResolver
import javax.mail.internet.InternetAddress
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
        if(request.phones > 0) deviceRequest += "Phones: ${request.phones}<br>\n";
        if(request.tablets > 0) deviceRequest += "Tablets: ${request.tablets}<br>\n";
        if(request.laptops > 0) deviceRequest += "Laptops: ${request.laptops}<br>\n";
        if(request.allInOnes > 0) deviceRequest += "All-in-ones: ${request.allInOnes}<br>\n";
        if(request.desktops > 0) deviceRequest += "Desktops: ${request.desktops}<br>\n";
        if(request.other > 0) deviceRequest += "Other: ${request.other}<br>\n";
        if((request.chromebooks?: 0) > 0) deviceRequest += "Chromebooks: ${request.chromebooks}<br>\n";
        if((request.commsDevices?: 0) > 0) deviceRequest += "SIM card (6 months, 20GB data, unlimited UK calls): ${request.commsDevices}<br>\n";
        return deviceRequest;
    }

    fun acknowledgeSubmission(org: Organisation) {
        var deviceRequest = formatDeviceRequests(org.attributes.request);

        val emailHeader = """
<html>
<head>
<meta content="text/html; charset=UTF-8" http-equiv="content-type">
<style type="text/css">
@import url(https://themes.googleusercontent.com/fonts/css?kit=jcFLf8ZX0K0voV0Wtl8DVwWg0g-wft8BWv_rYzdOKxw);ol{margin:0;padding:0}table td,table th{padding:0}.c4{-webkit-text-decoration-skip:none;color:#1155cc;font-weight:400;text-decoration:underline;vertical-align:baseline;text-decoration-skip-ink:none;font-size:9pt;font-family:"Poppins";font-style:normal}.c9{color:#000000;font-weight:400;text-decoration:none;vertical-align:baseline;font-size:9pt;font-family:"Poppins";font-style:normal}.c2{padding-top:0pt;padding-bottom:0pt;line-height:1.15;orphans:2;widows:2;text-align:left;height:11pt}.c0{color:#000000;font-weight:400;text-decoration:none;vertical-align:baseline;font-size:11pt;font-family:"Arial";font-style:normal}.c6{color:#666666;font-weight:400;text-decoration:none;vertical-align:baseline;font-size:7pt;font-family:"Poppins";font-style:normal}.c8{color:#000000;font-weight:700;text-decoration:none;vertical-align:baseline;font-size:9pt;font-family:"Poppins";font-style:normal}.c1{padding-top:0pt;padding-bottom:0pt;line-height:1.38;orphans:2;widows:2;text-align:left;height:11pt}.c11{-webkit-text-decoration-skip:none;color:#1155cc;font-weight:400;text-decoration:underline;text-decoration-skip-ink:none;font-size:9pt;font-family:"Poppins"}.c3{padding-top:0pt;padding-bottom:0pt;line-height:1.38;orphans:2;widows:2;text-align:left}.c10{padding-top:0pt;padding-bottom:0pt;line-height:1.15;orphans:2;widows:2;text-align:left}.c12{font-size:9pt;font-family:"Poppins";font-weight:400}.c5{background-color:#ffffff;max-width:468pt;padding:72pt 72pt 72pt 72pt}.c7{color:inherit;text-decoration:inherit}.title{padding-top:0pt;color:#000000;font-size:26pt;padding-bottom:3pt;font-family:"Arial";line-height:1.15;page-break-after:avoid;orphans:2;widows:2;text-align:left}.subtitle{padding-top:0pt;color:#666666;font-size:15pt;padding-bottom:16pt;font-family:"Arial";line-height:1.15;page-break-after:avoid;orphans:2;widows:2;text-align:left}li{color:#000000;font-size:11pt;font-family:"Arial"}p{margin:0;color:#000000;font-size:11pt;font-family:"Arial"}h1{padding-top:20pt;color:#000000;font-size:20pt;padding-bottom:6pt;font-family:"Arial";line-height:1.15;page-break-after:avoid;orphans:2;widows:2;text-align:left}h2{padding-top:18pt;color:#000000;font-size:16pt;padding-bottom:6pt;font-family:"Arial";line-height:1.15;page-break-after:avoid;orphans:2;widows:2;text-align:left}h3{padding-top:16pt;color:#434343;font-size:14pt;padding-bottom:4pt;font-family:"Arial";line-height:1.15;page-break-after:avoid;orphans:2;widows:2;text-align:left}h4{padding-top:14pt;color:#666666;font-size:12pt;padding-bottom:4pt;font-family:"Arial";line-height:1.15;page-break-after:avoid;orphans:2;widows:2;text-align:left}h5{padding-top:12pt;color:#666666;font-size:11pt;padding-bottom:4pt;font-family:"Arial";line-height:1.15;page-break-after:avoid;orphans:2;widows:2;text-align:left}h6{padding-top:12pt;color:#666666;font-size:11pt;padding-bottom:4pt;font-family:"Arial";line-height:1.15;page-break-after:avoid;font-style:italic;orphans:2;widows:2;text-align:left}
</style>
</head>
<body class="c5 doc-content">
""";
        val emailBody = """
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
""";

val emailFooter = """
<p class="c2"><span class="c8"><b>Distributions Team</b></span></p>
<br>
<p class="c3"><span class="c8"><b>Community TechAid</b></span></p>
<br>
<p class="c3"><span class="c11"><a class="c7" href="http://communitytechaid.org.uk">communitytechaid.org.uk</a></span></p>
<p class="c10">
<span class="c11"><a class="c7" href="https://twitter.com/CommTechaid">@commtechaid</a></span>
<span class="c12">&nbsp;|</span>
<span class="c11"><a class="c7" href="https://www.facebook.com/CommunityTechAid">@communitytechaid</a></span>
</p>
<p class="c2"><span class="c0"></span></p>
<p class="c3"><span style="overflow: hidden; display: inline-block; margin: 0.00px 0.00px; border: 0.00px solid #000000; transform: rotate(0.00rad) translateZ(0px); -webkit-transform: rotate(0.00rad) translateZ(0px); width: 200.00px; height: 41.33px;">
<img style="width: 200.00px; height: 41.33px; margin-left: 0.00px; margin-top: 0.00px; transform: rotate(0.00rad) translateZ(0px); -webkit-transform: rotate(0.00rad) translateZ(0px);" src="https://static.wixstatic.com/media/8f9418_5ed9a29e823a4fa1af0ab50b88f627ea~mv2.png">
</span></p>
<br>
<p class="c3">
<span class="c6">Community TechAid (also known as Lambeth TechAid) is a registered charity in England and Wales No. 1193210</span></p>
<br>
<p class="c3"><span class="c6">This email and its attachments may be confidential and are intended solely for the use of the intended recipient. If you are not the intended recipient of this email and its attachments, you must take no action based upon them, nor must you copy or show them to anyone. Please contact the sender if you believe you have received this email in error.</span></p>
<br><br><br>
<hr>
<br><br><br>
</body></html>
""";

        val msg = createEmail(
            to = org.email,
            from = mailService.address,
            subject = "Community TechAid: Device Request Acknowledged",
            bodyText = "$emailHeader $emailBody $emailFooter",
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
        return data.apply(entity)
        
        /* .apply {
            if (data.volunteerId == null) {
                volunteer?.removeOrganisation(this)
            } else if (data.volunteerId != volunteer?.id) {
                val owner = volunteerRepository.findById(data.volunteerId).toNullable()
                    ?: throw EntityNotFoundException("Unable to locate a volunteer with id: ${data.volunteerId}")
                owner.addOrganisation(this)
                notifyAssigned(listOf(owner), this)
            }
        } */
    }

    @PreAuthorize("hasAnyAuthority('delete:organisations')")
    fun deleteOrganisation(id: Long): Boolean {
        val entity =
            organisations.findById(id).toNullable() ?: throw EntityNotFoundException("No organisation with id: $id")
        organisations.delete(entity)
        return true
    }

    /* fun notifyAssigned(volunteers: List<Volunteer>, org: Organisation) {
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
    } */
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
