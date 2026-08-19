package cta.app.schedulingtasks

import cta.app.services.GdprDonorCleanupService
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Instant

/**
 * The job used to consult the `gdpr-in-app-cleanup` feature flag on every trigger and fail
 * closed. That flag staged the 2026-08-12 cutover from pg_cron and was removed on 2026-08-18
 * (V26.08.18.1500 deletes the row), so the only question left at each trigger is whether a run
 * is overdue.
 *
 * The first two tests are the ones that would have failed before that change: with no flag
 * repository in the constructor there is nothing left to switch retention off, and neither
 * trigger may consult one.
 */
class GdprDonorCleanupTest {
    private val cleanupService = mock(GdprDonorCleanupService::class.java)
    private val task = GdprDonorCleanup(cleanupService)

    private fun lastRan(instant: Instant?) {
        `when`(cleanupService.lastRunAt()).thenReturn(instant)
    }

    @Test
    fun `no flag to consult - the cron path runs on the schedule alone`() {
        lastRan(null)
        `when`(cleanupService.runRetentionCleanup()).thenReturn("GDPR Cleanup: Archived 0 inactive donors")

        task.runRetentionCleanup()

        verify(cleanupService, times(1)).runRetentionCleanup()
    }

    @Test
    fun `no flag to consult - the startup catch-up runs on overdue-ness alone`() {
        lastRan(null)

        task.catchUpOnStartup()

        verify(cleanupService, times(1)).runRetentionCleanup()
    }

    @Test
    fun `ran moments ago - the cron path is debounced and does not re-invoke`() {
        lastRan(Instant.now().minusSeconds(30))

        task.runRetentionCleanup()

        verify(cleanupService, never()).runRetentionCleanup()
    }

    @Test
    fun `ran over an hour ago - the cron path invokes the cleanup service again`() {
        lastRan(Instant.now().minus(GdprDonorCleanup.CRON_DEBOUNCE).minusSeconds(1))

        task.runRetentionCleanup()

        verify(cleanupService, times(1)).runRetentionCleanup()
    }

    @Test
    fun `the cleanup service throwing does not propagate - a scheduled job failure must not crash the app`() {
        lastRan(null)
        `when`(cleanupService.runRetentionCleanup()).thenThrow(RuntimeException("boom"))

        assertDoesNotThrow { task.runRetentionCleanup() }
    }

    @Test
    fun `startup catch-up - last run recent - a UAT cold start mid-week does not re-run it`() {
        lastRan(Instant.now().minusSeconds(3600))

        task.catchUpOnStartup()

        verify(cleanupService, never()).runRetentionCleanup()
    }

    @Test
    fun `startup catch-up - last run over a week ago - a container that missed its slot catches up`() {
        lastRan(Instant.now().minus(GdprDonorCleanup.CATCH_UP_THRESHOLD).minusSeconds(1))

        task.catchUpOnStartup()

        verify(cleanupService, times(1)).runRetentionCleanup()
    }

    @Test
    fun `startup catch-up - last-run lookup throws - fails open and runs anyway`() {
        `when`(cleanupService.lastRunAt()).thenThrow(RuntimeException("db unavailable"))

        task.catchUpOnStartup()

        verify(cleanupService, times(1)).runRetentionCleanup()
    }
}
