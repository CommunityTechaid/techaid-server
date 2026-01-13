package cta.app.graphql.filters

import com.github.alexliesenfeld.querydsl.jpa.hibernate.JsonPath
import com.querydsl.core.BooleanBuilder
import com.querydsl.core.types.dsl.EnumPath
import cta.app.DeviceRequestStatus
import cta.app.KitStatus
import cta.app.QDeviceRequest
import cta.app.QDeviceRequestItems
import cta.graphql.BooleanComparison
import cta.graphql.LongComparison
import cta.graphql.TextComparison
import cta.graphql.TimeComparison
import cta.graphql.IntegerComparison
import cta.graphql.JsonComparison
import java.time.Instant

class DeviceRequestWhereInput(
    var id: LongComparison? = null,
    var deviceRequestItems: DeviceRequestItemsWhereInput? = null,
    var isSales: BooleanComparison? = null,
    var clientRef: TextComparison? = null,
    var borough: TextComparison? = null,
    var status: DeviceRequestStatusComparison? = null,
    var createdAt: TimeComparison<Instant>? = null,
    var updatedAt: TimeComparison<Instant>? = null,
    var collectionDate: TimeComparison<Instant>? = null,
    var referringOrganisationContact: ReferringOrganisationContactWhereInput? = null,
    var isPrepped: BooleanComparison? = null,
    var AND: MutableList<DeviceRequestWhereInput> = mutableListOf(),
    var OR: MutableList<DeviceRequestWhereInput> = mutableListOf(),
    var NOT: MutableList<DeviceRequestWhereInput> = mutableListOf()
) {
    fun build(entity: QDeviceRequest = QDeviceRequest.deviceRequest): BooleanBuilder {
        val builder = BooleanBuilder()
        id?.let { builder.and(it.build(entity.id)) }
        deviceRequestItems?.let { builder.and(it.build(entity.deviceRequestItems)) }
        status?.let { builder.and(it.build(entity.status)) }
        isSales?.let {builder.and(it.build(entity.isSales))}
        clientRef?.let {builder.and(it.build(entity.clientRef))}
        borough?.let {builder.and(it.build(entity.borough))}
        referringOrganisationContact?.let { builder.and(it.build(entity.referringOrganisationContact)) }
        createdAt?.let { builder.and(it.build(entity.createdAt)) }
        updatedAt?.let { builder.and(it.build(entity.updatedAt)) }
        collectionDate?.let { builder.and(it.build(entity.collectionDate)) }
        isPrepped?.let { builder.and(it.build(entity.isPrepped)) }
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

class DeviceRequestStatusComparison(
    /**
     * Matches values equal to
     */
    var _eq: DeviceRequestStatus? = null,
    /**
     * Matches values greater than
     */
    var _gt: DeviceRequestStatus? = null,
    /**
     * Matches values greater than or equal to
     */
    var _gte: DeviceRequestStatus? = null,
    /**
     * Matches values contained in the collection
     */
    var _in: MutableList<DeviceRequestStatus>? = null,
    /**
     * Matches values that are null
     */
    var _is_null: Boolean? = null,
    /**
     * Matches values less than
     */
    var _lt: DeviceRequestStatus? = null,
    /**
     * Matches values less than or equal to
     */
    var _lte: DeviceRequestStatus? = null,
    /**
     * Matches values not equal to
     */
    var _neq: DeviceRequestStatus? = null,
    /**
     * Matches values not in the collection
     */
    var _nin: MutableList<DeviceRequestStatus>? = null
) {
    /**
     * Returns a filter for the specified [path]
     */
    fun build(path: EnumPath<DeviceRequestStatus>): BooleanBuilder {
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

class DeviceRequestItemsWhereInput(
    var phones: IntegerComparison? = null,
    var tablets: IntegerComparison? = null,
    var laptops: IntegerComparison? = null,
    var allInOnes: IntegerComparison? = null,
    var desktops: IntegerComparison? = null,
    var commsDevices: IntegerComparison? = null,
    var broadbandHubs: IntegerComparison? = null,
    var AND: MutableList<DeviceRequestItemsWhereInput> = mutableListOf(),
    var OR: MutableList<DeviceRequestItemsWhereInput> = mutableListOf(),
    var NOT: MutableList<DeviceRequestItemsWhereInput> = mutableListOf()
) {
    fun build(entity: QDeviceRequestItems = QDeviceRequestItems.deviceRequestItems): BooleanBuilder {
        val builder = BooleanBuilder()
        
        phones?.let { builder.and(it.build(entity.phones)) }
        tablets?.let { builder.and(it.build(entity.tablets)) }
        laptops?.let { builder.and(it.build(entity.laptops)) }
        allInOnes?.let { builder.and(it.build(entity.allInOnes)) }
        desktops?.let { builder.and(it.build(entity.desktops)) }
        commsDevices?.let { builder.and(it.build(entity.commsDevices)) }
        broadbandHubs?.let { builder.and(it.build(entity.broadbandHubs)) }
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

