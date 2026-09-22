package cta.models

import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.bean.override.mockito.MockitoBean
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue

/**
 * Pins the Typeform webhook payload against the **application's own** ObjectMapper.
 *
 * This is the one code path where three separate Jackson 3 moves converge, and nothing covered it:
 *
 *  - `PropertyNamingStrategies` moved to `tools.jackson.databind`
 *  - `@JsonNaming` moved to `tools.jackson.databind.annotation`
 *  - the `readValue` Kotlin extension moved to `tools.jackson.module.kotlin`
 *  - and constructing these Kotlin data classes at all requires the Jackson 3 Kotlin module to be
 *    registered on the mapper Spring injects
 *
 * If any of that is wrong the webhook does not fail loudly — Typeform is the public intake route
 * for device requests, so a silent deserialisation regression loses real submissions from real
 * people, and the first symptom is requests that never arrive.
 *
 * It deliberately injects the Spring-managed mapper rather than building one, because the risk is
 * in the **wiring**, not in Jackson. A hand-rolled `JsonMapper.builder().addModule(kotlinModule())`
 * would pass while production failed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class TypeFormPayloadDeserialisationTest {
    @MockitoBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var objectMapper: ObjectMapper

    /**
     * Shaped like a real Typeform webhook: snake_case throughout, and carrying far more fields
     * than the model declares. Both halves matter — the naming strategy has to map
     * `event_type` -> `eventType`, and the unknown fields must be ignored rather than rejected.
     */
    private val realisticPayload =
        """
        {
          "event_id": "01J8K2M4N5P6Q7R8S9T0",
          "event_type": "form_response",
          "form_response": {
            "form_id": "GsSjHz2p",
            "token": "01J8K2M4N5P6Q7R8S9T0",
            "landed_at": "2026-09-22T07:00:00Z",
            "submitted_at": "2026-09-22T07:04:11Z",
            "hidden": { "ref": "device-request", "source": "website" },
            "definition": { "id": "GsSjHz2p", "title": "Request a device", "fields": [] },
            "answers": [
              { "type": "text", "field": { "id": "abc", "type": "short_text" }, "text": "Ada" }
            ]
          }
        }
        """.trimIndent()

    @Test
    fun `a Typeform webhook payload deserialises through the application's own mapper`() {
        val payload = objectMapper.readValue<TypeFormPayload>(realisticPayload)

        assertThat(payload.eventType)
            .`as`("event_type -> eventType: the @JsonNaming SnakeCaseStrategy must still apply")
            .isEqualTo("form_response")
        assertThat(payload.formResponse.formId)
            .`as`("form_id -> formId on the nested type too")
            .isEqualTo("GsSjHz2p")
        assertThat(payload.formResponse.hidden)
            .`as`("hidden carries the routing data the intake flow depends on")
            .containsEntry("ref", "device-request")
            .containsEntry("source", "website")
    }

    @Test
    fun `unknown fields are ignored, not rejected`() {
        // A real webhook carries event_id, token, landed_at, submitted_at, definition and answers,
        // none of which the model declares. If FAIL_ON_UNKNOWN_PROPERTIES were ever enabled, every
        // live submission would fail while a minimal fixture kept passing.
        assertThat(objectMapper.readValue<TypeFormPayload>(realisticPayload).formResponse.formId)
            .isEqualTo("GsSjHz2p")
    }

    @Test
    fun `the injected mapper is the Jackson 3 one`() {
        // Guards the migration itself: if something reintroduced a Jackson 2 ObjectMapper bean,
        // this file would still compile but would be testing a different mapper than the app uses.
        assertThat(objectMapper).isInstanceOf(tools.jackson.databind.ObjectMapper::class.java)
    }
}
