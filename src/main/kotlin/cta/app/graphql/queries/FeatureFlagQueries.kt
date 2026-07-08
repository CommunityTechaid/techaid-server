package cta.app.graphql.queries

import cta.app.FeatureFlag
import cta.app.FeatureFlagRepository
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller

@Controller
class FeatureFlagQueries(
    private val featureFlags: FeatureFlagRepository,
) {
    // No @PreAuthorize: public pages read this anonymously to decide whether to show themselves.
    @QueryMapping
    fun featureFlagsPublic(): List<FeatureFlagGql> = featureFlags.findAll().map { it.toGql() }

    @PreAuthorize("hasAnyAuthority('app:admin')")
    @QueryMapping
    fun featureFlags(): List<FeatureFlagGql> = featureFlags.findAll().map { it.toGql() }
}

data class FeatureFlagGql(
    val key: String,
    val enabled: Boolean,
    val updatedAt: String?,
)

fun FeatureFlag.toGql(): FeatureFlagGql = FeatureFlagGql(key = key, enabled = enabled, updatedAt = updatedAt.toString())
