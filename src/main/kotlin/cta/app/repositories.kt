package cta.app

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.querydsl.QuerydslPredicateExecutor
import java.util.Optional

interface DonorRepository : JpaRepository<Donor, Long>, QuerydslPredicateExecutor<Donor> {
    fun findByEmail(email: String): Donor?
    fun findByPhoneNumber(phone: String): Donor?
}

interface DonorParentRepository : JpaRepository<DonorParent, Long>, QuerydslPredicateExecutor<DonorParent>

interface KitStatusCount {
    val status: KitStatus
    val count: Long
}

interface KitTypeCount {
    val type: KitType
    val count: Long
}

interface KitRepository :
        JpaRepository<Kit, Long>,
        QuerydslPredicateExecutor<Kit> {
    @Query(
        """
        SELECT k.status AS status, count(*) AS count from kits k where k.archived != 'Y' group by k.status 
    """
    , nativeQuery = true)
    fun statusCount(): List<KitStatusCount>

    @Query(
        """
        SELECT k.type AS type, count(*) AS count from kits k where k.archived != 'Y' group by k.type
    """
    , nativeQuery = true)
    fun typeCount(): List<KitTypeCount>
}

interface NoteRepository: JpaRepository<Note, Long>,
    QuerydslPredicateExecutor<Note>

interface ReferringOrganisationRepository: JpaRepository<ReferringOrganisation, Long>,
    QuerydslPredicateExecutor<ReferringOrganisation>

interface ReferringOrganisationContactRepository: JpaRepository<ReferringOrganisationContact, Long>
    ,QuerydslPredicateExecutor<ReferringOrganisationContact> {

        fun findOneByFullNameAndEmailAndReferringOrganisation(fullName: String, email: String, referringOrganisation: ReferringOrganisation): ReferringOrganisationContact?
    }

interface RequestCount {
    val phones: Long
    val laptops: Long
    val tablets: Long
    val allInOnes: Long
    val desktops: Long
    val other: Long
    val commsDevices: Long
    val broadbandHubs: Long
}
interface DeviceRequestRepository:
    JpaRepository<DeviceRequest, Long>,
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
            coalesce(sum(src.commsDevices),0) AS commsDevices,
            coalesce(sum(src.broadbandHubs),0) AS broadbandHubs
        FROM (
            SELECT 
                id,
                coalesce(phones, 0) as phones,
                coalesce(laptops, 0) as laptops,
                coalesce(tablets, 0) as tablets,
                coalesce(all_in_ones, 0) as allInOnes,
                coalesce(desktops, 0) as desktops,
                coalesce(other, 0) as other,
                coalesce(comms_devices, 0) as commsDevices,
                coalesce(broadband_hubs, 0) as broadbandHubs 
            FROM device_requests dr
            WHERE dr.status not in ('REQUEST_COMPLETED','REQUEST_DECLINED','REQUEST_CANCELLED','REQUEST_COLLECTION_DELIVERY_FAILED') 
        ) AS src
    """,
        nativeQuery = true
    )
    fun requestCount(): RequestCount

    fun findByCorrelationId(correlationId: Long): Optional<DeviceRequest>

    fun findAllByCorrelationIdIsNotNull(): List<DeviceRequest>
}

interface DeviceRequestNoteRepository: JpaRepository<DeviceRequestNote, Long>,
    QuerydslPredicateExecutor<DeviceRequestNote>

interface ReferringOrganisationNoteRepository:
        JpaRepository<ReferringOrganisationNote, Long>,
        QuerydslPredicateExecutor<ReferringOrganisationNote>

interface ReferringOrganisationContactNoteRepository:
        JpaRepository<ReferringOrganisationContactNote, Long>,
        QuerydslPredicateExecutor<ReferringOrganisationContactNote>