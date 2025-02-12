package cta.app.graphql.mutations

import cta.app.DeviceRequestRepository
import cta.app.DonorRepository
import cta.app.Kit
import cta.app.KitAttributes
import cta.app.KitRepository
import cta.app.KitStatus
import cta.app.KitStorageType
import cta.app.KitSubStatus
import cta.app.KitType
import cta.app.Note
import cta.app.QKit
import cta.app.services.FilterService
import cta.app.services.KitService
import cta.app.services.LocationService
import cta.app.services.MailService
import cta.toNullable
import jakarta.persistence.EntityNotFoundException
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated

@Controller
@Validated
@PreAuthorize("hasAnyAuthority('write:kits')")
@Transactional
class KitMutations(
    private val kits: KitRepository,
    private val donors: DonorRepository,
    private val deviceRequests: DeviceRequestRepository,
    private val locationService: LocationService,
    private val filterService: FilterService,
    private val mailService: MailService,
    private val kitService: KitService
) {

    @MutationMapping
    fun createKit(@Argument @Valid data: CreateKitInput): Kit {
        val details = filterService.userDetails()
        val kit = kits.save(data.entity.apply {
            if (location.isNotBlank()) {
                coordinates = locationService.findCoordinates(location)
            }

            if (data.note != null) {
                if (data.note.content !== "") {
                    val note = Note(content = data.note.content, kit = this, volunteer = details.email)
                    notes.add(note)
                }
            }

            if (data.donorId != null) {
                val user = donors.findById(data.donorId).toNullable()
                    ?: throw EntityNotFoundException("Unable to locate a donor with id: ${data.donorId}")
                user.addKit(this)
            }
        })
        return kit
    }

    @MutationMapping
    fun quickCreateKit(@Argument @Valid data: QuickCreateKitInput): Kit {
        val kit = kits.save(data.entity.apply {
            if (data.donorId != null) {
                val user = donors.findById(data.donorId).toNullable()
                    ?: throw EntityNotFoundException("Unable to locate a donor with id: ${data.donorId}")
                user.addKit(this)
            }

            //For backwards compatibility, however these will be removed imminently
            attributes.consent = "NO"
            attributes.pickup = "NOTSURE"
        })

        return kit
    }

    @MutationMapping
    fun updateKit(@Argument @Valid data: UpdateKitInput): Kit {
        val self = this
        val entity = kits.findOne(filterService.kitFilter().and(QKit.kit.id.eq(data.id))).toNullable()
            ?: throw EntityNotFoundException("Unable to locate a kit with id: ${data.id}")

        val previousStatus = entity.status
        return data.apply(entity).apply {
            if (location != null && location.isNotBlank() && (coordinates == null || coordinates?.input != location)) {
                coordinates = locationService.findCoordinates(location)
            }

            if (data.donorId == null) {
                donor?.removeKit(this)
            } else if (data.donorId != donor?.id) {
                val user = donors.findById(data.donorId).toNullable()
                    ?: throw EntityNotFoundException("Unable to locate a donor with id: ${data.donorId}")
                user.addKit(this)
            }

            if (data.deviceRequestId == null) {
                deviceRequest?.removeKit(this)
            } else if (data.deviceRequestId != deviceRequest?.id) {
                val devRequest = deviceRequests.findById(data.deviceRequestId).toNullable()
                    ?: throw EntityNotFoundException("Unable to locate a device request with id: ${data.deviceRequestId}")
                devRequest.addKit(this)
            }

            if (data.note != null) {
                if (data.note.content !== "") {
                    val volunteer = filterService.userDetails().name.ifBlank {
                        filterService.userDetails().email
                    }
                    val note = Note(content = data.note.content, kit = this, volunteer = volunteer)
                    notes.add(note)
                }

            }
        }
    }


    @MutationMapping
    fun autoCreateKit(@Argument @Valid data: AutoCreateKitInput): Kit {

        val entity = kits.findOne(filterService.kitFilter().and(QKit.kit.serialNo.eq(data.serialNo))).toNullable()

        /**
         * Create kit only if another Kit with the serial number does not exist. The philosophy is that as far as the
         * auto create script is concerned, the serial number is unique and if it is not, it is an edge case that falls
         * beyond the domain of it and requires manual intervention. We DO NOT want the script silently replacing Kit
         * details in case of a serialNo collision.
         */
        if (entity != null) {
            throw RuntimeException("Serial ${data.serialNo} exists with CTA ID# ${entity.id}");
        }

        return kits.save(data.entity.apply {
            if (data.donorId != null){
                val donor = donors.findById(data.donorId).toNullable() ?: throw EntityNotFoundException("Unable to locate a donor with id: ${data.donorId}")
                data.entity.donor = donor;
            }
        })

    }

    @MutationMapping
    fun autoUpdateKit(@Argument @Valid data: AutoUpdateKitInput): Kit {
        val self = this
        val entity = kits.findOne(filterService.kitFilter().and(QKit.kit.id.eq(data.id))).toNullable()
            ?: throw RuntimeException("Unable to locate a kit with CTA id: ${data.id}")

        return data.apply(entity);
    }

    @PreAuthorize("hasAnyAuthority('delete:kits')")
    @MutationMapping
    fun deleteKit(@Argument id: Long): Boolean {
        val kit = kits.findOne(filterService.kitFilter().and(QKit.kit.id.eq(id))).toNullable()
            ?: throw EntityNotFoundException("Unable to locate a kit with id: $id")
        kits.delete(kit)
        return true
    }
}

data class QuickCreateKitInput(
    val type: KitType,
    @get:NotBlank
    val model: String = "",
    val donorId: Long?
){

    val entity by lazy {
        val kit = Kit(
            type = type,
            age = 0,
            model = model,
            subStatus = KitSubStatus()
        )
        kit
    }

}
data class CreateKitInput(
    val type: KitType,
    val otherType: String? = null,
    val status: KitStatus? = null,
    @get:NotBlank
    val model: String = "",
    val location: String?,
    val age: Int,
    val attributes: KitAttributesInput,
    val donorId: Long? = null,
    val note: CreateNoteInput? = null,
    val make: String? = null,
    val deviceVersion: String? = null,
    val serialNo: String? = null,
    val storageCapacity: Int? = null,
    val typeOfStorage: KitStorageType? = null,
    val ramCapacity: Int? = null,
    val cpuType: String? = null,
    val tpmVersion: String? = null,
    val cpuCores: Int? = null,
    val batteryHealth: Int? = null
) {
    val entity by lazy {
        val kit = Kit(
            type = type,
            status = status ?: KitStatus.DONATION_NEW,
            model = model,
            age = age,
            make = make,
            deviceVersion = deviceVersion,
            serialNo = serialNo,
            storageCapacity = storageCapacity,
            typeOfStorage = typeOfStorage ?: KitStorageType.UNKNOWN,
            ramCapacity = ramCapacity,
            cpuType = cpuType,
            tpmVersion = tpmVersion,
            cpuCores = cpuCores,
            batteryHealth = batteryHealth
        )
        //kit.attributes = attributes.apply(kit)
        kit
    }
}

data class KitAttributesInput(
    val otherType: String? = null,
    val state: String? = null,
    val consent: String? = null,
    val pickup: String? = null,
    val notes: String? = null,
    val pickupAvailability: String? = null,
    val credentials: String? = null,
    val status: List<String>? = null,
    val network: String? = null,
    val otherNetwork: String? = null
) {
    fun apply(entity: Kit): KitAttributes {
        val self = this

        return entity.attributes.apply {
            otherType = self.otherType
            state = self.state
            consent = self.consent
            pickup = self.pickup
            pickupAvailability = self.pickupAvailability
            status = self.status ?: status
            credentials = self.credentials
            notes = self.notes ?: notes
            network = self.network ?: "UNKNOWN"
        }
    }
}

data class UpdateKitInput(
    @get:NotNull
    val id: Long,
    val type: KitType,
    val status: KitStatus,
    @get:NotBlank
    val model: String = "",
    val location: String?,
    val age: Int? = null,
    val attributes: KitAttributesInput = KitAttributesInput(),
    val donorId: Long? = null,
    val deviceRequestId: Long? = null,
    val archived: Boolean? = null,
    val note: CreateNoteInput? = null,
    val make: String? = null,
    val deviceVersion: String? = null,
    val serialNo: String? = null,
    val storageCapacity: Int? = null,
    val typeOfStorage: KitStorageType = KitStorageType.UNKNOWN,
    val ramCapacity: Int? = null,
    val cpuType: String? = null,
    val tpmVersion: String? = null,
    val cpuCores: Int? = null,
    val batteryHealth: Int? = null,
    val subStatus: KitSubStatusInput = KitSubStatusInput()
) {
    fun apply(entity: Kit): Kit {
        val self = this
        return entity.apply {
            type = self.type
            status = self.status
            model = self.model ?: model
            age = self.age ?: age
            attributes = attributes
            archived = self.archived ?: archived
            make = self.make ?: make
            deviceVersion = self.deviceVersion ?: deviceVersion
            serialNo = self.serialNo ?: serialNo
            storageCapacity = self.storageCapacity ?: storageCapacity
            typeOfStorage = self.typeOfStorage ?: (typeOfStorage ?: KitStorageType.UNKNOWN)
            ramCapacity = self.ramCapacity ?: ramCapacity
            cpuType = self.cpuType ?: cpuType
            tpmVersion = self.tpmVersion ?: tpmVersion
            cpuCores = self.cpuCores ?: cpuCores
            batteryHealth = self.batteryHealth ?: batteryHealth
            subStatus = self.subStatus.apply(entity)
        }
    }
}

data class AutoCreateKitInput(
    val type: KitType,
    val model: String = "",
    val status: KitStatus = KitStatus.DONATION_NEW,
    val make: String? = null,
    val deviceVersion: String? = null,
    @get:NotBlank
    val serialNo: String? = null,
    val donorId: Long?,
    val storageCapacity: Int? = null,
    val typeOfStorage: KitStorageType = KitStorageType.UNKNOWN,
    val ramCapacity: Int? = null,
    val cpuType: String? = null,
    val tpmVersion: String? = null,
    val cpuCores: Int? = null,
    val batteryHealth: Int? = null
) {
    val entity by lazy {
        val kit = Kit(
            type = type,
            model = model,
            status = status,
            make = make,
            deviceVersion = deviceVersion,
            serialNo = serialNo,
            storageCapacity = storageCapacity,
            typeOfStorage = typeOfStorage,
            ramCapacity = ramCapacity,
            cpuType = cpuType,
            tpmVersion = tpmVersion,
            cpuCores = cpuCores,
            age = 0,
            location = "",
            batteryHealth = batteryHealth
        )
        kit
    }
}

data class AutoUpdateKitInput(
    val id: Long,
    val type: KitType?,
    val model: String?,
    val status: KitStatus?,
    val make: String?,
    val deviceVersion: String?,
    val serialNo: String?,
    val storageCapacity: Int?,
    val typeOfStorage: KitStorageType?,
    val ramCapacity: Int?,
    val cpuType: String?,
    val tpmVersion: String?,
    val cpuCores: Int?,
    val batteryHealth: Int?,
    val subStatus: KitSubStatusInput = KitSubStatusInput()
) {
    fun apply(entity: Kit): Kit {
        val self = this
        return entity.apply {
            type = self.type ?: type ?: (type ?: KitType.OTHER)
            model = self.model ?: model
            status = self.status ?: status ?: (status ?: KitStatus.DONATION_NEW)
            make = self.make  ?: make
            deviceVersion = self.deviceVersion ?: deviceVersion
            serialNo = self.serialNo ?: serialNo
            storageCapacity = self.storageCapacity ?: storageCapacity
            typeOfStorage = self.typeOfStorage ?: (typeOfStorage ?: KitStorageType.UNKNOWN)
            ramCapacity = self.ramCapacity ?: ramCapacity
            cpuType = self.cpuType ?: cpuType
            tpmVersion = self.tpmVersion ?: tpmVersion
            cpuCores = self.cpuCores ?: cpuCores
            batteryHealth = self.batteryHealth ?: batteryHealth
            subStatus = self.subStatus.apply(entity)
        }
    }
}

data class KitSubStatusInput(
    var installationOfOSFailed: Boolean = false,
    var wipeFailed: Boolean = false,
    var needsSparePart: Boolean = false,
    var needsFurtherInvestigation: Boolean = false,
    var network: String? = null,
    var installedOSName: String? = null,
    var lockedToUser: Boolean = false
    )
{
    fun apply(entity: Kit): KitSubStatus {
        val self = this
        if(entity.subStatus == null) {
            entity.subStatus = KitSubStatus()
        }

        return entity.subStatus.apply {
            installationOfOSFailed = self.installationOfOSFailed
            wipeFailed = self.wipeFailed
            needsSparePart = self.needsSparePart
            needsFurtherInvestigation = self.needsFurtherInvestigation
            network = self.network
            installedOSName = self.installedOSName
            lockedToUser = self.lockedToUser
        }
    }
}


