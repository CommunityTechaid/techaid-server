package cta.app.graphql.mutations

import cta.app.AdminConfig
import cta.app.AdminConfigRepository
import cta.toNullable
import jakarta.persistence.EntityNotFoundException
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated

@Controller
@Validated
@Transactional
class AdminConfigMutations(
    private val adminConfig: AdminConfigRepository
) {
    @PreAuthorize("hasAnyAuthority('app:admin')")
    @MutationMapping
    fun updateAdminConfig(@Argument @Valid data: UpdateAdminConfigInput): AdminConfig {
        val entity = adminConfig.findAdminConfig()
            ?: throw EntityNotFoundException("Unable to locate a organisation with id: ${data.id}")
        return data.apply(entity)
    }
}

data class UpdateAdminConfigInput(
    var canPublicRequestSIMCard: Boolean,
    var canPublicRequestLaptop: Boolean,
    var canPublicRequestPhone: Boolean,
    var canPublicRequestBroadbandHub: Boolean

) {
    fun apply(entity: AdminConfig): AdminConfig {
        val self = this
        return entity.apply {
            canPublicRequestSIMCard = self.canPublicRequestSIMCard
            canPublicRequestLaptop = self.canPublicRequestLaptop
            canPublicRequestPhone = self.canPublicRequestPhone
            canPublicRequestBroadbandHub = self.canPublicRequestBroadbandHub
        }
    }
}
