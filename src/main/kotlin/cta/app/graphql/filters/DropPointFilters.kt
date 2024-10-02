package cta.app.graphql.filters

import com.querydsl.core.BooleanBuilder
import java.time.Instant
import cta.app.QDropPoint
import cta.graphql.LongComparision
import cta.graphql.TextComparison
import cta.graphql.TimeComparison

class DropPointWhereInput(
    var id: LongComparision? = null,
    var name: TextComparison? = null,
    var address: TextComparison? = null,
    var website: TextComparison? = null,
    var createdAt: TimeComparison<Instant>? = null,
    var updatedAt: TimeComparison<Instant>? = null,
    var AND: MutableList<DropPointWhereInput> = mutableListOf(),
    var OR: MutableList<DropPointWhereInput> = mutableListOf(),
    var NOT: MutableList<DropPointWhereInput> = mutableListOf()
) {
    fun build(entity: QDropPoint = QDropPoint.dropPoint): BooleanBuilder {
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
