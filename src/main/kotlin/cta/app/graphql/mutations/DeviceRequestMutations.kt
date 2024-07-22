package cta.app.graphql.mutations

import com.coxautodev.graphql.tools.GraphQLMutationResolver
import cta.app.DeviceRequest
import cta.app.DeviceRequestItems
import cta.app.DeviceRequestNeeds
import cta.app.DeviceRequestNote
import cta.app.DeviceRequestNoteRepository
import cta.app.DeviceRequestRepository
import cta.app.DeviceRequestStatus
import cta.app.ReferringOrganisationContactRepository
import cta.app.services.createEmail
import cta.app.services.FilterService
import cta.app.services.MailService
import cta.toNullable
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated
import javax.mail.internet.InternetAddress
import javax.persistence.EntityNotFoundException
import javax.validation.Valid
import javax.validation.constraints.NotNull


@Component
@Validated
@Transactional
class DeviceRequestMutations(

    private val deviceRequests: DeviceRequestRepository,
    private val referringOrganisationContacts: ReferringOrganisationContactRepository,
    private val filterService: FilterService,
    private val deviceRequestNotes: DeviceRequestNoteRepository,
    private val mailService: MailService
) : GraphQLMutationResolver {
    fun createDeviceRequest(@Valid data: CreateDeviceRequestInput): DeviceRequest {

        val referringOrganisationContact = referringOrganisationContacts.findById(data.referringOrganisationContact).toNullable() ?:
        throw EntityNotFoundException("No referring contact found with id: ${data.referringOrganisationContact}")

        //Throw an exception if DEVICE_REQUEST_LIMIT is reached
        if (referringOrganisationContact.requestCount == DEVICE_REQUEST_LIMIT){
            throw RuntimeException("Could not create new requests. This user already has ${DEVICE_REQUEST_LIMIT} requests open")
        }

        val deviceRequest = DeviceRequest(
            deviceRequestItems = data.deviceRequestItems.entity,
            referringOrganisationContact = referringOrganisationContact,
            isSales = data.isSales,
            clientRef = data.clientRef,
            details = data.details,
            deviceRequestNeeds = data.deviceRequestNeeds.entity
        )

        var savedRequest = deviceRequests.save(deviceRequest)

        if(mailService.emailEnabled) {
            acknowledgeSubmission(savedRequest);
        }

        return savedRequest;
    }

    fun formatDeviceRequests(items: DeviceRequestItems) : String {
        var deviceRequest = "";
        if(items.phones ?: 0 > 0) deviceRequest += "Phones: ${items.phones}<br>\n";
        if(items.tablets ?: 0 > 0) deviceRequest += "Tablets: ${items.tablets}<br>\n";
        if(items.laptops ?: 0 > 0) deviceRequest += "Laptops: ${items.laptops}<br>\n";
        if(items.allInOnes ?: 0 > 0) deviceRequest += "All-in-ones: ${items.allInOnes}<br>\n";
        if(items.desktops ?: 0 > 0) deviceRequest += "Desktops: ${items.desktops}<br>\n";
        if(items.other ?: 0 > 0) deviceRequest += "Other: ${items.other}<br>\n";
        if((items.chromebooks ?: 0) > 0) deviceRequest += "Chromebooks: ${items.chromebooks}<br>\n";
        if((items.commsDevices ?: 0) > 0) deviceRequest += "SIM card (6 months, 20GB data, unlimited UK calls): ${items.commsDevices}<br>\n";
        return deviceRequest;
    }

    fun acknowledgeSubmission(request: DeviceRequest) {
        var formattedItems = formatDeviceRequests(request.deviceRequestItems);

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
        val typeFormURL = "https://ghjngk6ao4g.typeform.com/to/TzlNC6kN";
        val emailBody = """
Dear ${request.referringOrganisationContact.fullName} of ${request.referringOrganisationContact.referringOrganisation.name}<br>
<br>
<b>Your Community TechAid reference: ${request.id}. Your client reference: ${request.clientRef}. Your device request(s): <br>
<br>
${formattedItems} <br>
</b> <br>
Thank you for your request. We need you to complete our recipient data form <a href="${typeFormURL}">here</a>.<br> 
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
            to = request.referringOrganisationContact.email,
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
    fun updateDeviceRequest(@Valid data: UpdateDeviceRequestInput): DeviceRequest {
        val entity = deviceRequests.findById(data.id).toNullable()
            ?: throw EntityNotFoundException("Unable to locate a device request with id: ${data.id}")

        return data.apply(entity).apply {

            if (data.referringOrganisationContact != null) {

                val referringOrganisationContact =
                    referringOrganisationContacts.findById(data.referringOrganisationContact).toNullable()
                        ?: throw EntityNotFoundException("No referring organisation was found with id {$data.referringOrganisation}")
                entity.referringOrganisationContact = referringOrganisationContact
            }

            if (data.deviceRequestNote != null) {
                if (data.deviceRequestNote.content !== "") {
                    val volunteer = filterService.userDetails().name.ifBlank {
                        filterService.userDetails().email
                    }
                    val deviceRequestNote = DeviceRequestNote(content = data.deviceRequestNote.content, deviceRequest = this, volunteer = volunteer)
                    deviceRequestNotes.add(deviceRequestNote)
                }

            }
        }


    }

    @PreAuthorize("hasAnyAuthority('delete:organisations')")
    fun deleteDeviceRequest(id: Long): Boolean {
        val entity =
            deviceRequests.findById(id).toNullable()
                ?: throw EntityNotFoundException("No device request with id: $id")
        deviceRequests.delete(entity)
        return true
    }

    companion object {
        const val DEVICE_REQUEST_LIMIT = 3;
    }
}

data class CreateDeviceRequestInput(
    @get:NotNull
    var deviceRequestItems: DeviceRequestItemsInput,
    @get:NotNull
    var referringOrganisationContact: Long,
    var isSales: Boolean,
    var clientRef: String,
    var details: String,
    var deviceRequestNeeds: DeviceRequestNeedsInput
){
}


data class DeviceRequestItemsInput(
    val phones: Int? = 0,
    val tablets: Int? = 0,
    val laptops: Int? = 0,
    val allInOnes: Int? = 0,
    val desktops: Int? = 0,
    val commsDevices: Int? = 0,
    val other: Int? = 0
) {
    val entity by lazy {
        DeviceRequestItems(
            phones = phones ?: 0,
            tablets = tablets ?: 0,
            laptops = laptops ?: 0,
            allInOnes = allInOnes ?: 0,
            desktops = desktops ?: 0,
            commsDevices = commsDevices ?: 0,
            other = other ?: 0
        )
    }
}

data class DeviceRequestNeedsInput(
    var hasInternet: Boolean?,
    var hasMobilityIssues: Boolean?,
    var needQuickStart: Boolean?
) {
    val entity by lazy {
        DeviceRequestNeeds(
            hasInternet = hasInternet,
            hasMobilityIssues = hasMobilityIssues,
            needQuickStart = needQuickStart
        )
    }
}



data class UpdateDeviceRequestInput(
    @get:NotNull
    val id: Long,
    val deviceRequestItems: DeviceRequestItemsInput,
    val referringOrganisationContact: Long? = null,
    val status: DeviceRequestStatus = DeviceRequestStatus.NEW,
    val isSales: Boolean?,
    val clientRef: String,
    val details: String,
    val deviceRequestNote: DeviceRequestNoteInput? = null,
    val deviceRequestNeeds: DeviceRequestNeedsInput
){
    fun apply(entity: DeviceRequest): DeviceRequest {
        val self = this
        return entity.apply {
            deviceRequestItems = self.deviceRequestItems.entity
            status = self.status
            isSales = self.isSales ?: false
            clientRef = self.clientRef
            details = self.details
            deviceRequestNeeds = self.deviceRequestNeeds.entity
        }
    }
}
