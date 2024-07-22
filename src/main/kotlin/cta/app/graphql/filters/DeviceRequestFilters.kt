package cta.app.graphql.filters

import com.querydsl.core.BooleanBuilder
import com.querydsl.core.types.dsl.EnumPath
import cta.app.DeviceRequestStatus
import cta.app.KitStatus
import cta.app.QDeviceRequest
import cta.graphql.BooleanComparison
import cta.graphql.LongComparision
import cta.graphql.TimeComparison
import java.time.Instant

class DeviceRequestWhereInput(
    var id: LongComparision? = null,
    //var deviceRequestItems: DeviceRequestItemsWhereInput? = null,
    var isSales: BooleanComparison? = null,
    var status: DeviceRequestStatusComparison? = null,
    var createdAt: TimeComparison<Instant>? = null,
    var updatedAt: TimeComparison<Instant>? = null,
    var referringOrganisationContact: ReferringOrganisationContactWhereInput? = null,
    var AND: MutableList<DeviceRequestWhereInput> = mutableListOf(),
    var OR: MutableList<DeviceRequestWhereInput> = mutableListOf(),
    var NOT: MutableList<DeviceRequestWhereInput> = mutableListOf()
) {
    fun build(entity: QDeviceRequest = QDeviceRequest.deviceRequest): BooleanBuilder {
        val builder = BooleanBuilder()
        id?.let { builder.and(it.build(entity.id)) }
        //deviceRequestItems?.let { builder.and(it.build(entity)) }
        status?.let { builder.and(it.build(entity.status)) }
        isSales?.let {builder.and(it.build(entity.isSales))}
        referringOrganisationContact?.let { builder.and(it.build(entity.referringOrganisationContact)) }
        createdAt?.let { builder.and(it.build(entity.createdAt)) }
        updatedAt?.let { builder.and(it.build(entity.updatedAt)) }
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

/*
class DeviceRequestItemsWhereInput(
    var phones: IntegerComparision? = null,
    var tablets: IntegerComparision? = null,
    var laptops: IntegerComparision? = null,
    var allInOnes: IntegerComparision? = null,
    var desktops: IntegerComparision? = null,
    var filters: List<JsonComparison>? = null,
    var AND: MutableList<DeviceRequestItemsWhereInput> = mutableListOf(),
    var OR: MutableList<DeviceRequestItemsWhereInput> = mutableListOf(),
    var NOT: MutableList<DeviceRequestItemsWhereInput> = mutableListOf()
) {
    fun build(entity: QDeviceRequest = QDeviceRequest.deviceRequest): BooleanBuilder {
        val builder = BooleanBuilder()
        val json = JsonPath.of(entity.deviceRequestItems)

        phones?.let { builder.and(it.build(json.get("deviceRequestItems.phones").asInt())) }
        tablets?.let { builder.and(it.build(json.get("deviceRequestItems.tablets").asInt())) }
        laptops?.let { builder.and(it.build(json.get("deviceRequestItems.laptops").asInt())) }
        allInOnes?.let { builder.and(it.build(json.get("deviceRequestItems.allInOnes").asInt())) }
        desktops?.let { builder.and(it.build(json.get("deviceRequestItems.desktops").asInt())) }
        filters?.let { filter ->
            filter.forEach { builder.and(it.build(json.get("deviceRequestItems"))) }
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
}*/

