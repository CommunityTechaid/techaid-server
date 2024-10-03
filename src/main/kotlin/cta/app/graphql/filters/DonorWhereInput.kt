package cta.app.graphql.filters

import com.querydsl.core.BooleanBuilder
import com.querydsl.core.types.dsl.EnumPath
import java.time.Instant
import cta.app.QDonor
import cta.app.DonorType
import cta.graphql.LongComparision
import cta.graphql.TextComparison
import cta.graphql.TimeComparison

class DonorTypeComparison(
    /**
     * Matches values equal to
     */
    var _eq: DonorType? = null,
    /**
     * Matches values greater than
     */
    var _gt: DonorType? = null,
    /**
     * Matches values greater than or equal to
     */
    var _gte: DonorType? = null,
    /**
     * Matches values contained in the collection
     */
    var _in: MutableList<DonorType>? = null,
    /**
     * Matches values that are null
     */
    var _is_null: Boolean? = null,
    /**
     * Matches values less than
     */
    var _lt: DonorType? = null,
    /**
     * Matches values less than or equal to
     */
    var _lte: DonorType? = null,
    /**
     * Matches values not equal to
     */
    var _neq: DonorType? = null,
    /**
     * Matches values not in the collection
     */
    var _nin: MutableList<DonorType>? = null
) {
    /**
     * Returns a filter for the specified [path]
     */
    fun build(path: EnumPath<DonorType>): BooleanBuilder {
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

class DonorWhereInput(
    var id: LongComparision? = null,
    var postCode: TextComparison? = null,
    var phoneNumber: TextComparison? = null,
    var name: TextComparison? = null,
    var businessName: TextComparison? = null,
    var email: TextComparison? = null,
    var type: DonorTypeComparison? = null,
    var referral: TextComparison? = null,
    var dropPoint: DropPointWhereInput? = null,
    var createdAt: TimeComparison<Instant>? = null,
    var updatedAt: TimeComparison<Instant>? = null,
    var AND: MutableList<DonorWhereInput> = mutableListOf(),
    var OR: MutableList<DonorWhereInput> = mutableListOf(),
    var NOT: MutableList<DonorWhereInput> = mutableListOf()
) {
    fun build(entity: QDonor = QDonor.donor): BooleanBuilder {
        val builder = BooleanBuilder()
        id?.let { builder.and(it.build(entity.id)) }
        phoneNumber?.let { builder.and(it.build(entity.phoneNumber)) }
        email?.let { builder.and(it.build(entity.email)) }
        referral?.let { builder.and(it.build(entity.referral)) }
        postCode?.let { builder.and(it.build(entity.postCode)) }
        createdAt?.let { builder.and(it.build(entity.createdAt)) }
        updatedAt?.let { builder.and(it.build(entity.updatedAt)) }
        type?.let { builder.and(it.build(entity.type)) }
        name?.let { builder.and(it.build(entity.name)) }
        businessName?.let { builder.and(it.build(entity.businessName)) }
        dropPoint?.let { builder.and(it.build(entity.dropPoint)) }
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
