package cta.app.config

import cta.app.services.BookingRateLimiter
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * Wires the per-client-IP budgets for the two anonymous creates the public referral form uses.
 * Same limiter class as delivery booking, separate beans and budgets, so exhausting one can never
 * block another (the form calls the contact create and then the request create).
 *
 * Defaults (per hour, per client IP): 20 device requests and 10 contacts. Measured in production
 * over 88 days to 2026-10-24, the busiest single IP made 5 anonymous device requests in an hour
 * (8 in a day), and anonymous contact creates were rare. 20 and 10 leave room for several
 * referrers sharing one office or council IP, plus the form's one automatic retry after a cold
 * start. Staff are exempt (see [cta.app.services.PublicCreateThrottle]).
 */
@Configuration
class PublicCreateRateLimiterConfig {
    @Bean
    fun publicDeviceRequestRateLimiter(
        @Value("\${public-create.rate-limit.enabled:true}") enabled: Boolean,
        @Value("\${public-create.rate-limit.device-request.max-requests:20}") maxRequests: Int,
        @Value("\${public-create.rate-limit.device-request.window-seconds:3600}") windowSeconds: Long,
    ): BookingRateLimiter = BookingRateLimiter(Clock.systemUTC(), enabled, maxRequests, windowSeconds)

    @Bean
    fun publicContactRateLimiter(
        @Value("\${public-create.rate-limit.enabled:true}") enabled: Boolean,
        @Value("\${public-create.rate-limit.contact.max-requests:10}") maxRequests: Int,
        @Value("\${public-create.rate-limit.contact.window-seconds:3600}") windowSeconds: Long,
    ): BookingRateLimiter = BookingRateLimiter(Clock.systemUTC(), enabled, maxRequests, windowSeconds)
}
