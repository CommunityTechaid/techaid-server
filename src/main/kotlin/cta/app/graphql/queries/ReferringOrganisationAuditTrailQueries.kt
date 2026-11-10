package cta.app.graphql.queries

import cta.app.CustomRevisionInfo
import cta.app.MembershipTier
import cta.app.ReferringOrganisation
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.hibernate.envers.AuditReaderFactory
import org.hibernate.envers.RevisionType
import org.hibernate.envers.query.AuditEntity
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * Audit history of an organisation (#266, organisations only): what staff use to see how a
 * membership has changed over time. Reads `referring_organisations_audit_trail`, which Envers has
 * always written; the membership columns exist on revisions from 2026-10-28.
 *
 * Unlike `kitAudits` / `deviceRequestAudits`, the admin permission is checked HERE, not only by
 * the dashboard hiding the tab.
 */
@Controller
class ReferringOrganisationAuditTrailQueries(
    @PersistenceContext
    private val em: EntityManager,
) {
    // @Transactional keeps the entity manager open for the AuditReader (as in DeviceRequestAuditTrailQueries).
    @PreAuthorize("hasAnyAuthority('app:admin', 'admin:organisations')")
    @Transactional(readOnly = true)
    @QueryMapping
    fun referringOrganisationAudits(
        @Argument where: Long,
    ): List<ReferringOrganisationAudit> =
        AuditReaderFactory
            .get(em)
            .createQuery()
            .forRevisionsOfEntity(ReferringOrganisation::class.java, false, true)
            .add(AuditEntity.id().eq(where))
            .addOrder(AuditEntity.revisionNumber().asc())
            .resultList
            .map { row ->
                // Each row is [entity, revision entity, revision type].
                val columns = row as Array<*>
                ReferringOrganisationAudit(
                    entity = ReferringOrganisationSnapshot.of(columns[0] as ReferringOrganisation),
                    revision = columns[1] as CustomRevisionInfo,
                    type = columns[2] as RevisionType,
                )
            }
}

data class ReferringOrganisationAudit(
    val entity: ReferringOrganisationSnapshot,
    val revision: CustomRevisionInfo,
    val type: RevisionType,
)

/**
 * The audited columns of an organisation at one revision, copied out of the Envers entity so that
 * GraphQL cannot walk from a historic row into referees, notes or today's allowance.
 */
data class ReferringOrganisationSnapshot(
    val id: Long,
    val name: String?,
    val website: String?,
    val phoneNumber: String?,
    val archived: Boolean,
    val membershipTier: MembershipTier?,
    val membershipStartDate: LocalDate?,
    val membershipEndDate: LocalDate?,
) {
    companion object {
        /**
         * Envers fills the entity by field access, so on a revision written before a column
         * existed (and on a DEL revision) a property declared non-null really holds null. Read
         * them all as nullable.
         */
        fun of(entity: ReferringOrganisation): ReferringOrganisationSnapshot {
            val name: String? = entity.name
            val tier: MembershipTier? = entity.membershipTier
            val start: LocalDate? = entity.membershipStartDate
            return ReferringOrganisationSnapshot(
                id = entity.id,
                name = name,
                website = entity.website,
                phoneNumber = entity.phoneNumber,
                archived = entity.archived,
                membershipTier = tier,
                membershipStartDate = start,
                membershipEndDate = entity.membershipEndDate,
            )
        }
    }
}
