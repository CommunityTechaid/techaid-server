package cta.app.graphql.queries

import cta.app.services.Coordinates
import cta.app.services.LocationService
import org.springframework.boot.info.BuildProperties
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller

data class BuildInfo(
    val version: String?,
    val name: String?,
    val time: String?,
    val commit: String?,
)

@Controller
class GlobalQueries(
    private val locationService: LocationService,
    private val buildProperties: BuildProperties?,
) {
    // Proxies the billed Google geocoding key; unused by the dashboard's public pages,
    // so any authenticated user (dashboard or admin token) may call it but anonymous
    // callers may not.
    @PreAuthorize("isAuthenticated()")
    @QueryMapping
    fun location(
        @Argument address: String,
    ): Coordinates? = locationService.findCoordinates(address)

    @QueryMapping
    fun buildInfo(): BuildInfo =
        BuildInfo(
            version = buildProperties?.version,
            name = buildProperties?.name,
            time = buildProperties?.time?.toString(),
            commit = buildProperties?.get("git.commit"),
        )
}
