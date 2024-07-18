package cta.app.graphql.filters

import com.querydsl.core.BooleanBuilder
import cta.app.QReferringOrganisationContact
import cta.graphql.BooleanComparison
import cta.graphql.LongComparision
import cta.graphql.TextComparison
import cta.graphql.TimeComparison
import java.time.Instant

class ReferringOrganisationContactWhereInput(
    var id: LongComparision? = null,
    var fullName: TextComparison? = null,
    var address: TextComparison? = null,
    var email: TextComparison? = null,
    var phoneNumber: TextComparison? = null,
    var archived: BooleanComparison? = null,
    var createdAt: TimeComparison<Instant>? = null,
    var updatedAt: TimeComparison<Instant>? = null,
    var referringOrganisation: ReferringOrganisationWhereInput? = null,
    var AND: MutableList<ReferringOrganisationContactWhereInput> = mutableListOf(),
    var OR: MutableList<ReferringOrganisationContactWhereInput> = mutableListOf(),
    var NOT: MutableList<ReferringOrganisationContactWhereInput> = mutableListOf()
) {
    fun build(entity: QReferringOrganisationContact = QReferringOrganisationContact.referringOrganisationContact): BooleanBuilder {
        val builder = BooleanBuilder()
        id?.let { builder.and(it.build(entity.id)) }
        fullName?.let { builder.and(it.build(entity.fullName)) }
        address?.let { builder.and(it.build(entity.address)) }
        phoneNumber?.let { builder.and(it.build(entity.phoneNumber)) }
        email?.let { builder.and(it.build(entity.email)) }
        referringOrganisation?.let { builder.and(it.build(entity.referringOrganisation)) }
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

data class ReferringOrganisationContactPublicWhereInput(
    var fullName: TextComparison? = null,
    var email: TextComparison? = null,
    var referringOrganisation: ReferringOrganisationWhereInput? = null,
    var AND: MutableList<ReferringOrganisationContactPublicWhereInput> = mutableListOf(),
    var OR: MutableList<ReferringOrganisationContactPublicWhereInput> = mutableListOf(),
    var NOT: MutableList<ReferringOrganisationContactPublicWhereInput> = mutableListOf()
) {
    fun build(entity: QReferringOrganisationContact = QReferringOrganisationContact.referringOrganisationContact): BooleanBuilder {
        val builder = BooleanBuilder()
        fullName?.let { builder.and(it.build(entity.fullName)) }
        email?.let { builder.and(it.build(entity.email)) }
        referringOrganisation?.let { builder.and(it.build(entity.referringOrganisation)) }
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