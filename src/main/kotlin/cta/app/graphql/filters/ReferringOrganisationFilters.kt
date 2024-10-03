package cta.app.graphql.filters

import com.querydsl.core.BooleanBuilder
import cta.app.QReferringOrganisation
import cta.graphql.BooleanComparison
import cta.graphql.LongComparision
import cta.graphql.TextComparison
import cta.graphql.TimeComparison
import java.time.Instant

class ReferringOrganisationWhereInput(
    var id: LongComparision? = null,
    var name: TextComparison? = null,
    var website: TextComparison? = null,
    var archived: BooleanComparison? = null,
    var createdAt: TimeComparison<Instant>? = null,
    var updatedAt: TimeComparison<Instant>? = null,
    var AND: MutableList<ReferringOrganisationWhereInput> = mutableListOf(),
    var OR: MutableList<ReferringOrganisationWhereInput> = mutableListOf(),
    var NOT: MutableList<ReferringOrganisationWhereInput> = mutableListOf()
) {
    fun build(entity: QReferringOrganisation = QReferringOrganisation.referringOrganisation): BooleanBuilder {
        val builder = BooleanBuilder()
        id?.let { builder.and(it.build(entity.id)) }
        name?.let { builder.and(it.build(entity.name)) }
        website?.let {builder.and(it.build(entity.website))}
        archived?.let { builder.and(it.build(entity.archived)) }
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
