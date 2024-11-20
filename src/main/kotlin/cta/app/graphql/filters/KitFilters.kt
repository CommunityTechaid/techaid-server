package cta.app.graphql.filters

import com.github.alexliesenfeld.querydsl.jpa.hibernate.JsonPath
import com.querydsl.core.BooleanBuilder
import com.querydsl.core.types.dsl.EnumPath
import com.querydsl.jpa.JPAExpressions
import cta.app.KitStatus
import cta.app.KitStorageType
import cta.app.KitType
import cta.app.QKit
import cta.app.QKitSubStatus
import cta.graphql.BooleanComparison
import cta.graphql.IntegerComparision
import cta.graphql.JsonComparison
import cta.graphql.LongComparision
import cta.graphql.TextComparison
import cta.graphql.TimeComparison
import java.time.Instant

class KitStatusComparison(
    /**
     * Matches values equal to
     */
    var _eq: KitStatus? = null,
    /**
     * Matches values greater than
     */
    var _gt: KitStatus? = null,
    /**
     * Matches values greater than or equal to
     */
    var _gte: KitStatus? = null,
    /**
     * Matches values contained in the collection
     */
    var _in: MutableList<KitStatus>? = null,
    /**
     * Matches values that are null
     */
    var _is_null: Boolean? = null,
    /**
     * Matches values less than
     */
    var _lt: KitStatus? = null,
    /**
     * Matches values less than or equal to
     */
    var _lte: KitStatus? = null,
    /**
     * Matches values not equal to
     */
    var _neq: KitStatus? = null,
    /**
     * Matches values not in the collection
     */
    var _nin: MutableList<KitStatus>? = null
) {
    /**
     * Returns a filter for the specified [path]
     */
    fun build(path: EnumPath<KitStatus>): BooleanBuilder {
        val builder = BooleanBuilder()
        _eq?.let { builder.and(path.eq(it)) }
        _gt?.let { builder.and(path.gt(it)) }
        _gte?.let { builder.and(path.goe(it)) }
        _in?.let { builder.and(path.`in`(it)) }
        _is_null?.let {
            if (it) {
                builder.and(path.isNull)
            } else {
                builder.and(path.isNotNull)
            }
        }
        _lt?.let { builder.and(path.lt(it)) }
        _lte?.let { builder.and(path.loe(it)) }
        _neq?.let { builder.and(path.ne(it)) }
        _nin?.let { builder.and(path.notIn(it)) }
        return builder
    }
}

class KitTypeComparison(
    /**
     * Matches values equal to
     */
    var _eq: KitType? = null,
    /**
     * Matches values greater than
     */
    var _gt: KitType? = null,
    /**
     * Matches values greater than or equal to
     */
    var _gte: KitType? = null,
    /**
     * Matches values contained in the collection
     */
    var _in: MutableList<KitType>? = null,
    /**
     * Matches values that are null
     */
    var _is_null: Boolean? = null,
    /**
     * Matches values less than
     */
    var _lt: KitType? = null,
    /**
     * Matches values less than or equal to
     */
    var _lte: KitType? = null,
    /**
     * Matches values not equal to
     */
    var _neq: KitType? = null,
    /**
     * Matches values not in the collection
     */
    var _nin: MutableList<KitType>? = null
) {
    /**
     * Returns a filter for the specified [path]
     */
    fun build(path: EnumPath<KitType>): BooleanBuilder {
        val builder = BooleanBuilder()
        _eq?.let { builder.and(path.eq(it)) }
        _gt?.let { builder.and(path.gt(it)) }
        _gte?.let { builder.and(path.goe(it)) }
        _in?.let { builder.and(path.`in`(it)) }
        _is_null?.let {
            if (it) {
                builder.and(path.isNull)
            } else {
                builder.and(path.isNotNull)
            }
        }
        _lt?.let { builder.and(path.lt(it)) }
        _lte?.let { builder.and(path.loe(it)) }
        _neq?.let { builder.and(path.ne(it)) }
        _nin?.let { builder.and(path.notIn(it)) }
        return builder
    }
}

class KitStorageTypeComparison(
    /**
     * Matches values equal to
     */
    var _eq: KitStorageType? = null,
    /**
     * Matches values greater than
     */
    var _gt: KitStorageType? = null,
    /**
     * Matches values greater than or equal to
     */
    var _gte: KitStorageType? = null,
    /**
     * Matches values contained in the collection
     */
    var _in: MutableList<KitStorageType>? = null,
    /**
     * Matches values that are null
     */
    var _is_null: Boolean? = null,
    /**
     * Matches values less than
     */
    var _lt: KitStorageType? = null,
    /**
     * Matches values less than or equal to
     */
    var _lte: KitStorageType? = null,
    /**
     * Matches values not equal to
     */
    var _neq: KitStorageType? = null,
    /**
     * Matches values not in the collection
     */
    var _nin: MutableList<KitStorageType>? = null
    ) {
        /**
         * Returns a filter for the specified [path]
         */
        fun build(path: EnumPath<KitStorageType>): BooleanBuilder {
            val builder = BooleanBuilder()
            _eq?.let { builder.and(path.eq(it)) }
            _gt?.let { builder.and(path.gt(it)) }
            _gte?.let { builder.and(path.goe(it)) }
            _in?.let { builder.and(path.`in`(it)) }
            _is_null?.let {
                if (it) {
                    builder.and(path.isNull)
                } else {
                    builder.and(path.isNotNull)
                }
            }
            _lt?.let { builder.and(path.lt(it)) }
            _lte?.let { builder.and(path.loe(it)) }
            _neq?.let { builder.and(path.ne(it)) }
            _nin?.let { builder.and(path.notIn(it)) }
            return builder
        }
}

class KitAttributesWhereInput(
    var otherType: TextComparison? = null,
    var pickup: TextComparison? = null,
    var state: TextComparison? = null,
    var consent: TextComparison? = null,
    var notes: TextComparison? = null,
    var status: TextComparison? = null,
    var pickupAvailability: TextComparison? = null,
    var filters: List<JsonComparison>? = null,
    var AND: MutableList<KitAttributesWhereInput> = mutableListOf(),
    var OR: MutableList<KitAttributesWhereInput> = mutableListOf(),
    var NOT: MutableList<KitAttributesWhereInput> = mutableListOf()
) {
    fun build(entity: QKit = QKit.kit): BooleanBuilder {
        val builder = BooleanBuilder()
        val json = JsonPath.of(entity.attributes)

        otherType?.let { builder.and(it.build(json.get("otherType").asText())) }
        pickup?.let { builder.and(it.build(json.get("pickup").asText())) }
        state?.let { builder.and(it.build(json.get("state").asText())) }
        consent?.let { builder.and(it.build(json.get("consent").asText())) }
        notes?.let { builder.and(it.build(json.get("notes").asText())) }
        pickupAvailability?.let { builder.and(it.build(json.get("pickupAvailability").asText())) }
        status?.let { builder.and(it.build(json.get("status").asText())) }
        filters?.let { filter ->
            filter.forEach { builder.and(it.build(json)) }
        }
        if (AND.isNotEmpty()) {
            AND.forEach {
                builder.and(it.build(entity))
            }
        }

        if (OR.isNotEmpty()) {
            OR.forEach {
                builder.or(it.build(entity))
            }
        }

        if (NOT.isNotEmpty()) {
            NOT.forEach {
                builder.andNot(it.build(entity))
            }
        }
        return builder
    }
}

class KitSubStatusWhereInput(
    var installationOfOSFailed: BooleanComparison? = null,
    var wipeFailed: BooleanComparison? = null,
    var needsSparePart: BooleanComparison? = null,
    var needsFurtherInvestigation: BooleanComparison? = null,
    var network: TextComparison? = null,
    var installedOSName: TextComparison? = null,
    var lockedToUser: BooleanComparison? = null,
    var AND: MutableList<KitSubStatusWhereInput> = mutableListOf(),
    var OR: MutableList<KitSubStatusWhereInput> = mutableListOf(),
    var NOT: MutableList<KitSubStatusWhereInput> = mutableListOf()
) {
    fun build(entity: QKitSubStatus = QKitSubStatus.kitSubStatus): BooleanBuilder {
        val builder = BooleanBuilder()
        
        installationOfOSFailed?.let { builder.and(it.build(entity.installationOfOSFailed)) }
        wipeFailed?.let { builder.and(it.build(entity.wipeFailed)) }
        needsSparePart?.let { builder.and(it.build(entity.needsSparePart)) }
        needsFurtherInvestigation?.let { builder.and(it.build(entity.needsFurtherInvestigation)) }
        network?.let { builder.and(it.build(entity.network)) }
        installedOSName?.let { builder.and(it.build(entity.installedOSName)) }
        lockedToUser?.let { builder.and(it.build(entity.lockedToUser)) }
        if (AND.isNotEmpty()) {
            AND.forEach {
                builder.and(it.build(entity))
            }
        }

        if (OR.isNotEmpty()) {
            OR.forEach {
                builder.or(it.build(entity))
            }
        }

        if (NOT.isNotEmpty()) {
            NOT.forEach {
                builder.andNot(it.build(entity))
            }
        }
        return builder
    }
}

class KitWhereInput(
    var id: LongComparision? = null,
    var location: TextComparison? = null,
    var status: KitStatusComparison? = null,
    var type: KitTypeComparison? = null,
    var age: IntegerComparision? = null,
    var model: TextComparison? = null,
    var archived: BooleanComparison? = null,
    var createdAt: TimeComparison<Instant>? = null,
    var updatedAt: TimeComparison<Instant>? = null,
    var attributes: KitAttributesWhereInput? = null,
    var deviceRequest: DeviceRequestWhereInput? = null,
    var donor: DonorWhereInput? = null,
    var make: TextComparison? = null,
    var deviceVersion: TextComparison? = null,
    var serialNo: TextComparison? = null,
    var storageCapacity: IntegerComparision? = null,
    var typeOfStorage: KitStorageTypeComparison? = null,
    var ramCapacity: IntegerComparision? = null,
    var cpuType: TextComparison? = null,
    var tpmVersion: TextComparison? = null,
    var cpuCores: IntegerComparision? = null,
    var subStatus: KitSubStatusWhereInput? = null,
    var AND: MutableList<KitWhereInput> = mutableListOf(),
    var OR: MutableList<KitWhereInput> = mutableListOf(),
    var NOT: MutableList<KitWhereInput> = mutableListOf()
) {
    fun build(entity: QKit = QKit.kit): BooleanBuilder {
        val builder = BooleanBuilder()
        age?.let { builder.and(it.build(entity.age)) }
        id?.let { builder.and(it.build(entity.id)) }
        status?.let { builder.and(it.build(entity.status)) }
        type?.let { builder.and(it.build(entity.type)) }
        model?.let { builder.and(it.build(entity.model)) }
        location?.let { builder.and(it.build(entity.location)) }
        createdAt?.let { builder.and(it.build(entity.createdAt)) }
        archived?.let { builder.and(it.build(entity.archived)) }
        updatedAt?.let { builder.and(it.build(entity.updatedAt)) }
        attributes?.let { builder.and(it.build(entity)) }
        deviceRequest?.let { builder.and(it.build(entity.deviceRequest)) }
        donor?.let { builder.and(it.build(entity.donor)) }
        make?.let { builder.and(it.build(entity.make)) }
        deviceVersion?.let { builder.and(it.build(entity.deviceVersion)) }
        serialNo?.let { builder.and(it.build(entity.serialNo)) }
        storageCapacity?.let {builder.and(it.build(entity.storageCapacity))}
        typeOfStorage?.let {builder.and(it.build(entity.typeOfStorage))}
        ramCapacity?.let {builder.and(it.build(entity.ramCapacity))}
        cpuType?.let {builder.and(it.build(entity.cpuType))}
        tpmVersion?.let {builder.and(it.build(entity.tpmVersion))}
        cpuCores?.let { builder.and(it.build(entity.cpuCores)) }
        subStatus?.let { builder.and(it.build(entity.subStatus))}
        if (AND.isNotEmpty()) {
            AND.forEach {
                builder.and(it.build(entity))
            }
        }

        if (OR.isNotEmpty()) {
            OR.forEach {
                builder.or(it.build(entity))
            }
        }

        if (NOT.isNotEmpty()) {
            NOT.forEach {
                builder.andNot(it.build(entity))
            }
        }
        return builder
    }
}
