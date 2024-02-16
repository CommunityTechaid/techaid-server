package cta.app.graphql.queries

import com.coxautodev.graphql.tools.GraphQLQueryResolver
import cta.app.services.Coordinates
import cta.app.services.LocationService
import org.springframework.stereotype.Component

@Component
class GlobalQueries(private val locationService: LocationService) : GraphQLQueryResolver {
    fun location(address: String): Coordinates? {
        return locationService.findCoordinates(address)
    }
}
