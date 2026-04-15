package cta.app.graphql.mutations

import cta.app.Kit
import cta.app.KitAttributes
import cta.app.KitStatus
import cta.app.KitStorageType
import cta.app.KitSubStatus
import cta.app.KitType
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.time.Instant

data class QuickCreateKitInput(
    val type: KitType,
    val make: String = "",
    @get:NotBlank
    val model: String = "",
    val donorId: Long?,
    val lotId: String? = null,
    val locationCode: String? = null,
) {
    val entity by lazy {
        val kit =
            Kit(
                type = type,
                age = 0,
                make = make,
                model = model,
                lotId = lotId,
                locationCode = locationCode,
                subStatus = KitSubStatus(),
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
    val batteryHealth: Int? = null,
    val lotId: String? = null,
    val locationCode: String? = null,
) {
    val entity by lazy {
        val kit =
            Kit(
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
                batteryHealth = batteryHealth,
                lotId = lotId,
                locationCode = locationCode,
            )
        // kit.attributes = attributes.apply(kit)
        kit
    }
}

data class KitAttributesInput(
    val otherType: String? = null,
    val state: String? = null,
    val notes: String? = null,
    val credentials: String? = null,
    val status: List<String>? = null,
    val network: String? = null,
    val otherNetwork: String? = null,
) {
    fun apply(entity: Kit): KitAttributes {
        val self = this

        return entity.attributes.apply {
            otherType = self.otherType
            state = self.state
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
    val lotId: String? = null,
    val locationCode: String? = null,
    val subStatus: KitSubStatusInput = KitSubStatusInput(),
) {
    fun apply(entity: Kit): Kit {
        val self = this
        return entity.apply {
            type = self.type
            status = self.status
            model = self.model
            age = self.age ?: age
            attributes = attributes
            archived = self.archived ?: archived
            make = self.make ?: make
            deviceVersion = self.deviceVersion ?: deviceVersion
            serialNo = self.serialNo ?: serialNo
            storageCapacity = self.storageCapacity ?: storageCapacity
            typeOfStorage = self.typeOfStorage
            ramCapacity = self.ramCapacity ?: ramCapacity
            cpuType = self.cpuType ?: cpuType
            tpmVersion = self.tpmVersion ?: tpmVersion
            cpuCores = self.cpuCores ?: cpuCores
            batteryHealth = self.batteryHealth ?: batteryHealth
            lotId = self.lotId ?: lotId
            locationCode = self.locationCode ?: locationCode
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
    val batteryHealth: Int? = null,
    val lotId: String? = null,
    val locationCode: String? = null,
) {
    val entity by lazy {
        val kit =
            Kit(
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
                batteryHealth = batteryHealth,
                lotId = lotId,
                locationCode = locationCode,
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
    val lotId: String?,
    val locationCode: String?,
    val subStatus: KitSubStatusInput = KitSubStatusInput(),
) {
    fun apply(entity: Kit): Kit {
        val self = this
        return entity.apply {
            val previousStatus = status
            type = self.type ?: type
            model = self.model ?: model
            status = self.status ?: status
            // Update statusUpdatedAt if status has changed
            if (previousStatus != status) {
                statusUpdatedAt = Instant.now()
            }
            make = self.make ?: make
            deviceVersion = self.deviceVersion ?: deviceVersion
            serialNo = self.serialNo ?: serialNo
            storageCapacity = self.storageCapacity ?: storageCapacity
            typeOfStorage = self.typeOfStorage ?: typeOfStorage
            ramCapacity = self.ramCapacity ?: ramCapacity
            cpuType = self.cpuType ?: cpuType
            tpmVersion = self.tpmVersion ?: tpmVersion
            cpuCores = self.cpuCores ?: cpuCores
            batteryHealth = self.batteryHealth ?: batteryHealth
            lotId = self.lotId ?: lotId
            locationCode = self.locationCode ?: locationCode
            subStatus = self.subStatus.apply(entity)
        }
    }
}

data class BulkKitUpdateInput(
    val ids: List<Long>,
    val status: KitStatus? = null,
    val archived: Boolean? = null,
    val locationCode: String? = null,
    val lotId: String? = null,
) {
    fun apply(entity: Kit): Kit {
        val self = this
        return entity.apply {
            if (self.status != null && self.status != status) {
                status = self.status
                statusUpdatedAt = Instant.now()
            }
            archived = self.archived ?: archived
            locationCode = self.locationCode ?: locationCode
            lotId = self.lotId ?: lotId
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
    var lockedToUser: Boolean = false,
) {
    fun apply(entity: Kit): KitSubStatus {
        val self = this
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
