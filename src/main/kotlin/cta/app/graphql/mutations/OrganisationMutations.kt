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
<p class="c2"><span class="c8">Distributions Team</span></p>
<br>
<p class="c3"><span class="c8">Community TechAid</span></p>
<br>
<p class="c3"><span class="c11"><a class="c7" href="http://communitytechaid.org.uk">communitytechaid.org.uk</a></span></p>
<p class="c10">
<span class="c11"><a class="c7" href="https://twitter.com/CommTechaid">@commtechaid</a></span>
<span class="c12">&nbsp;|</span>
<span class="c11"><a class="c7" href="https://www.facebook.com/CommunityTechAid">@communitytechaid</a></span>
</p>
<p class="c2"><span class="c0"></span></p>
<p class="c3"><span style="overflow: hidden; display: inline-block; margin: 0.00px 0.00px; border: 0.00px solid #000000; transform: rotate(0.00rad) translateZ(0px); -webkit-transform: rotate(0.00rad) translateZ(0px); width: 200.00px; height: 41.33px;">
<img style="width: 200.00px; height: 41.33px; margin-left: 0.00px; margin-top: 0.00px; transform: rotate(0.00rad) translateZ(0px); -webkit-transform: rotate(0.00rad) translateZ(0px);" src="data:image/png;base64,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">
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
