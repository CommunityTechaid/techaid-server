package cta.app.schedulingtasks

import cta.app.FeatureFlag
import cta.app.FeatureFlagRepository
import cta.app.services.GdprDonorCleanupService
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Instant
import java.util.Optional

class GdprDonorCleanupTest {
    private val cleanupService = mock(GdprDonorCleanupService::class.java)
    private val featureFlags = mock(FeatureFlagRepository::class.java)
    private val task = GdprDonorCleanup(cleanupService, featureFlags)

    private fun flag(enabled: Boolean) {
        `when`(featureFlags.findById(GdprDonorCleanup.FLAG_KEY))
            .thenReturn(Optional.of(FeatureFlag(key = GdprDonorCleanup.FLAG_KEY, enabled = enabled)))
    }

    private fun lastRan(instant: Instant?) {
        `when`(cleanupService.lastRunAt()).thenReturn(instant)
    }

    @Test
    fun `flag off - the cleanup service is never invoked - pg_cron remains the only retention path`() {
        flag(enabled = false)

        task.runRetentionCleanup()

        verify(cleanupService, never()).runRetentionCleanup()
    }

    @Test
    fun `flag row missing - fails closed and does not invoke the cleanup service`() {
        `when`(featureFlags.findById(GdprDonorCleanup.FLAG_KEY)).thenReturn(Optional.empty())

        task.runRetentionCleanup()

        verify(cleanupService, never()).runRetentionCleanup()
    }

    @Test
    fun `flag lookup throws - fails closed and does not invoke the cleanup service`() {
        `when`(featureFlags.findById(GdprDonorCleanup.FLAG_KEY)).thenThrow(RuntimeException("db unavailable"))

        assertDoesNotThrow { task.runRetentionCleanup() }

        verify(cleanupService, never()).runRetentionCleanup()
    }

    @Test
    fun `flag on and never run before - the cron path invokes the cleanup service`() {
        flag(enabled = true)
        lastRan(null)
        `when`(cleanupService.runRetentionCleanup()).thenReturn("GDPR Cleanup: Archived 0 inactive donors")

        task.runRetentionCleanup()

        verify(cleanupService, times(1)).runRetentionCleanup()
    }

    @Test
    fun `flag on and ran moments ago - the cron path is debounced and does not re-invoke`() {
        flag(enabled = true)
        lastRan(Instant.now().minusSeconds(30))

        task.runRetentionCleanup()

        verify(cleanupService, never()).runRetentionCleanup()
    }

    @Test
    fun `flag on and ran over an hour ago - the cron path invokes the cleanup service again`() {
        flag(enabled = true)
        lastRan(Instant.now().minus(GdprDonorCleanup.CRON_DEBOUNCE).minusSeconds(1))

        task.runRetentionCleanup()

        verify(cleanupService, times(1)).runRetentionCleanup()
    }

    @Test
    fun `flag on - the cleanup service throwing does not propagate - a scheduled job failure must not crash the app`() {
        flag(enabled = true)
        lastRan(null)
        `when`(cleanupService.runRetentionCleanup()).thenThrow(RuntimeException("boom"))

        assertDoesNotThrow { task.runRetentionCleanup() }
    }

    @Test
    fun `startup catch-up - flag off - never invokes the cleanup service`() {
        flag(enabled = false)

        task.catchUpOnStartup()

        verify(cleanupService, never()).runRetentionCleanup()
    }

    @Test
    fun `startup catch-up - no run ever recorded - invokes the cleanup service`() {
        flag(enabled = true)
        lastRan(null)

        task.catchUpOnStartup()

        verify(cleanupService, times(1)).runRetentionCleanup()
    }

    @Test
    fun `startup catch-up - last run recent - a UAT cold start mid-week does not re-run it`() {
        flag(enabled = true)
        lastRan(Instant.now().minusSeconds(3600))

        task.catchUpOnStartup()

        verify(cleanupService, never()).runRetentionCleanup()
    }

    @Test
    fun `startup catch-up - last run over a week ago - a container that missed its Monday slot catches up`() {
        flag(enabled = true)
        lastRan(Instant.now().minus(GdprDonorCleanup.CATCH_UP_THRESHOLD).minusSeconds(1))

        task.catchUpOnStartup()

        verify(cleanupService, times(1)).runRetentionCleanup()
    }

    @Test
    fun `startup catch-up - last-run lookup throws - fails open and runs anyway`() {
        flag(enabled = true)
        `when`(cleanupService.lastRunAt()).thenThrow(RuntimeException("db unavailable"))

        task.catchUpOnStartup()

        verify(cleanupService, times(1)).runRetentionCleanup()
    }
}
