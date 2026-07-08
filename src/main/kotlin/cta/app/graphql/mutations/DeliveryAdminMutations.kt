package cta.app.graphql.mutations

import cta.app.DeliveryBlockedDate
import cta.app.DeliveryBlockedDateRepository
import cta.app.DeliveryBookingRepository
import cta.app.DeliveryConfigRepository
import cta.app.DeliveryWindow
import cta.app.DeliveryWindowRepository
import cta.app.graphql.queries.DeliveryBlockedDateGql
import cta.app.graphql.queries.DeliveryConfigGql
import cta.app.graphql.queries.DeliveryWindowAdminGql
import cta.app.graphql.queries.toAdminGql
import cta.app.graphql.queries.toGql
import cta.toNullable
import graphql.GraphQLError
import graphql.GraphqlErrorBuilder
import jakarta.persistence.EntityNotFoundException
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.GraphQlExceptionHandler
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.execution.ErrorType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated
import java.time.LocalDate

/** Raised for admin-side delivery edits that can't be applied; surfaced as a BAD_REQUEST error. */
class DeliveryAdminException(
    message: String,
) : RuntimeException(message)

/**
 * Admin-only write side of the delivery-slots screen: edit settings, upsert/remove
 * windows, and manage blocked dates. Guarded by write:organisations, matching the
 * rest of the distributions & deliveries surface.
 */
@Controller
@Validated
@Transactional
class DeliveryAdminMutations(
    private val config: DeliveryConfigRepository,
    private val windows: DeliveryWindowRepository,
    private val blockedDates: DeliveryBlockedDateRepository,
    private val bookings: DeliveryBookingRepository,
) {
    @PreAuthorize("hasAnyAuthority('write:organisations')")
    @MutationMapping
    fun updateDeliveryConfig(
        @Argument @Valid data: UpdateDeliveryConfigInput,
    ): DeliveryConfigGql {
        val entity = config.getConfig()
        entity.enabled = data.enabled
        entity.daysOfWeek = normaliseDaysOfWeek(data.daysOfWeek)
        entity.leadTimeDays = data.leadTimeDays.coerceAtLeast(0)
        entity.advanceDays = data.advanceDays.coerceIn(1, 60)
        return config.save(entity).toGql()
    }

    @PreAuthorize("hasAnyAuthority('write:organisations')")
    @MutationMapping
    fun saveDeliveryWindow(
        @Argument @Valid data: DeliveryWindowInput,
    ): DeliveryWindowAdminGql {
        val entity =
            data.id?.toLongOrNull()?.let { id ->
                windows.findById(id).toNullable()
                    ?: throw DeliveryAdminException("No delivery window with id $id")
            } ?: DeliveryWindow()
        entity.name = data.name
        entity.startTime = data.startTime
        entity.endTime = data.endTime
        entity.icon = data.icon ?: entity.icon
        entity.capacity = data.capacity.coerceAtLeast(0)
        entity.sortOrder = data.sortOrder ?: entity.sortOrder
        entity.active = data.active ?: entity.active
        return windows.save(entity).toAdminGql()
    }

    @PreAuthorize("hasAnyAuthority('write:organisations')")
    @MutationMapping
    fun deleteDeliveryWindow(
        @Argument id: String,
    ): Boolean {
        val windowId = id.toLongOrNull() ?: return false
        if (bookings.countByWindowId(windowId) > 0) {
            throw DeliveryAdminException("This window already has bookings — deactivate it instead of deleting it.")
        }
        windows.deleteById(windowId)
        return true
    }

    @PreAuthorize("hasAnyAuthority('write:organisations')")
    @MutationMapping
    fun addDeliveryBlockedDate(
        @Argument @Valid data: DeliveryBlockedDateInput,
    ): DeliveryBlockedDateGql {
        val date =
            try {
                LocalDate.parse(data.date)
            } catch (e: Exception) {
                throw DeliveryAdminException("Invalid date: ${data.date}")
            }
        // One row per date — re-blocking a date just updates its reason.
        val existing = blockedDates.findAllByOrderByBlockedDateAsc().firstOrNull { it.blockedDate == date }
        val entity = existing ?: DeliveryBlockedDate(blockedDate = date)
        entity.reason = data.reason
        return blockedDates.save(entity).toGql()
    }

    @PreAuthorize("hasAnyAuthority('write:organisations')")
    @MutationMapping
    fun deleteDeliveryBlockedDate(
        @Argument id: String,
    ): Boolean {
        val blockedId = id.toLongOrNull() ?: return false
        blockedDates.deleteById(blockedId)
        return true
    }

    @GraphQlExceptionHandler
    fun handleDeliveryAdminError(ex: DeliveryAdminException): GraphQLError =
        GraphqlErrorBuilder
            .newError()
            .errorType(ErrorType.BAD_REQUEST)
            .message(ex.message)
            .build()
}

private val DOW_REGEX = Regex("[1-7]")

/** Keep only valid ISO day-of-week digits (1=Mon..7=Sun), de-duplicated, e.g. "2,4". */
private fun normaliseDaysOfWeek(raw: String): String =
    raw
        .split(",")
        .map { it.trim() }
        .filter { it.matches(DOW_REGEX) }
        .distinct()
        .joinToString(",")

data class UpdateDeliveryConfigInput(
    var enabled: Boolean = true,
    var daysOfWeek: String = "",
    var leadTimeDays: Int = 1,
    var advanceDays: Int = 4,
)

data class DeliveryWindowInput(
    var id: String? = null,
    @get:NotBlank var name: String = "",
    @get:NotBlank var startTime: String = "",
    @get:NotBlank var endTime: String = "",
    var icon: String? = null,
    var capacity: Int = 0,
    var sortOrder: Int? = null,
    var active: Boolean? = null,
)

data class DeliveryBlockedDateInput(
    @get:NotBlank var date: String = "",
    var reason: String? = null,
)
