package cta.app.graphql.queries

import cta.app.AdminConfig
import cta.app.AdminConfigRepository
import cta.graphql.KeyValuePair
import cta.graphql.PaginationInput
import org.springframework.data.domain.Page
import org.springframework.data.domain.Sort
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import java.util.Optional

@Controller
class AdminConfigQueries(
    private val adminConfig: AdminConfigRepository
)  {

    @PreAuthorize("hasAnyAuthority('app:admin')")
    @QueryMapping
    fun adminConfig(): AdminConfig {
        return adminConfig.getAdminConfig()
    }

}
