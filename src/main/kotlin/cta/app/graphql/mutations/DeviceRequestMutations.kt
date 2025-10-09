package cta.app.graphql.mutations

import cta.app.DeviceRequest
import cta.app.DeviceRequestItems
import cta.app.DeviceRequestNeeds
import cta.app.DeviceRequestNote
import cta.app.DeviceRequestNoteRepository
import cta.app.DeviceRequestRepository
import cta.app.DeviceRequestStatus
import cta.app.ReferringOrganisationContactRepository
import cta.app.services.FilterService
import cta.app.services.MailService
import cta.toNullable
import graphql.GraphQLError
import graphql.GraphqlErrorBuilder
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLType
import graphql.schema.GraphQLTypeUtil

import jakarta.persistence.EntityNotFoundException
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import org.springframework.graphql.data.method.annotation.Argument

import org.springframework.graphql.data.method.annotation.GraphQlExceptionHandler
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.execution.ErrorType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated

public class ExceededDeviceRequestLimitException : RuntimeException {
    
    public constructor() : super("Exceeded device request limit") {
    }

    public constructor(message: String) : super(message) {
    }
}

@ControllerAdvice
class ControllerExceptionHandler {

    @GraphQlExceptionHandler // example for MyException
    fun handleMyError(ex: ExceededDeviceRequestLimitException): GraphQLError {
    return GraphqlErrorBuilder.newError()
            .errorType(ErrorType.BAD_REQUEST) 
            .message(ex.message)
            .build()
    }
}

//     @GraphQlExceptionHandler
// public GraphQLError handle(IllegalArgumentException ex) {
// return GraphQLError.newError().errorType(ErrorType.BAD_REQUEST).message("Handled an
// IllegalArgumentException!").build();
// }
// }



@Controller
@Validated
@Transactional
class DeviceRequestMutations(
    private val deviceRequests: DeviceRequestRepository,
    private val referringOrganisationContacts: ReferringOrganisationContactRepository,
    private val filterService: FilterService,
    private val deviceRequestNotes: DeviceRequestNoteRepository,
    private val mailService: MailService
)  {

    @MutationMapping
    fun createDeviceRequest(@Argument @Valid data: CreateDeviceRequestInput): DeviceRequest {

        val referringOrganisationContact =
            referringOrganisationContacts.findById(data.referringOrganisationContact).toNullable()
                ?: throw EntityNotFoundException("No referring contact found with id: ${data.referringOrganisationContact}")

        //Throw an exception if DEVICE_REQUEST_LIMIT is reached
        if (referringOrganisationContact.requestCount >= DEVICE_REQUEST_LIMIT) {
            throw ExceededDeviceRequestLimitException("Could not create new requests. This user already has ${DEVICE_REQUEST_LIMIT} requests open")
        }

        val deviceRequest = DeviceRequest(
            deviceRequestItems = data.deviceRequestItems.entity,
            referringOrganisationContact = referringOrganisationContact,
            isSales = data.isSales ?: false,
            clientRef = data.clientRef,
            borough = data.borough ?: "",
            details = data.details,
            deviceRequestNeeds = data.deviceRequestNeeds?.entity,
            correlationId = generateCorrelationId()
        )

        return deviceRequests.save(deviceRequest);
    }

    private fun generateCorrelationId(): Long {
        return kotlin.random.Random.nextLong(1, Long.MAX_VALUE)
    }



    @PreAuthorize("hasAnyAuthority('write:organisations')")
    @MutationMapping
    fun updateDeviceRequest(@Argument @Valid data: UpdateDeviceRequestInput): DeviceRequest {
        val entity = deviceRequests.findById(data.id).toNullable()
            ?: throw EntityNotFoundException("Unable to locate a device request with id: ${data.id}")

        return data.apply(entity).apply {

            if (data.referringOrganisationContactId != null) {

                val referringOrganisationContact =
                    referringOrganisationContacts.findById(data.referringOrganisationContactId).toNullable()
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
    @MutationMapping
    fun deleteDeviceRequest(@Argument id: Long): Boolean {
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
    var isSales: Boolean?,
    var clientRef: String,
    var borough: String?,
    var details: String,
    var deviceRequestNeeds: DeviceRequestNeedsInput? = null
){
}


data class DeviceRequestItemsInput(
    val phones: Int? = 0,
    val tablets: Int? = 0,
    val laptops: Int? = 0,
    val allInOnes: Int? = 0,
    val desktops: Int? = 0,
    val commsDevices: Int? = 0,
    val other: Int? = 0,
    val broadbandHubs: Int? = 0
) {
    val entity by lazy {
        DeviceRequestItems(
            phones = phones ?: 0,
            tablets = tablets ?: 0,
            laptops = laptops ?: 0,
            allInOnes = allInOnes ?: 0,
            desktops = desktops ?: 0,
            commsDevices = commsDevices ?: 0,
            other = other ?: 0,
            broadbandHubs = broadbandHubs ?: 0
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
    val referringOrganisationContactId: Long? = null,
    val status: DeviceRequestStatus = DeviceRequestStatus.NEW,
    val isSales: Boolean?,
    val clientRef: String,
    val borough: String?,
    val details: String,
    val deviceRequestNote: DeviceRequestNoteInput? = null,
    val deviceRequestNeeds: DeviceRequestNeedsInput? = null
){
    fun apply(entity: DeviceRequest): DeviceRequest {
        val self = this
        return entity.apply {
            deviceRequestItems = self.deviceRequestItems.entity
            status = self.status
            isSales = self.isSales ?: false
            clientRef = self.clientRef
            borough = self.borough ?: entity.borough
            details = self.details
            deviceRequestNeeds = self.deviceRequestNeeds?.entity ?: entity.deviceRequestNeeds
        }
    }
}
