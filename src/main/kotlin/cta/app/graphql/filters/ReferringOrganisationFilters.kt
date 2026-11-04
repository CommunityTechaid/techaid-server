package cta.app.graphql.filters

import com.querydsl.core.BooleanBuilder
import com.querydsl.core.types.dsl.EnumPath
import cta.app.MembershipTier
import cta.app.QReferringOrganisation
import cta.graphql.BooleanComparison
import cta.graphql.LongComparison
import cta.graphql.TextComparison
import cta.graphql.TimeComparison
import java.time.Instant

class ReferringOrganisationWhereInput(
    var id: LongComparison? = null,
    var name: TextComparison? = null,
    var website: TextComparison? = null,
    var archived: BooleanComparison? = null,
    var membershipTier: MembershipTierComparison? = null,
    var createdAt: TimeComparison<Instant>? = null,
    var updatedAt: TimeComparison<Instant>? = null,
    var AND: MutableList<ReferringOrganisationWhereInput> = mutableListOf(),
    var OR: MutableList<ReferringOrganisationWhereInput> = mutableListOf(),
    var NOT: MutableList<ReferringOrganisationWhereInput> = mutableListOf(),
) {
    fun build(entity: QReferringOrganisation = QReferringOrganisation.referringOrganisation): BooleanBuilder {
        val builder = BooleanBuilder()
        id?.let { builder.and(it.build(entity.id)) }
        name?.let { builder.and(it.build(entity.name)) }
        website?.let { builder.and(it.build(entity.website)) }
        archived?.let { builder.and(it.build(entity.archived)) }
        membershipTier?.let { builder.and(it.build(entity.membershipTier)) }
        createdAt?.let { builder.and(it.build(entity.createdAt)) }
        updatedAt?.let { builder.and(it.build(entity.updatedAt)) }
        if (AND.isNotEmpty()) {
            AND.forEach {
                builder.and(it.build(entity))
            }
        }

        if (OR.isNotEmpty()) {
            // Group the alternatives, then AND the group in (#169, same fix as #168 in
            // DeviceRequestFilters). builder.or(...) disjoins against everything already on the
            // builder, so {x: ..., OR: [y]} used to build "x OR y" instead of "x AND (y)".
            val alternatives = BooleanBuilder()
            OR.forEach { alternatives.or(it.build(entity)) }
            builder.and(alternatives)
        }

        if (NOT.isNotEmpty()) {
            NOT.forEach {
                builder.andNot(it.build(entity))
            }
        }
        return builder
    }
}

/** Enum comparison for [MembershipTier]; same shape as the other `*Comparison` filter inputs. */
class MembershipTierComparison(
    var _eq: MembershipTier? = null,
    var _in: MutableList<MembershipTier>? = null,
    var _is_null: Boolean? = null,
    var _neq: MembershipTier? = null,
    var _nin: MutableList<MembershipTier>? = null,
) {
    fun build(path: EnumPath<MembershipTier>): BooleanBuilder {
        val builder = BooleanBuilder()
        _eq?.let { builder.and(path.eq(it)) }
        _in?.let { builder.and(path.`in`(it)) }
        _is_null?.let { builder.and(if (it) path.isNull else path.isNotNull) }
        _neq?.let { builder.and(path.ne(it)) }
        _nin?.let { builder.and(path.notIn(it)) }
        return builder
    }
}

/**
 * Restricted filter surface for the anonymous referringOrganisationsPublic query.
 * The public referral form's typeahead only needs a name search over non-archived
 * organisations; everything else stays admin-only on the full where-input.
 */
class ReferringOrganisationPublicWhereInput(
    var name: TextComparison? = null,
    var archived: BooleanComparison? = null,
) {
    fun build(entity: QReferringOrganisation = QReferringOrganisation.referringOrganisation): BooleanBuilder {
        val builder = BooleanBuilder()
        name?.let { builder.and(it.build(entity.name)) }
        archived?.let { builder.and(it.build(entity.archived)) }
        return builder
    }
}
