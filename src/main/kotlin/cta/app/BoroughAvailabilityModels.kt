package cta.app

import jakarta.persistence.CascadeType
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.SequenceGenerator
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

/**
 * The device types a request can ask for.
 *
 * The `key` is the wire value used everywhere else — it matches the field names on
 * [DeviceRequestItems] and the option values the dashboard's device-type control uses. It is what
 * is persisted and what crosses GraphQL, deliberately: a second spelling of the same eight things
 * is the sort of thing that stays in step for about a year and then quietly does not.
 */
enum class DeviceType(
    val key: String,
) {
    PHONES("phones"),
    TABLETS("tablets"),
    LAPTOPS("laptops"),
    ALL_IN_ONES("allInOnes"),
    DESKTOPS("desktops"),
    COMMS_DEVICES("commsDevices"),
    BROADBAND_HUBS("broadbandHubs"),
    OTHER("other"),
    ;

    companion object {
        val keys: List<String> = entries.map { it.key }

        fun byKey(key: String): DeviceType? = entries.firstOrNull { it.key == key }
    }
}

/**
 * How a device type is offered for a borough group.
 *
 * [AUTO] is **not implemented**. The design carries a stock-derived mode — offered while stock
 * lasts, closed when it runs out — but the stock signal it needs does not exist, and building one
 * was explicitly descoped (dashboard #179). It is accepted and stored so the admin screen can
 * present the mode and so switching it on later is a read-path change rather than a migration,
 * but nothing computes it.
 *
 * Until something does, AUTO resolves to **closed**, and the resolved view says so rather than
 * silently behaving like ON. An admin who picks a mode that does nothing must be able to see that
 * it does nothing; the alternative is a borough that looks open on the admin screen and offers
 * nothing on the public form, with no way to tell why.
 */
enum class AvailabilityMode {
    ON,
    OFF,
    AUTO,
    ;

    /** Whether this mode currently offers the device type. AUTO is closed — see the note above. */
    val offered: Boolean
        get() = this == ON
}

/** Lifecycle of a borough group. Descriptive only — nothing branches on it server-side. */
enum class BoroughGroupStatus {
    LIVE,
    PILOT,
    DRAFT,
}

/**
 * A set of boroughs configured as one unit.
 *
 * Lambeth and Southwark are one group because they have always been administered as one, and
 * Tower Hamlets is its own because it launched as a pilot with a narrower offer. A borough belongs
 * to at most one group, enforced by a unique index rather than by application code — see the
 * migration.
 */
@Entity
@Table(name = "borough_groups")
class BoroughGroup(
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "borough_groups-seq-generator")
    @SequenceGenerator(
        name = "borough_groups-seq-generator",
        sequenceName = "borough_groups_sequence",
        allocationSize = 1,
    )
    var id: Long = 0,
    var name: String = "",
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    var status: BoroughGroupStatus = BoroughGroupStatus.DRAFT,
    /**
     * Requests one referee may have open against this group.
     *
     * Not yet enforced: DEVICE_REQUEST_LIMIT in DeviceRequestMutations.kt is still the cap that
     * actually rejects a request, and it is global. This column records the intent per group and
     * is what the admin screen edits; wiring the check to read it is a separate change, so that
     * moving the number here cannot silently change who gets rejected today.
     */
    var maxPerReferee: Int = 0,
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "borough_group_boroughs",
        joinColumns = [JoinColumn(name = "borough_group_id")],
    )
    @Column(name = "borough")
    var boroughs: MutableSet<String> = mutableSetOf(),
    @OneToMany(
        fetch = FetchType.EAGER,
        cascade = [CascadeType.ALL],
        orphanRemoval = true,
        mappedBy = "group",
    )
    var availability: MutableSet<BoroughAvailability> = mutableSetOf(),
    @CreationTimestamp
    var createdAt: Instant = Instant.now(),
    @UpdateTimestamp
    var updatedAt: Instant = Instant.now(),
) {
    /**
     * The device type keys currently offered, with AUTO resolved to closed.
     *
     * The single place that turns stored modes into "what may this borough ask for". The public
     * query returns the output of this rather than raw modes, so the browser never decides
     * availability — a client that resolved AUTO itself would be guessing at a stock signal that
     * does not exist.
     */
    fun offeredDeviceTypes(): List<String> =
        availability
            .filter { it.mode.offered }
            .map { it.deviceType }
            .sortedBy { DeviceType.keys.indexOf(it) }

    /** Device types set to AUTO, which currently resolve to closed because nothing computes them. */
    fun unresolvedAutoDeviceTypes(): List<String> =
        availability
            .filter { it.mode == AvailabilityMode.AUTO }
            .map { it.deviceType }
            .sortedBy { DeviceType.keys.indexOf(it) }
}

/** One device type's mode within a group. Unique per (group, deviceType) — see the migration. */
@Entity
@Table(name = "borough_availability")
class BoroughAvailability(
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "borough_availability-seq-generator")
    @SequenceGenerator(
        name = "borough_availability-seq-generator",
        sequenceName = "borough_availability_sequence",
        allocationSize = 1,
    )
    var id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "borough_group_id")
    var group: BoroughGroup? = null,
    /** A [DeviceType.key]. Stored as the key, not the enum name — see [DeviceType]. */
    var deviceType: String = "",
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    var mode: AvailabilityMode = AvailabilityMode.OFF,
)

/**
 * An organisation-specific override of a group's [BoroughGroup.maxPerReferee].
 *
 * Exceptions only. An organisation with no row here gets the group's value, which keeps this
 * table short enough to read at a glance instead of holding a row per organisation per group.
 */
@Entity
@Table(name = "referrer_limit_exceptions")
class ReferrerLimitException(
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "referrer_limit_exceptions-seq-generator")
    @SequenceGenerator(
        name = "referrer_limit_exceptions-seq-generator",
        sequenceName = "referrer_limit_exceptions_sequence",
        allocationSize = 1,
    )
    var id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "referring_organisation_id")
    var referringOrganisation: ReferringOrganisation? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "borough_group_id")
    var group: BoroughGroup? = null,
    var maxPerReferee: Int = 0,
    @CreationTimestamp
    var createdAt: Instant = Instant.now(),
    @UpdateTimestamp
    var updatedAt: Instant = Instant.now(),
)

interface BoroughGroupRepository : JpaRepository<BoroughGroup, Long>

interface ReferrerLimitExceptionRepository : JpaRepository<ReferrerLimitException, Long>
