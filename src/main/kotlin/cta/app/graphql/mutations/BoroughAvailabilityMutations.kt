package cta.app.graphql.mutations

import cta.app.AvailabilityMode
import cta.app.BoroughAvailability
import cta.app.BoroughGroup
import cta.app.BoroughGroupRepository
import cta.app.BoroughGroupStatus
import cta.app.DeviceType
import cta.app.ReferrerLimitException
import cta.app.ReferrerLimitExceptionRepository
import cta.app.ReferringOrganisationRepository
import cta.app.graphql.queries.BoroughGroupGql
import cta.app.graphql.queries.toGql
import cta.toNullable
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated

@Controller
@Validated
@Transactional
class BoroughAvailabilityMutations(
    private val boroughGroups: BoroughGroupRepository,
    private val limitExceptions: ReferrerLimitExceptionRepository,
    private val referringOrganisations: ReferringOrganisationRepository,
) {
    /**
     * Replace the whole configuration in one transaction.
     *
     * The admin screen stages edits behind an unsaved-changes counter and commits them with a
     * single Save, so this takes the complete intended state rather than a stream of per-cell
     * updates. Two things fall out of that and both are deliberate:
     *
     * - Groups and exceptions absent from the input are DELETED. "The config is what I sent" is
     *   the only rule that makes a staged editor safe; a merge would leave rows the admin thought
     *   they had removed.
     * - Everything is validated before anything is written, so a rejected save changes nothing.
     *   A half-applied availability grid is worse than a failed one - the admin would have no way
     *   to tell which half landed.
     */
    @PreAuthorize("hasAnyAuthority('app:admin')")
    @MutationMapping
    fun saveBoroughAvailability(
        @Argument @Valid data: SaveBoroughAvailabilityInput,
    ): List<BoroughGroupGql> {
        validate(data)

        val existing = boroughGroups.findAll().associateBy { it.id }
        val keptIds = data.groups.mapNotNull { it.id }.toSet()

        // Exceptions first: they hold a foreign key onto the groups, so deleting a group with an
        // exception still attached would fail on the constraint rather than on our own validation.
        limitExceptions.deleteAll()
        limitExceptions.flush()

        existing.values.filter { it.id !in keptIds }.forEach { boroughGroups.delete(it) }
        boroughGroups.flush()

        val saved =
            data.groups.map { input ->
                val group = input.id?.let { existing[it] } ?: BoroughGroup()
                group.name = input.name.trim()
                group.status = BoroughGroupStatus.valueOf(input.status)
                group.maxPerReferee = input.maxPerReferee
                group.boroughs = input.boroughs.map { it.trim() }.toMutableSet()
                applyAvailability(group, input.availability)
                boroughGroups.save(group)
            }
        boroughGroups.flush()

        val groupsById = saved.associateBy { it.id }
        data.exceptions.forEach { input ->
            val organisation =
                referringOrganisations.findById(input.organisationId).toNullable()
                    ?: throw IllegalArgumentException("Unknown referring organisation ${input.organisationId}")
            val group =
                groupsById[input.boroughGroupId]
                    ?: throw IllegalArgumentException("Unknown borough group ${input.boroughGroupId}")
            limitExceptions.save(
                ReferrerLimitException(
                    referringOrganisation = organisation,
                    group = group,
                    maxPerReferee = input.maxPerReferee,
                ),
            )
        }

        return saved.map { it.toGql() }
    }

    /**
     * Write every device type's mode, filling in anything the client omitted as OFF.
     *
     * Closed is the safe default for a missing value: a client that forgets a device type ends up
     * offering less than intended, which someone notices and fixes, rather than offering something
     * the charity cannot supply.
     */
    private fun applyAvailability(
        group: BoroughGroup,
        input: List<DeviceAvailabilityInput>,
    ) {
        val requested = input.associate { it.deviceType to AvailabilityMode.valueOf(it.mode) }
        val byType = group.availability.associateBy { it.deviceType }

        DeviceType.keys.forEach { key ->
            val mode = requested[key] ?: AvailabilityMode.OFF
            val row = byType[key]
            if (row == null) {
                group.availability.add(BoroughAvailability(group = group, deviceType = key, mode = mode))
            } else {
                row.mode = mode
            }
        }

        // Drop rows for device types that are no longer known at all, so a renamed or retired type
        // cannot linger and quietly keep offering itself.
        group.availability.removeIf { it.deviceType !in DeviceType.keys }
    }

    /** Everything that must hold before a single row is written. */
    private fun validate(data: SaveBoroughAvailabilityInput) {
        if (data.groups.isEmpty()) {
            throw IllegalArgumentException("At least one borough group is required")
        }

        data.groups.forEach { group ->
            runCatching { BoroughGroupStatus.valueOf(group.status) }.getOrElse {
                throw IllegalArgumentException(
                    "Unknown status '${group.status}' - expected one of ${BoroughGroupStatus.entries.joinToString()}",
                )
            }
            if (group.boroughs.isEmpty()) {
                throw IllegalArgumentException("Borough group '${group.name}' must contain at least one borough")
            }
            group.availability.forEach { availability ->
                if (DeviceType.byKey(availability.deviceType) == null) {
                    throw IllegalArgumentException(
                        "Unknown device type '${availability.deviceType}' - expected one of ${DeviceType.keys.joinToString()}",
                    )
                }
                runCatching { AvailabilityMode.valueOf(availability.mode) }.getOrElse {
                    throw IllegalArgumentException(
                        "Unknown availability mode '${availability.mode}' - expected one of ${AvailabilityMode.entries.joinToString()}",
                    )
                }
            }
        }

        // A borough in two groups makes "which rules govern this postcode?" ambiguous, and the
        // public query would emit that borough twice with different answers. The database enforces
        // this too, but failing here gives the admin the borough's name instead of a constraint
        // violation.
        val boroughs = data.groups.flatMap { it.boroughs.map { borough -> borough.trim() } }
        val duplicates =
            boroughs
                .groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
                .keys
        if (duplicates.isNotEmpty()) {
            throw IllegalArgumentException(
                "A borough may belong to only one group, but ${duplicates.joinToString()} appears in more than one",
            )
        }
    }
}

data class DeviceAvailabilityInput(
    @get:NotBlank var deviceType: String = "",
    @get:NotBlank var mode: String = "",
)

data class BoroughGroupInput(
    var id: Long? = null,
    @get:NotBlank var name: String = "",
    var boroughs: List<String> = emptyList(),
    @get:NotBlank var status: String = "",
    @get:Min(0) var maxPerReferee: Int = 0,
    var availability: List<DeviceAvailabilityInput> = emptyList(),
)

data class ReferrerLimitExceptionInput(
    var organisationId: Long = 0,
    var boroughGroupId: Long = 0,
    @get:Min(0) var maxPerReferee: Int = 0,
)

data class SaveBoroughAvailabilityInput(
    var groups: List<BoroughGroupInput> = emptyList(),
    var exceptions: List<ReferrerLimitExceptionInput> = emptyList(),
)
