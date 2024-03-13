package cta.app.graphql.mutations

import com.coxautodev.graphql.tools.GraphQLMutationResolver
import cta.app.DeviceRequest
import cta.app.DeviceRequestItems
import cta.app.DeviceRequestRepository
import cta.app.DeviceRequestStatus
import cta.app.ReferringOrganisationContactRepository
import cta.toNullable
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated
import javax.persistence.EntityNotFoundException
import javax.validation.Valid
import javax.validation.constraints.NotNull


@Component
@Validated
@Transactional

class DeviceRequestMutations(

    private val deviceRequests: DeviceRequestRepository,
    private val referringOrganisationContacts: ReferringOrganisationContactRepository
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
            referringOrganisationContact = referringOrganisationContact
        )
        return deviceRequests.save(deviceRequest)
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
    var referringOrganisationContact: Long

)


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

data class UpdateDeviceRequestInput(
    @get:NotNull
    val id: Long,
    val deviceRequestItems: DeviceRequestItemsInput,
    val referringOrganisationContact: Long? = null,
    val status: DeviceRequestStatus = DeviceRequestStatus.NEW
){
    fun apply(entity: DeviceRequest): DeviceRequest {
        val self = this
        return entity.apply {
            deviceRequestItems = self.deviceRequestItems.entity
            status = self.status
        }
    }
}
