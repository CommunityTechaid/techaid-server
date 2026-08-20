package cta.app

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.LockModeType
import jakarta.persistence.ManyToOne
import jakarta.persistence.SequenceGenerator
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.querydsl.QuerydslPredicateExecutor
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.time.LocalDate

/**
 * Singleton configuration for the public delivery-booking flow. A future admin
 * settings screen edits this row; for now it is seeded by Flyway with the values
 * the original mock hard-coded (Tue/Thu, 1-day lead time, 4 upcoming days).
 */
@Entity
@Table(name = "delivery_config")
class DeliveryConfig(
    @Id
    val id: Long = 1L, // singleton
    var enabled: Boolean = true,
    /** Comma-separated ISO day-of-week numbers we deliver on (1=Mon .. 7=Sun), e.g. "2,4" = Tue & Thu. */
    @Column(name = "days_of_week")
    var daysOfWeek: String = "2,4",
    /** Earliest bookable day is today + leadTimeDays. */
    var leadTimeDays: Int = 1,
    /** How many upcoming delivery days to offer. */
    var advanceDays: Int = 4,
    @CreationTimestamp
    var createdAt: Instant = Instant.now(),
    @UpdateTimestamp
    var updatedAt: Instant = Instant.now(),
)

/** A recurring delivery time window (e.g. a morning or afternoon slot) with its own capacity. */
@Entity
@Table(name = "delivery_windows")
class DeliveryWindow(
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "delivery_windows-seq-generator")
    @SequenceGenerator(
        name = "delivery_windows-seq-generator",
        sequenceName = "delivery_windows_sequence",
        allocationSize = 1,
    )
    var id: Long = 0,
    var name: String = "",
    var startTime: String = "",
    var endTime: String = "",
    var icon: String = "",
    var capacity: Int = 0,
    var sortOrder: Int = 0,
    var active: Boolean = true,
)

/** A single date on which no deliveries run (holiday, van unavailable, etc.). */
@Entity
@Table(name = "delivery_blocked_dates")
class DeliveryBlockedDate(
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "delivery_blocked_dates-seq-generator")
    @SequenceGenerator(
        name = "delivery_blocked_dates-seq-generator",
        sequenceName = "delivery_blocked_dates_sequence",
        allocationSize = 1,
    )
    var id: Long = 0,
    var blockedDate: LocalDate = LocalDate.EPOCH,
    var reason: String? = null,
)

/** A booked delivery slot submitted through the public booking flow. */
@Entity
@Table(name = "delivery_bookings")
class DeliveryBooking(
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "delivery_bookings-seq-generator")
    @SequenceGenerator(
        name = "delivery_bookings-seq-generator",
        sequenceName = "delivery_bookings_sequence",
        allocationSize = 1,
    )
    var id: Long = 0,
    var deliveryDate: LocalDate = LocalDate.EPOCH,
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "window_id")
    var window: DeliveryWindow? = null,
    var firstName: String = "",
    var surname: String = "",
    var email: String = "",
    var phone: String = "",
    @Column(columnDefinition = "TEXT")
    var address: String = "",
    @Column(name = "access_notes", columnDefinition = "TEXT")
    var accessNotes: String? = null,
    /** The booker's device request id — see V26.08.13.1500 for why this is numeric. */
    var ctaReference: Long = 0,
    @CreationTimestamp
    var createdAt: Instant = Instant.now(),
)

/**
 * A one-off, staff-granted exemption from the one-booking-per-CTA-reference rule. An unconsumed
 * row (`consumedAt == null`) lets exactly one extra booking through for [ctaReference]; the
 * booking that uses it stamps [consumedAt] so the exemption cannot be reused.
 */
@Entity
@Table(name = "delivery_booking_overrides")
class DeliveryBookingOverride(
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "delivery_booking_overrides-seq-generator")
    @SequenceGenerator(
        name = "delivery_booking_overrides-seq-generator",
        sequenceName = "delivery_booking_overrides_sequence",
        allocationSize = 1,
    )
    var id: Long = 0,
    var ctaReference: Long = 0,
    @CreationTimestamp
    var createdAt: Instant = Instant.now(),
    var createdBy: String? = null,
    @Column(columnDefinition = "TEXT")
    var note: String? = null,
    var consumedAt: Instant? = null,
)

interface DeliveryConfigRepository : JpaRepository<DeliveryConfig, Long> {
    /** The delivery config is a singleton row (id = 1). */
    @Query("SELECT * FROM delivery_config WHERE id = 1", nativeQuery = true)
    fun getConfig(): DeliveryConfig
}

interface DeliveryWindowRepository :
    JpaRepository<DeliveryWindow, Long>,
    QuerydslPredicateExecutor<DeliveryWindow> {
    fun findByActiveTrueOrderBySortOrderAsc(): List<DeliveryWindow>

    fun findAllByOrderBySortOrderAsc(): List<DeliveryWindow>

    /** Locks the window row so concurrent bookings serialise on the capacity check. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from DeliveryWindow w where w.id = :id")
    fun findByIdForUpdate(
        @Param("id") id: Long,
    ): DeliveryWindow?
}

interface DeliveryBlockedDateRepository : JpaRepository<DeliveryBlockedDate, Long> {
    fun existsByBlockedDate(blockedDate: LocalDate): Boolean

    fun findAllByBlockedDateGreaterThanEqual(blockedDate: LocalDate): List<DeliveryBlockedDate>

    fun findAllByOrderByBlockedDateAsc(): List<DeliveryBlockedDate>
}

interface DeliveryBookingRepository :
    JpaRepository<DeliveryBooking, Long>,
    QuerydslPredicateExecutor<DeliveryBooking> {
    fun countByDeliveryDateAndWindowId(
        deliveryDate: LocalDate,
        windowId: Long,
    ): Long

    fun countByWindowId(windowId: Long): Long

    fun findAllByOrderByDeliveryDateAscCreatedAtAsc(): List<DeliveryBooking>

    fun findAllByDeliveryDateBetweenOrderByDeliveryDateAscCreatedAtAsc(
        start: LocalDate,
        end: LocalDate,
    ): List<DeliveryBooking>

    /**
     * Backs the one-booking-per-reference policy: true if *any* booking with this ctaReference
     * exists, past or future. Team decision: a delivered booking still counts, because the
     * reference has already been used once — a fresh one needs a staff-granted
     * [DeliveryBookingOverride], not just the calendar moving on. Since ctaReference became a
     * bigint (V26.08.13.1500) this is plain equality — there is no case or whitespace to
     * normalise away.
     */
    fun existsByCtaReference(ctaReference: Long): Boolean

    /**
     * Postgres advisory transaction lock keyed on the ctaReference. Serialises concurrent
     * submits for the same reference across different windows/days, which the per-window row
     * lock (`findByIdForUpdate`) doesn't cover. Auto-released when the transaction commits or
     * rolls back.
     */
    @Query(value = "select pg_advisory_xact_lock(hashtext(:key))", nativeQuery = true)
    fun acquireReferenceLock(
        @Param("key") key: String,
    )
}

interface DeliveryBookingOverrideRepository : JpaRepository<DeliveryBookingOverride, Long> {
    /** The unconsumed override for a reference, if any — at most one can exist at a time. */
    fun findFirstByCtaReferenceAndConsumedAtIsNull(ctaReference: Long): DeliveryBookingOverride?

    /**
     * Unconsumed overrides for a batch of references in one query, so
     * `deliveryBookingsAdmin.additionalBookingAllowed` can resolve for a whole page without an
     * N+1 lookup per row.
     */
    fun findAllByCtaReferenceInAndConsumedAtIsNull(ctaReferences: Collection<Long>): List<DeliveryBookingOverride>
}
