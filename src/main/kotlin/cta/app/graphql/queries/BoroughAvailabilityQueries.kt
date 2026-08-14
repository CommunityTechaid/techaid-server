package cta.app.graphql.queries

import cta.app.BoroughGroup
import cta.app.BoroughGroupRepository
import cta.app.DeviceType
import cta.app.ReferrerLimitException
import cta.app.ReferrerLimitExceptionRepository
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller

@Controller
class BoroughAvailabilityQueries(
    private val boroughGroups: BoroughGroupRepository,
    private val limitExceptions: ReferrerLimitExceptionRepository,
) {
    /**
     * No @PreAuthorize: the device request form is unauthenticated and needs this to filter its
     * own device list, exactly as it reads featureFlagsPublic to gate itself.
     *
     * Keyed by borough rather than by group, and already resolved. The public form knows which
     * borough a postcode fell in; it has no business knowing that Lambeth and Southwark share a
     * configuration record, and it must not be handed raw modes to interpret - resolving AUTO
     * would mean reading a stock signal only the server could have.
     */
    @QueryMapping
    fun boroughAvailabilityPublic(): List<BoroughAvailabilityGql> =
        boroughGroups
            .findAll()
            .flatMap { group ->
                group.boroughs.map { borough ->
                    BoroughAvailabilityGql(
                        borough = borough,
                        offered = group.offeredDeviceTypes(),
                        maxPerReferee = group.maxPerReferee,
                        unresolvedAuto = group.unresolvedAutoDeviceTypes(),
                    )
                }
            }.sortedBy { it.borough }

    @PreAuthorize("hasAnyAuthority('app:admin')")
    @QueryMapping
    fun boroughGroups(): List<BoroughGroupGql> = boroughGroups.findAll().map { it.toGql() }

    @PreAuthorize("hasAnyAuthority('app:admin')")
    @QueryMapping
    fun referrerLimitExceptions(): List<ReferrerLimitExceptionGql> = limitExceptions.findAll().map { it.toGql() }
}

data class BoroughAvailabilityGql(
    val borough: String,
    val offered: List<String>,
    val maxPerReferee: Int,
    val unresolvedAuto: List<String>,
)

data class DeviceAvailabilityGql(
    val deviceType: String,
    val mode: String,
)

data class BoroughGroupGql(
    val id: Long,
    val name: String,
    val boroughs: List<String>,
    val status: String,
    val maxPerReferee: Int,
    val availability: List<DeviceAvailabilityGql>,
    val updatedAt: String?,
)

data class ReferrerLimitExceptionGql(
    val id: Long,
    val organisationId: Long,
    val organisationName: String,
    val boroughGroupId: Long,
    val maxPerReferee: Int,
)

fun BoroughGroup.toGql(): BoroughGroupGql =
    BoroughGroupGql(
        id = id,
        name = name,
        boroughs = boroughs.sorted(),
        status = status.name,
        maxPerReferee = maxPerReferee,
        // Ordered by the canonical device-type order rather than by id or insertion, so the admin
        // grid's columns line up between groups without the client having to sort them.
        availability =
            availability
                .sortedBy { DeviceType.keys.indexOf(it.deviceType) }
                .map { DeviceAvailabilityGql(deviceType = it.deviceType, mode = it.mode.name) },
        updatedAt = updatedAt.toString(),
    )

fun ReferrerLimitException.toGql(): ReferrerLimitExceptionGql =
    ReferrerLimitExceptionGql(
        id = id,
        organisationId = referringOrganisation?.id ?: 0,
        organisationName = referringOrganisation?.name ?: "",
        boroughGroupId = group?.id ?: 0,
        maxPerReferee = maxPerReferee,
    )
