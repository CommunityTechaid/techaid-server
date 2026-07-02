package cta.controllers

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CustomErrorModelTest {
    @Test
    fun `from maps the standard error attributes`() {
        val model =
            CustomErrorModel.from(
                500,
                "techaid-api",
                mapOf(
                    "error" to "Internal Server Error",
                    "message" to "boom",
                    "timestamp" to "2026-07-02T00:00:00Z",
                ),
            )

        assertEquals(500, model.status)
        assertEquals("techaid-api", model.application)
        assertEquals("Internal Server Error", model.error)
        assertEquals("boom", model.message)
    }

    @Test
    fun `from tolerates the error attribute being absent`() {
        // Some error paths (e.g. ErrorAttributeOptions without the ERROR include) omit the
        // "error" key; rendering the error page must not itself throw.
        val model = CustomErrorModel.from(404, "techaid-api", mapOf("timestamp" to "2026-07-02T00:00:00Z"))

        assertEquals(404, model.status)
        assertEquals("", model.error)
        assertEquals("", model.message)
    }
}
