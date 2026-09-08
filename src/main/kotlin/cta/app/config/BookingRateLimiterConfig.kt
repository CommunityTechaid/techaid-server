package cta.app.config

import cta.app.services.BookingRateLimiter
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * Wires the two [BookingRateLimiter] budgets for the public delivery-booking surface.
 *
 * These used to be a single shared bean keyed only by client IP, so a caller who checked
 * eligibility a few times (retyping a mistyped CTA reference on the new reference step, say)
 * spent the same budget as an actual submit — leaving submit refused with "Too many booking
 * attempts" even though the applicant never actually over-submitted. Splitting them into
 * separate beans/budgets means exhausting one can never block the other.
 *
 * Eligibility is a cheap read and gets a more generous allowance (20 per window) than submit
 * (5 per window, unchanged) because legitimate users retype references a few times before
 * getting it right.
 */
@Configuration
class BookingRateLimiterConfig {
    @Bean
    fun deliveryBookingSubmitRateLimiter(
        @Value("\${delivery-booking.rate-limit.enabled:true}") enabled: Boolean,
        @Value("\${delivery-booking.rate-limit.max-requests:5}") maxRequests: Int,
        @Value("\${delivery-booking.rate-limit.window-seconds:600}") windowSeconds: Long,
    ): BookingRateLimiter = BookingRateLimiter(Clock.systemUTC(), enabled, maxRequests, windowSeconds)

    @Bean
    fun deliveryBookingEligibilityRateLimiter(
        @Value("\${delivery-booking.rate-limit.enabled:true}") enabled: Boolean,
        @Value("\${delivery-booking.rate-limit.eligibility.max-requests:20}") maxRequests: Int,
        @Value("\${delivery-booking.rate-limit.eligibility.window-seconds:600}") windowSeconds: Long,
    ): BookingRateLimiter = BookingRateLimiter(Clock.systemUTC(), enabled, maxRequests, windowSeconds)
}
