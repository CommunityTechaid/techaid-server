package ju.ma.app.graphql.filters

import com.querydsl.core.BooleanBuilder
import ju.ma.app.QReferringOrganisation
import ju.ma.graphql.LongComparision
import ju.ma.graphql.TextComparison
import ju.ma.graphql.TimeComparison
import java.time.Instant

class ReferringOrganisationWhereInput(
    var id: LongComparision? = null,
    var name: TextComparison? = null,
    var domain: TextComparison? = null,
    var address: TextComparison? = null,
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
        domain?.let { builder.and(it.build(entity.domain)) }
        address?.let { builder.and(it.build(entity.address)) }
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
