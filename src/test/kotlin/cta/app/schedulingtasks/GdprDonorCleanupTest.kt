package cta.app.schedulingtasks

import cta.app.FeatureFlag
import cta.app.FeatureFlagRepository
import cta.app.services.GdprDonorCleanupService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.Optional

/**
 * Covers the gating and failure handling of the in-app GDPR retention cleanup — which is all
 * that is currently implementable.
 *
 * There is deliberately NO end-to-end test of the cleanup itself. gdpr.performgdprcleanup()
 * lives only in techaid_uat and techaid_prod and appears in no migration in this repo, so it
 * does not exist in the zonky test database. A test that exercised it would have to create its
 * own copy of the routine, which would assert against a fixture rather than against the thing
 * production runs — precisely the false confidence that let the donors_to_delete drift survive
 * (see cta.db.GdprSchemaConvergenceTest). Version that function first; then this gets a real
 * end-to-end test.
 *
 * The replaced draft of this job hard-deleted donors. These tests exist partly to make sure it
 * cannot start doing that again unnoticed.
 */
class GdprDonorCleanupTest {
    private lateinit var service: GdprDonorCleanupService
    private lateinit var featureFlags: FeatureFlagRepository
    private lateinit var job: GdprDonorCleanup

    @BeforeEach
    fun setUp() {
        service = mock(GdprDonorCleanupService::class.java)
        featureFlags = mock(FeatureFlagRepository::class.java)
        job = GdprDonorCleanup(service, featureFlags)
    }

    private fun flag(enabled: Boolean) {
        `when`(featureFlags.findById(GdprDonorCleanup.FLAG_KEY))
            .thenReturn(Optional.of(FeatureFlag(key = GdprDonorCleanup.FLAG_KEY, enabled = enabled)))
    }

    @Test
    fun `does nothing while the flag is off`() {
        flag(false)

        job.runRetentionCleanup()

        verify(service, never()).runRetentionCleanup()
    }

    @Test
    fun `does nothing when the flag row is absent`() {
        `when`(featureFlags.findById(GdprDonorCleanup.FLAG_KEY)).thenReturn(Optional.empty())

        job.runRetentionCleanup()

        verify(service, never()).runRetentionCleanup()
    }

    @Test
    fun `fails closed when the flag cannot be read`() {
        `when`(featureFlags.findById(GdprDonorCleanup.FLAG_KEY)).thenThrow(RuntimeException("db down"))

        job.runRetentionCleanup()

        verify(service, never()).runRetentionCleanup()
    }

    @Test
    fun `runs the cleanup once the flag is on`() {
        flag(true)
        `when`(service.runRetentionCleanup()).thenReturn("donors anonymised: 3")

        job.runRetentionCleanup()

        val calls = mockingDetails(service).invocations.count { it.method.name == "runRetentionCleanup" }
        assertEquals(1, calls)
    }

    @Test
    fun `a failing cleanup is swallowed so the scheduler survives`() {
        flag(true)
        `when`(service.runRetentionCleanup()).thenThrow(RuntimeException("permission denied for schema gdpr"))

        // Must not propagate: the prerequisites are unmet, so this is the expected failure mode
        // if the flag is switched on prematurely.
        job.runRetentionCleanup()
    }
}
