package cta.app.graphql.queries

import cta.app.CustomRevisionInfo
import cta.app.DeviceRequest
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
@PreAuthorize("hasAnyAuthority('app:admin', 'read:organisations')")
class DeviceRequestAuditTrailQueries(
    @PersistenceContext
    private val em: EntityManager
)  {

    @Transactional
    @QueryMapping
    //This annotation is added so that the entity manager can be obtained.
    //Entity Manager might close otherwise
    fun deviceRequestAudits(@Argument where: Long): List<DeviceRequestAudit> {
        val reader: AuditReader = AuditReaderFactory.get(em)
        val finalResults: MutableList<DeviceRequestAudit> = mutableListOf()
        /*Use AuditReader to query the revisions as required and get the results as a list.
        Set selectedEntitiesOnly to true to get only the revisions of DeviceRequest instead of the extra information
        * */
        val results = reader.createQuery()
            .forRevisionsOfEntity(DeviceRequest::class.java, false, true)
            .add(AuditEntity.id().eq(where))
            .resultList

        /*
        * The returned result is of the form [{DeviceRequest, CustomRevisionInfo, RevisionType}]
        * We need to manually typecast this (I think. I did not find a straightforward way of doing it otherwise)
        */
        for (row in results) {
            @Suppress("UNCHECKED_CAST")
            val array = row as Array<Any>
            val entity: DeviceRequest = array[0] as DeviceRequest
            val revisionEntity: CustomRevisionInfo  = array[1] as CustomRevisionInfo
            val revisionType = array[2] as RevisionType

            val revision = DeviceRequestAudit(entity, revisionEntity, revisionType)
            //Keep adding the revisions into a list to return to graphQL query
            finalResults.add(revision)
        }

        return finalResults.toList()
    }
}

/*
* Data class setup to model the response for this particular query.
* If selectEntitiesOnly is set to true in the AuditReader query, this class need not be used. Instead, a list of DeviceRequests is sent back
*/
data class DeviceRequestAudit(val entity: DeviceRequest, val revision: CustomRevisionInfo, val type: RevisionType)
