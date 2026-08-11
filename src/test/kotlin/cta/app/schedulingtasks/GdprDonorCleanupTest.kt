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
import java.util.Optional

class GdprDonorCleanupTest {
    private val cleanupService = mock(GdprDonorCleanupService::class.java)
    private val featureFlags = mock(FeatureFlagRepository::class.java)
    private val task = GdprDonorCleanup(cleanupService, featureFlags)

    private fun flag(enabled: Boolean) {
        `when`(featureFlags.findById(GdprDonorCleanup.FLAG_KEY))
            .thenReturn(Optional.of(FeatureFlag(key = GdprDonorCleanup.FLAG_KEY, enabled = enabled)))
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
    fun `flag on - the cleanup service is invoked exactly once`() {
        flag(enabled = true)
        `when`(cleanupService.runRetentionCleanup()).thenReturn("GDPR Cleanup: Archived 0 inactive donors")

        task.runRetentionCleanup()

        verify(cleanupService, times(1)).runRetentionCleanup()
    }

    @Test
    fun `flag on - the cleanup service throwing does not propagate - a scheduled job failure must not crash the app`() {
        flag(enabled = true)
        `when`(cleanupService.runRetentionCleanup()).thenThrow(RuntimeException("boom"))

        assertDoesNotThrow { task.runRetentionCleanup() }
    }
}
