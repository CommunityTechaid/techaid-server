package cta.app.graphql.queries

import cta.app.services.Coordinates
import cta.app.services.LocationService
import org.springframework.boot.info.BuildProperties
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.QueryMapping
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
