package cta.app.graphql.filters

import com.querydsl.core.BooleanBuilder
import java.time.Instant
import cta.app.QDonor
import cta.graphql.BooleanComparison
import cta.graphql.LongComparison
import cta.graphql.TextComparison
import cta.graphql.TimeComparison



class DonorWhereInput(
    var id: LongComparison? = null,
    var postCode: TextComparison? = null,
    var phoneNumber: TextComparison? = null,
    var name: TextComparison? = null,
    var email: TextComparison? = null,
    var referral: TextComparison? = null,
    var donorParent: DonorParentWhereInput? = null,
    var createdAt: TimeComparison<Instant>? = null,
    var updatedAt: TimeComparison<Instant>? = null,
    var archived: BooleanComparison? = null,
    var isLeadContact: BooleanComparison? = null,
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
        name?.let { builder.and(it.build(entity.name)) }
        donorParent?.let { builder.and(it.build(entity.donorParent)) }
        archived?.let { builder.and(it.build(entity.archived)) }
        isLeadContact?.let { builder.and(it.build(entity.isLeadContact)) }
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
