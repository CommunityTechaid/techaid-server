package cta.app.graphql.filters

import com.querydsl.core.BooleanBuilder
import cta.app.QReferringOrganisationContact
import cta.graphql.BooleanComparison
import cta.graphql.ExactTextComparison
import cta.graphql.LongComparison
import cta.graphql.TextComparison
import cta.graphql.TimeComparison
import java.time.Instant

class ReferringOrganisationContactWhereInput(
    var id: LongComparison? = null,
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
    var NOT: MutableList<ReferringOrganisationContactWhereInput> = mutableListOf(),
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

/**
 * The anonymous "is this address already registered" lookup.
 *
 * [email] is required by the schema and only does a case-insensitive EXACT match: the query is
 * reachable without credentials, so it must not be able to answer anything broader than one
 * address. [ExactTextComparison] explains what this replaced and why. The boolean combinators
 * that the staff-facing input carries are deliberately absent here.
 */
data class ReferringOrganisationContactPublicWhereInput(
    var email: ExactTextComparison? = null,
    var referringOrganisation: ReferringOrganisationWhereInput? = null,
    var archived: BooleanComparison? = null,
) {
    fun build(entity: QReferringOrganisationContact = QReferringOrganisationContact.referringOrganisationContact): BooleanBuilder {
        val builder = BooleanBuilder()
        // The schema makes email non-null, so this is belt and braces: with no address at all we
        // match nothing rather than returning the table.
        val emailFilter = email ?: ExactTextComparison()
        builder.and(emailFilter.build(entity.email))
        referringOrganisation?.let { builder.and(it.build(entity.referringOrganisation)) }
        archived?.let { builder.and(it.build(entity.archived)) }
        return builder
    }
}
