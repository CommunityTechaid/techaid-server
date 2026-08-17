package cta.app.services

import cta.app.FeatureFlag
import cta.app.FeatureFlagRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.Optional

/**
 * The switch that decides whether borough configuration reaches the public device request
 * journey. Its whole job is to be conservative: anything other than a row that says `true` means
 * off, and off is the behaviour production ran before the configuration existed.
 *
 * The missing-row and read-failure cases are the ones worth pinning. A promote applies the
 * seeding migration and the new code in the same release, but a rollback to an image that
 * predates the migration — or a database blip on the flag read — must land on the pre-config
 * behaviour rather than on a half-applied configuration nobody has reviewed.
 */
class BoroughAvailabilityRulesTest {
    private val featureFlags = mock(FeatureFlagRepository::class.java)
    private val rules = BoroughAvailabilityRules(featureFlags)

    @Test
    fun `on only when the flag row says so`() {
        `when`(featureFlags.findById(BoroughAvailabilityRules.FLAG_KEY))
            .thenReturn(Optional.of(FeatureFlag(key = BoroughAvailabilityRules.FLAG_KEY, enabled = true)))

        assertThat(rules.enabled()).isTrue()
    }

    @Test
    fun `off when the flag row says so`() {
        `when`(featureFlags.findById(BoroughAvailabilityRules.FLAG_KEY))
            .thenReturn(Optional.of(FeatureFlag(key = BoroughAvailabilityRules.FLAG_KEY, enabled = false)))

        assertThat(rules.enabled()).isFalse()
    }

    @Test
    fun `off when the flag row does not exist at all`() {
        `when`(featureFlags.findById(BoroughAvailabilityRules.FLAG_KEY)).thenReturn(Optional.empty())

        assertThat(rules.enabled()).isFalse()
    }

    @Test
    fun `off, not thrown, when the flag read fails`() {
        `when`(featureFlags.findById(BoroughAvailabilityRules.FLAG_KEY))
            .thenThrow(RuntimeException("database unavailable"))

        // createDeviceRequest calls through this on the public form. A flag read that throws must
        // degrade to the pre-config cap, never surface as a failed referral.
        assertThat(rules.enabled()).isFalse()
    }
}
