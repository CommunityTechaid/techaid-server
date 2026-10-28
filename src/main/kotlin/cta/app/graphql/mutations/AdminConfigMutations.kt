package cta.app.graphql.mutations

import cta.app.AdminConfig
import cta.app.AdminConfigRepository
import cta.app.MembershipOverLimitMode
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
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
    private val adminConfig: AdminConfigRepository,
) {
    @PreAuthorize("hasAnyAuthority('app:admin')")
    @MutationMapping
    fun updateAdminConfig(
        @Argument @Valid data: UpdateAdminConfigInput,
    ): AdminConfig {
        val entity = adminConfig.getAdminConfig()
        return data.apply(entity)
    }
}

data class UpdateAdminConfigInput(
    // Every field: null or absent = unchanged. The borough-availability screen sends only the six
    // canPublicRequest* flags and the dashboard's membership settings only the two membership
    // fields, so neither may reset the other's. The schema already declared the six nullable; the
    // Kotlin types didn't, so a partial input failed to bind.
    var canPublicRequestSIMCard: Boolean? = null,
    var canPublicRequestLaptop: Boolean? = null,
    var canPublicRequestPhone: Boolean? = null,
    var canPublicRequestBroadbandHub: Boolean? = null,
    var canPublicRequestTablet: Boolean? = null,
    var canPublicRequestDesktop: Boolean? = null,
    val membershipOverLimitMode: MembershipOverLimitMode? = null,
    @get:Min(0)
    val membershipGraceRequests: Int? = null,
) {
    fun apply(entity: AdminConfig): AdminConfig {
        val self = this
        return entity.apply {
            canPublicRequestSIMCard = self.canPublicRequestSIMCard ?: canPublicRequestSIMCard
            canPublicRequestLaptop = self.canPublicRequestLaptop ?: canPublicRequestLaptop
            canPublicRequestPhone = self.canPublicRequestPhone ?: canPublicRequestPhone
            canPublicRequestBroadbandHub = self.canPublicRequestBroadbandHub ?: canPublicRequestBroadbandHub
            canPublicRequestTablet = self.canPublicRequestTablet ?: canPublicRequestTablet
            canPublicRequestDesktop = self.canPublicRequestDesktop ?: canPublicRequestDesktop
            membershipOverLimitMode = self.membershipOverLimitMode ?: membershipOverLimitMode
            membershipGraceRequests = self.membershipGraceRequests ?: membershipGraceRequests
        }
    }
}
