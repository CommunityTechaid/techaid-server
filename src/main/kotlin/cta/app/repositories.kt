package cta.app

import org.springframework.data.jpa.repository.Query
import org.springframework.data.querydsl.QuerydslPredicateExecutor
import org.springframework.data.repository.PagingAndSortingRepository

interface VolunteerRepository : PagingAndSortingRepository<Volunteer, Long>, QuerydslPredicateExecutor<Volunteer> {
    fun findByEmail(email: String): Volunteer?
}

interface ImageRepository : PagingAndSortingRepository<KitImage, Long>, QuerydslPredicateExecutor<KitImage>

interface DonorRepository : PagingAndSortingRepository<Donor, Long>, QuerydslPredicateExecutor<Donor> {
    fun findByEmail(email: String): Donor?
    fun findByPhoneNumber(phone: String): Donor?
}

interface KitStatusCount {
    val status: KitStatus
    val count: Long
}

interface KitTypeCount {
    val type: KitType
    val count: Long
}

interface KitRepository : PagingAndSortingRepository<Kit, Long>, QuerydslPredicateExecutor<Kit> {
    @Query(
        """
        SELECT k.status AS status, count(*) AS count from Kit k where k.archived != 'Y' group by k.status 
    """
    )
    fun statusCount(): List<KitStatusCount>

    @Query(
        """
        SELECT k.type AS type, count(*) AS count from Kit k where k.archived != 'Y' group by k.type
    """
    )
    fun typeCount(): List<KitTypeCount>
}

interface EmailTemplateRepository : PagingAndSortingRepository<EmailTemplate, Long>,
    QuerydslPredicateExecutor<EmailTemplate>

interface NoteRepository: PagingAndSortingRepository<Note, Long>,
    QuerydslPredicateExecutor<Note>

interface ReferringOrganisationRepository: PagingAndSortingRepository<ReferringOrganisation, Long>,
    QuerydslPredicateExecutor<ReferringOrganisation>

interface ReferringOrganisationContactRepository: PagingAndSortingRepository<ReferringOrganisationContact, Long>,
    QuerydslPredicateExecutor<ReferringOrganisationContact>
interface DeviceRequestRepository: PagingAndSortingRepository<DeviceRequest, Long>,
    QuerydslPredicateExecutor<DeviceRequest>

interface DeviceRequestNoteRepository: PagingAndSortingRepository<DeviceRequestNote, Long>,
    QuerydslPredicateExecutor<DeviceRequestNote>

interface ReferringOrganisationNoteRepository: PagingAndSortingRepository<ReferringOrganisationNote, Long>,
    QuerydslPredicateExecutor<ReferringOrganisationNote>

interface ReferringOrganisationContactNoteRepository: PagingAndSortingRepository<ReferringOrganisationContactNote, Long>,
    QuerydslPredicateExecutor<ReferringOrganisationContactNote>
