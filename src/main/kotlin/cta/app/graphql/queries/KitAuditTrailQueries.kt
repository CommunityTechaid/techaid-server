package cta.app.graphql.queries

import cta.app.CustomRevisionInfo
import cta.app.Kit
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.hibernate.envers.AuditReader
import org.hibernate.envers.AuditReaderFactory
import org.hibernate.envers.RevisionType
import org.hibernate.envers.query.AuditEntity
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional

@Controller
@PreAuthorize("hasAnyAuthority('read:kits')")
class KitAuditTrailQueries(
    @PersistenceContext
    private val em: EntityManager,
) {
    // private val kitAudits: KitAuditRepository,
    // private val filterService: FilterService

    // This annotation is added so that the entity manager can be obtained.
    // Entity Manager might close otherwise
    @Transactional
    @QueryMapping
    fun kitAudits(
        @Argument where: Long,
    ): List<KitAudit> {
        val reader: AuditReader = AuditReaderFactory.get(em)
        val finalResults: MutableList<KitAudit> = mutableListOf()
        /*Use AuditReader to query the revisions as required and get the results as a list.
        Set selectedEntitiesOnly to true to get only the revisions of Kit instead of the extra information
         * */
        val results =
            reader
                .createQuery()
                .forRevisionsOfEntity(Kit::class.java, false, true)
                .add(AuditEntity.id().eq(where))
                .resultList

        /*
         * The returned result is of the form [{Kit, CustomRevisionInfo, RevisionType}]
         * We need to manually typecast this (I think. I did not find a straightforward way of doing it otherwise)
         */
        for (row in results) {
            @Suppress("UNCHECKED_CAST")
            val array = row as Array<Any>
            val entity: Kit = array[0] as Kit
            val revisionEntity: CustomRevisionInfo = array[1] as CustomRevisionInfo
            val revisionType = array[2] as RevisionType

            val revision = KitAudit(entity, revisionEntity, revisionType)
            // Keep adding the revisions into a list to return to graphQL query
            finalResults.add(revision)
        }

        val shape = revisionShape(where)
        return finalResults.map {
            val s = shape[it.revision.id]
            it.copy(
                changedNothingAudited = s?.first ?: false,
                siblingKitsInRevision = s?.second ?: 0,
            )
        }
    }

    /**
     * Per revision of this kit: did anything but updated_at change, and how many OTHER kits were
     * written in the same revision.
     *
     * Computed in SQL rather than by comparing Kit fields in Kotlin, deliberately. The comparison
     * has to cover EVERY audited column - a Kotlin list of them is a second copy of the audit
     * schema that drifts silently the first time someone adds a column, and the failure is
     * invisible: a revision that changed only the new column would be reported as changing
     * nothing and hidden from the history. `to_jsonb(row) - 'updated_at'` cannot drift. It is
     * also the same expression db/admin/2026-08-18__correct_kit_updated_at_collateral.sql uses,
     * so the API and the correction script agree by construction.
     *
     * One extra query per audit view, bounded by that kit's revision count.
     */
    private fun revisionShape(kitId: Long): Map<Long, Pair<Boolean, Int>> {
        @Suppress("UNCHECKED_CAST")
        val rows =
            em
                .createNativeQuery(
                    """
                    with seq as (
                        select k.rev,
                               k.revtype,
                               to_jsonb(k) - 'rev' - 'revtype' - 'updated_at' as body,
                               lag(to_jsonb(k) - 'rev' - 'revtype' - 'updated_at')
                                   over (order by k.rev)                      as prev_body
                          from kit_audit_trail k
                         where k.id = :kitId
                    )
                    select s.rev,
                           coalesce(s.revtype = 1 and s.body = s.prev_body, false) as changed_nothing,
                           (select count(*) - 1 from kit_audit_trail o where o.rev = s.rev) as siblings
                      from seq s
                    """,
                ).setParameter("kitId", kitId)
                .resultList as List<Array<Any>>

        return rows.associate {
            (it[0] as Number).toLong() to Pair(it[1] as Boolean, (it[2] as Number).toInt())
        }
    }

    // fun kitAudits(page: PaginationInput?, id: Long): Page<KitAudit> {
    //     val f: PaginationInput = page ?: PaginationInput()

    //     return kitAudits.findAll(AuditEntity.id().eq(id), f.create())
    // }
}

/*
* Data class setup to model the response for this particular query.
* If selectEntitiesOnly is set to true in the AuditReader query, this class need not be used. Instead, a list of Kits is sent back
*/
data class KitAudit(
    val entity: Kit,
    val revision: CustomRevisionInfo,
    val type: RevisionType,
    /** See the field documentation on KitRevision in kitAuditTrail.graphqls. */
    val changedNothingAudited: Boolean = false,
    val siblingKitsInRevision: Int = 0,
)

// interface KitAuditRepository : PagingAndSortingRepository<KitAudit, Long>, QuerydslPredicateExecutor<KitAudit>
