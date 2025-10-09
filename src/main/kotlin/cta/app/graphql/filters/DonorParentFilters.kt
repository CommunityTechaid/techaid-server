package cta.app.graphql.filters

import com.querydsl.core.BooleanBuilder
import com.querydsl.core.types.dsl.EnumPath
import java.time.Instant
import cta.app.DonorParentType
import cta.app.QDonorParent
import cta.graphql.BooleanComparison
import cta.graphql.LongComparison
import cta.graphql.TextComparison
import cta.graphql.TimeComparison

class DonorParentTypeComparison(
    /**
     * Matches values equal to
     */
    var _eq: DonorParentType? = null,
    /**
     * Matches values greater than
     */
    var _gt: DonorParentType? = null,
    /**
     * Matches values greater than or equal to
     */
    var _gte: DonorParentType? = null,
    /**
     * Matches values contained in the collection
     */
    var _in: MutableList<DonorParentType>? = null,
    /**
     * Matches values that are null
     */
    var _is_null: Boolean? = null,
    /**
     * Matches values less than
     */
    var _lt: DonorParentType? = null,
    /**
     * Matches values less than or equal to
     */
    var _lte: DonorParentType? = null,
    /**
     * Matches values not equal to
     */
    var _neq: DonorParentType? = null,
    /**
     * Matches values not in the collection
     */
    var _nin: MutableList<DonorParentType>? = null
) {
    /**
     * Returns a filter for the specified [path]
     */
    fun build(path: EnumPath<DonorParentType>): BooleanBuilder {
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

class DonorParentWhereInput(
    var id: LongComparison? = null,
    var name: TextComparison? = null,
    var address: TextComparison? = null,
    var website: TextComparison? = null,
    var type: DonorParentTypeComparison? = null,
    var createdAt: TimeComparison<Instant>? = null,
    var updatedAt: TimeComparison<Instant>? = null,
    var archived: BooleanComparison? = null,
    var AND: MutableList<DonorParentWhereInput> = mutableListOf(),
    var OR: MutableList<DonorParentWhereInput> = mutableListOf(),
    var NOT: MutableList<DonorParentWhereInput> = mutableListOf()
) {
    fun build(entity: QDonorParent = QDonorParent.donorParent): BooleanBuilder {
        val builder = BooleanBuilder()
        id?.let { builder.and(it.build(entity.id)) }
        name?.let { builder.and(it.build(entity.name)) }
        address?.let { builder.and(it.build(entity.address)) }
        website?.let { builder.and(it.build(entity.website)) }
        type?.let { builder.and(it.build(entity.type)) }
        createdAt?.let { builder.and(it.build(entity.createdAt)) }
        updatedAt?.let { builder.and(it.build(entity.updatedAt)) }
        archived?.let { builder.and(it.build(entity.archived)) }
        if (AND.isNotEmpty()) {
            AND.forEach { builder.and(it.build(entity)) }
        }
        if (OR.isNotEmpty()) {
            OR.forEach { builder.or(it.build(entity)) }
        }
        if (NOT.isNotEmpty()) {
            NOT.forEach { builder.andNot(it.build(entity)) }
        }
        return builder
    }
}
