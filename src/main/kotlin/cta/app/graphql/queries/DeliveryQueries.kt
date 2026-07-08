package cta.app.graphql.queries

import cta.app.DeliveryWindow
import cta.app.services.DeliveryService
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller

/**
 * Public delivery availability. No @PreAuthorize: this is the anonymous surface the
 * public booking page reads (see PublicSurfaceAuthorizationTest).
 */
@Controller
class DeliveryQueries(
    private val delivery: DeliveryService,
) {
    @QueryMapping
    fun deliveryAvailabilityPublic(): List<DeliveryDayAvailabilityGql> =
        delivery.availability().map { day ->
            DeliveryDayAvailabilityGql(
                date = day.date.toString(),
                dayOfWeek = delivery.dayOfWeekName(day.date),
                dayLabel = delivery.dayLabel(day.date),
                windows =
                    day.windows.map { availability ->
                        DeliveryWindowAvailabilityGql(
                            window = DeliveryWindowGql.from(availability.window),
                            spotsRemaining = availability.spotsRemaining,
                        )
                    },
            )
        }
}

data class DeliveryWindowGql(
    val id: String,
    val name: String,
    val startTime: String,
    val endTime: String,
    val icon: String,
) {
    companion object {
        fun from(window: DeliveryWindow): DeliveryWindowGql =
            DeliveryWindowGql(
                id = window.id.toString(),
                name = window.name,
                startTime = window.startTime,
                endTime = window.endTime,
                icon = window.icon,
            )
    }
}

data class DeliveryWindowAvailabilityGql(
    val window: DeliveryWindowGql,
    val spotsRemaining: Int,
)

data class DeliveryDayAvailabilityGql(
    val date: String,
    val dayOfWeek: String,
    val dayLabel: String,
    val windows: List<DeliveryWindowAvailabilityGql>,
)
