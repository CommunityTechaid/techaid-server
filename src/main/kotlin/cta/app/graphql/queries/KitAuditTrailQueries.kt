package cta.app.graphql.queries

import cta.app.CustomRevisionInfo
import cta.app.Kit
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.hibernate.envers.AuditReader
import org.hibernate.envers.AuditReaderFactory
import org.hibernate.envers.RevisionType
import org.hibernate.envers.query.AuditEntity
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional

@Controller
@PreAuthorize("hasAnyAuthority('read:kits', 'read:kits:assigned')")
class KitAuditTrailQueries(
    @PersistenceContext
    private val em: EntityManager
)  {

    @Transactional
    @QueryMapping
    //This annotation is added so that the entity manager can be obtained.
    //Entity Manager might close otherwise
    fun kitAudits(where: Long): List<Revision> {

        val reader: AuditReader = AuditReaderFactory.get(em)
        val finalResults: MutableList<Revision> = mutableListOf()

        /*Use AuditReader to query the revisions as required and get the results as a list.
        Set selectedEntitiesOnly to true to get only the revisions of Kit instead of the extra information
        * */
        val results = reader.createQuery()
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
            val revisionEntity: CustomRevisionInfo  = array[1] as CustomRevisionInfo
            val revisionType = array[2] as RevisionType

            val revision = Revision(entity, revisionEntity, revisionType)
            //Keep adding the revisions into a list to return to graphQL query
            finalResults.add(revision)
        }

        return finalResults.toList()
    }
}

/*
* Data class setup to model the response for this particular query.
* If selectEntitiesOnly is set to true in the AuditReader query, this class need not be used. Instead, a list of Kits is sent back
*/
data class Revision(val entity: Kit, val revision: CustomRevisionInfo, val type: RevisionType)