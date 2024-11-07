package cta.app.graphql.queries

import cta.app.services.Coordinates
import cta.app.services.LocationService
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller

@Controller
class GlobalQueries(private val locationService: LocationService)  {
    @QueryMapping
    fun location(@Argument address: String): Coordinates? {
        return locationService.findCoordinates(address)
    }
}
