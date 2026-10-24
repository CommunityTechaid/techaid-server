package cta.app.config

import cta.app.services.BookingRateLimiter
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * Wires the per-client-IP budget for the anonymous device request create the public referral form uses.
 * Same limiter class as delivery booking, separate bean and budget.
 *
 * Defaults (per hour, per client IP): 20 device requests. Measured in production
 * over 88 days to 2026-10-24, the busiest single IP made 5 anonymous device requests in an hour
 * (8 in a day). 20 leaves room for several
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
}
