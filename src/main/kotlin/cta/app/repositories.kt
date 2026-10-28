package cta.app

import com.querydsl.core.types.Predicate
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.querydsl.QuerydslPredicateExecutor
import java.util.Optional

interface DonorRepository :
    JpaRepository<Donor, Long>,
    QuerydslPredicateExecutor<Donor> {
    fun findByEmail(email: String): Donor?

    fun findByPhoneNumber(phone: String): Donor?
}

interface DonorParentRepository :
    JpaRepository<DonorParent, Long>,
    QuerydslPredicateExecutor<DonorParent>

/** One row of [DeviceRequestRepository.countMembershipUsage]. */
interface MembershipUsageCount {
    val organisationId: Long
    val used: Long
}

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
    """,
        nativeQuery = true,
    )
    fun statusCount(): List<KitStatusCount>

    @Query(
        """
        SELECT k.type AS type, count(*) AS count from kits k where k.archived != 'Y' group by k.type
    """,
        nativeQuery = true,
    )
    fun typeCount(): List<KitTypeCount>
}

interface NoteRepository :
    JpaRepository<Note, Long>,
    QuerydslPredicateExecutor<Note>

interface ReferringOrganisationRepository :
    JpaRepository<ReferringOrganisation, Long>,
    QuerydslPredicateExecutor<ReferringOrganisation>

interface ReferringOrganisationContactRepository :
    JpaRepository<ReferringOrganisationContact, Long>,
    QuerydslPredicateExecutor<ReferringOrganisationContact> {
    fun findOneByFullNameAndEmailAndReferringOrganisation(
        fullName: String,
        email: String,
        referringOrganisation: ReferringOrganisation,
    ): ReferringOrganisationContact?
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

interface DeviceRequestRepository :
    JpaRepository<DeviceRequest, Long>,
    QuerydslPredicateExecutor<DeviceRequest> {
    // DeviceRequest.referringOrganisationContact is a @ManyToOne with no fetch type, so it
    // is EAGER and loads on every row whether or not the caller wants it. Left alone that
    // is one extra SELECT per distinct contact on the page. Joining it into the page query
    // keeps the same semantics (it was already eager) and costs no extra round trips.
    @EntityGraph(attributePaths = ["referringOrganisationContact"])
    override fun findAll(pageable: Pageable): Page<DeviceRequest>

    @EntityGraph(attributePaths = ["referringOrganisationContact"])
    override fun findAll(
        predicate: Predicate,
        pageable: Pageable,
    ): Page<DeviceRequest>

    /**
     * Open requests a contact holds within a given set of boroughs.
     *
     * The per-referee cap used to be one global number against
     * [ReferringOrganisationContact.requestCount], which counts every open request the contact
     * has anywhere. Limits are now set per borough group, so the count has to be scoped the same
     * way — otherwise Tower Hamlets' limit of 1 would be consumed by a referrer's existing
     * Lambeth requests and the pilot would reject almost everyone on day one.
     *
     * Takes the closed statuses as a parameter so it can share [CLOSED_REQUEST_STATUSES] rather
     * than repeat the literal — unlike the `@Formula` on the entity, a `@Query` parameter is not
     * an annotation constant and can hold the real enum set.
     *
     * `boroughs` must be passed already trimmed and lower-cased: the stored borough is normalised
     * the same way here so that matching agrees with how RefereeRequestLimitService resolves the
     * group in the first place. A borough that resolves to a group but does not count towards it
     * hands the referee silent extra headroom, which is the one failure mode a cap must not have.
     */
    @Query(
        """
        SELECT COUNT(d) FROM DeviceRequest d
        WHERE d.referringOrganisationContact.id = :contactId
          AND d.status NOT IN :closedStatuses
          AND LOWER(TRIM(d.borough)) IN :boroughs
    """,
    )
    fun countOpenForContactInBoroughs(
        contactId: Long,
        closedStatuses: Collection<DeviceRequestStatus>,
        boroughs: Collection<String>,
    ): Long

    /**
     * Requests that count towards membership allowances, grouped by organisation, for one membership
     * period (#311). One grouped query serves every organisation on a page that shares the period.
     *
     * Deliberately NOT filtered on `archived`: a request made by a referee who has since been
     * archived still used the organisation's allowance. `createdAt` is compared against
     * London-midnight bounds (see [MembershipPeriod]); `excluded` is
     * [MEMBERSHIP_NON_COUNTING_STATUSES].
     */
    @Query(
        """
        SELECT c.referringOrganisation.id AS organisationId, COUNT(d) AS used
        FROM DeviceRequest d JOIN d.referringOrganisationContact c
        WHERE c.referringOrganisation.id IN :organisationIds
          AND d.status NOT IN :excluded
          AND d.createdAt >= :from AND d.createdAt < :to
        GROUP BY c.referringOrganisation.id
    """,
    )
    fun countMembershipUsage(
        organisationIds: Collection<Long>,
        excluded: Collection<DeviceRequestStatus>,
        from: java.time.Instant,
        to: java.time.Instant,
    ): List<MembershipUsageCount>

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
            WHERE dr.status not in ($CLOSED_REQUEST_STATUSES_SQL)
        ) AS src
    """,
        nativeQuery = true,
    )
    fun requestCount(): RequestCount

    fun findByCorrelationId(correlationId: Long): Optional<DeviceRequest>

    fun findAllByCorrelationIdIsNotNull(): List<DeviceRequest>
}

interface DeviceRequestNoteRepository :
    JpaRepository<DeviceRequestNote, Long>,
    QuerydslPredicateExecutor<DeviceRequestNote>

interface ReferringOrganisationNoteRepository :
    JpaRepository<ReferringOrganisationNote, Long>,
    QuerydslPredicateExecutor<ReferringOrganisationNote>

interface ReferringOrganisationContactNoteRepository :
    JpaRepository<ReferringOrganisationContactNote, Long>,
    QuerydslPredicateExecutor<ReferringOrganisationContactNote>
