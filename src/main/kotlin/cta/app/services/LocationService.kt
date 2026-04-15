package cta.app.services

import com.fasterxml.jackson.annotation.JsonProperty
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import org.springframework.web.util.UriComponentsBuilder

private val logger = KotlinLogging.logger {}

data class Coordinates(val lat: String, val lng: String, val address: String, val input: String = "")

@Service
class LocationService {
    @Value("\${google.places.key:}")
    private lateinit var key: String
    private val restTemplate = RestTemplate()

    fun findLocation(address: String): LocationResponse? {
        try {
            val uri = UriComponentsBuilder
                .fromHttpUrl("https://maps.google.com/maps/api/geocode/json")
                .queryParam("key", key)
                .queryParam("address", address)
                .build(true)
                .toUri()
            return restTemplate.getForEntity(uri, LocationResponse::class.java).body!!
        } catch (e: Exception) {
            logger.error("Geocoding request failed for address: {}", address, e)
        }
        return null
    }

    fun findCoordinates(address: String): Coordinates? {
        if (address.isBlank()) return null
        findLocation(address)?.results?.firstOrNull()?.let { location ->
            return Coordinates(
                lat = location.geometry.location.lat,
                lng = location.geometry.location.lng,
                address = location.formattedAddress,
                input = address
            )
        }
        return null
    }
}

data class LocationResponse(
    val status: String,
    val results: List<Result>
) {
    data class Result(
        @JsonProperty("formatted_address")
        val formattedAddress: String,
        val geometry: Geometry
    )

    data class Geometry(
        val location: Location
    )

    data class Location(
        val lat: String,
        val lng: String
    )
}
