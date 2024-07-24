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
    QuerydslPredicateExecutor<ReferringOrganisationContact>{

        fun findOneByFullNameAndEmailAndReferringOrganisation(fullName: String, email: String, referringOrganisation: ReferringOrganisation): ReferringOrganisationContact?
    }

interface RequestCount {
    val phones: Long
    val laptops: Long
    val tablets: Long
    val allInOnes: Long
    val desktops: Long
    val other: Long
    val chromebooks: Long
    val commsDevices: Long
}
interface DeviceRequestRepository: PagingAndSortingRepository<DeviceRequest, Long>,
    QuerydslPredicateExecutor<DeviceRequest> {
    @Query(
        """
        SELECT
            coalesce(sum(src.phones),0) AS phones,
            coalesce(sum(src.laptops),0) AS laptops,
            coalesce(sum(src.tablets),0) AS tablets,
            coalesce(sum(src.allInOnes),0) AS allInOnes,
            coalesce(sum(src.desktops),0) AS desktops,
            coalesce(sum(src.other),0) AS other,
            coalesce(sum(src.chromebooks),0) AS chromebooks,
            coalesce(sum(src.commsDevices),0) AS commsDevices
        FROM (
            SELECT 
                id,
                coalesce(phones, 0) as phones,
                coalesce(laptops, 0) as laptops,
                coalesce(tablets, 0) as tablets,
                coalesce(all_in_ones, 0) as allInOnes,
                coalesce(desktops, 0) as desktops,
                coalesce(other, 0) as other,
                coalesce(chromebooks, 0) as chromebooks,
                coalesce(comms_devices, 0) as commsDevices 
            FROM device_requests dr
            WHERE dr.status not in ('REQUEST_COMPLETED','REQUEST_DECLINED','REQUEST_CANCELLED') 
        ) AS src
    """,
        nativeQuery = true
    )
    fun requestCount(): RequestCount
}

interface DeviceRequestNoteRepository: PagingAndSortingRepository<DeviceRequestNote, Long>,
    QuerydslPredicateExecutor<DeviceRequestNote>

interface ReferringOrganisationNoteRepository: PagingAndSortingRepository<ReferringOrganisationNote, Long>,
    QuerydslPredicateExecutor<ReferringOrganisationNote>

interface ReferringOrganisationContactNoteRepository: PagingAndSortingRepository<ReferringOrganisationContactNote, Long>,
    QuerydslPredicateExecutor<ReferringOrganisationContactNote>
