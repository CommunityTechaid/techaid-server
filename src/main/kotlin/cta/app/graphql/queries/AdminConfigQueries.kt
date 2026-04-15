package cta.app.graphql.queries

import cta.app.AdminConfig
import cta.app.AdminConfigRepository
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller

@Controller
class AdminConfigQueries(
    private val adminConfig: AdminConfigRepository,
) {
    @QueryMapping
    fun adminConfig(): AdminConfig = adminConfig.getAdminConfig()
}
