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
    /**
     * Off-by-default gate for per-weekday borough restriction (sheet row 23). Independent of
     * BoroughAvailabilityRules.FLAG_KEY — see DeliveryService.checkBoroughDaySchedule.
     */
    var boroughSchedulingEnabled: Boolean = false,
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
 * A borough allowed to book on a given ISO weekday (sheet row 23), e.g. dayOfWeek=2 (Tuesday),
 * borough="Southwark". Held separately from BoroughGroup/BoroughAvailability — this restricts
 * which *day* a borough may book, not which device types it may ask for. A weekday with no rows
 * here is open to every borough; see DeliveryService.checkBoroughDaySchedule.
 */
@Entity
@Table(name = "delivery_day_boroughs")
class DeliveryDayBorough(
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "delivery_day_boroughs-seq-generator")
    @SequenceGenerator(
        name = "delivery_day_boroughs-seq-generator",
        sequenceName = "delivery_day_boroughs_sequence",
        allocationSize = 1,
    )
    var id: Long = 0,
    /** ISO day-of-week (1=Mon..7=Sun). */
    var dayOfWeek: Int = 0,
    var borough: String = "",
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

interface DeliveryDayBoroughRepository : JpaRepository<DeliveryDayBorough, Long> {
    fun findAllByOrderByDayOfWeekAscBoroughAsc(): List<DeliveryDayBorough>

    fun findAllByDayOfWeek(dayOfWeek: Int): List<DeliveryDayBorough>

    fun deleteByDayOfWeek(dayOfWeek: Int)
}
