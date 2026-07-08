package cta.app.graphql.mutations

import cta.app.FeatureFlag
import cta.app.FeatureFlagRepository
import cta.app.graphql.queries.FeatureFlagGql
import cta.app.graphql.queries.toGql
import cta.toNullable
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated

@Controller
@Validated
@Transactional
class FeatureFlagMutations(
    private val featureFlags: FeatureFlagRepository,
) {
    @PreAuthorize("hasAnyAuthority('app:admin')")
    @MutationMapping
    fun updateFeatureFlag(
        @Argument @Valid data: UpdateFeatureFlagInput,
    ): FeatureFlagGql {
        val entity =
            featureFlags.findById(data.key).toNullable()
                ?: FeatureFlag(key = data.key)
        entity.enabled = data.enabled
        return featureFlags.save(entity).toGql()
    }
}

data class UpdateFeatureFlagInput(
    @get:NotBlank var key: String = "",
    var enabled: Boolean = false,
)
