package cta.app.config

import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Starts the application on a REAL servlet container and talks to it over real HTTP.
 *
 * Everything else in this suite runs under `WebEnvironment.MOCK`, including
 * `ActuatorHealthDetailsTest`, which exercises `/actuator/health` through MockMvc. MockMvc calls
 * the MVC stack directly: no Tomcat is started, no socket is opened, and nothing proves the
 * application would actually boot as a server.
 *
 * That distinction earned its place during the Spring Boot 4 upgrade. Boot 4 renamed
 * `spring-boot-starter-web` to `-webmvc` and split the servlet and web-server support into
 * separate modules, so "the MVC stack responds" and "the container starts and listens" became
 * genuinely different claims. A packaging or servlet-module regression is invisible to every
 * other test here.
 *
 * Deliberately a smoke test, not a feature test: if this fails, nothing else in the suite is worth
 * reading. It does NOT cover the shipped jar or the production profile - only `bootRun` against a
 * real database does that, and CI does not.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class ApplicationStartupSmokeTest {
    @MockitoBean
    lateinit var jwtDecoder: JwtDecoder

    @LocalServerPort
    var port: Int = 0

    private fun get(path: String): HttpResponse<String> =
        HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `the servlet container starts, listens on a real port and serves actuator health`() {
        assertThat(port)
            .`as`("a real container must have been assigned a real port")
            .isGreaterThan(0)

        val response = get("/actuator/health")

        assertThat(response.statusCode())
            .`as`("health must answer over real HTTP, not just through MockMvc")
            .isEqualTo(200)
        assertThat(response.body())
            .`as`("the container liveness probe reads this")
            .contains("\"status\":\"UP\"")
    }
}
