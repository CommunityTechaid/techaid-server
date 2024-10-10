package cta.app.graphql.filters

import com.querydsl.core.BooleanBuilder
import java.time.Instant
import cta.app.QDonorParent
import cta.graphql.LongComparision
import cta.graphql.TextComparison
import cta.graphql.TimeComparison

class DonorParentWhereInput(
    var id: LongComparision? = null,
    var name: TextComparison? = null,
    var address: TextComparison? = null,
    var website: TextComparison? = null,
    var createdAt: TimeComparison<Instant>? = null,
    var updatedAt: TimeComparison<Instant>? = null,
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
        createdAt?.let { builder.and(it.build(entity.createdAt)) }
        updatedAt?.let { builder.and(it.build(entity.updatedAt)) }
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
