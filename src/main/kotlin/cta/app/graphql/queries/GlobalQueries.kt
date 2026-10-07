package cta.app.graphql.queries

import org.springframework.boot.info.BuildProperties
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
    private val buildProperties: BuildProperties?,
) {
    @QueryMapping
    fun buildInfo(): BuildInfo =
        BuildInfo(
            version = buildProperties?.version,
            name = buildProperties?.name,
            time = buildProperties?.time?.toString(),
            commit = buildProperties?.get("git.commit"),
        )
}
