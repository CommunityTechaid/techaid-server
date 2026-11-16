package cta.app.graphql.mutations

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cta.app.DeviceRequest
import cta.app.DeviceRequestItems
import cta.app.DeviceRequestRepository
import cta.app.DeviceRequestStatus
import cta.app.EqualitiesQuestions
import cta.app.ReferringOrganisation
import cta.app.ReferringOrganisationContact
import cta.app.ReferringOrganisationContactRepository
import cta.app.ReferringOrganisationRepository
import cta.app.services.EqualitiesSheetUnavailableException
import cta.app.services.EqualitiesSheetWriter
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.concurrent.CopyOnWriteArrayList

/** Stands in for the Google Sheet: records each row, or refuses while [failing]. */
class RecordingSheetWriter : EqualitiesSheetWriter {
    val rows = CopyOnWriteArrayList<List<String>>()

    @Volatile
    var failing = false

    override fun append(row: List<String>) {
        if (failing) throw EqualitiesSheetUnavailableException()
        rows.add(row)
    }
}

/**
 * The public submitEqualities mutation, over HTTP as the anonymous request form calls it.
 *
 * The Sheet is a recording fake here; what matters is the order of effects: nothing is completed unless the
 * row was written, nothing is written unless the request is waiting and the answers are valid, and
 * no answer ever reaches a log line.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
@Import(SubmitEqualitiesTest.FakeSheet::class)
class SubmitEqualitiesTest {
    @TestConfiguration
    class FakeSheet {
        @Bean
        @Primary
        fun recordingSheetWriter() = RecordingSheetWriter()
    }

    @MockitoBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var sheetWriter: RecordingSheetWriter

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var deviceRequests: DeviceRequestRepository

    @Autowired
    lateinit var referringOrganisations: ReferringOrganisationRepository

    @Autowired
    lateinit var referringOrganisationContacts: ReferringOrganisationContactRepository

    private val rootLogger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
    private val logs = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun captureLogs() {
        logs.start()
        rootLogger.addAppender(logs)
        sheetWriter.rows.clear()
        sheetWriter.failing = false
    }

    @AfterEach
    fun releaseLogs() {
        rootLogger.detachAppender(logs)
        logs.stop()
    }

    private fun waitingRequest(): DeviceRequest {
        val stamp = System.nanoTime()
        val organisation = referringOrganisations.save(ReferringOrganisation(name = "Org eq $stamp"))
        val contact =
            referringOrganisationContacts.save(
                ReferringOrganisationContact(
                    fullName = "Referee $stamp",
                    email = "referee-$stamp@example.com",
                    phoneNumber = "0$stamp",
                    address = "1 Test Street",
                    referringOrganisation = organisation,
                ),
            )
        return deviceRequests.save(
            DeviceRequest(
                deviceRequestItems = DeviceRequestItems(laptops = 1),
                referringOrganisationContact = contact,
                clientRef = "EQ",
                borough = "Lambeth",
                details = "Needs a laptop",
                deviceRequestNeeds = null,
                correlationId = stamp,
            ),
        )
    }

    private fun validAnswers(
        age: String = "25-34",
        benefits: String = "No",
    ): List<Pair<String, String>> =
        EqualitiesQuestions.QUESTIONS
            .filterNot { benefits == "Yes" && it.key == "noRecourseToPublicFunds" }
            .map {
                it.key to
                    when (it.key) {
                        "age" -> age
                        "stateBenefits" -> benefits
                        else -> it.choices.first()
                    }
            }

    private fun json(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun submit(
        correlationId: String,
        answers: List<Pair<String, String>>,
        ward: String? = "Brixton North",
    ): ResultActions {
        val answersJson = answers.joinToString(",") { (k, v) -> """{"key":${json(k)},"choice":${json(v)}}""" }
        val wardJson = ward?.let { json(it) } ?: "null"
        val body =
            """{"query":"mutation submit(${'$'}data: SubmitEqualitiesInput!) { submitEqualities(data: ${'$'}data) }",""" +
                """"variables":{"data":{"correlationId":${json(correlationId)},"ward":$wardJson,"answers":[$answersJson]}}}"""
        return mockMvc.perform(post("/graphql").contentType(MediaType.APPLICATION_JSON).content(body))
    }

    private fun capturedRow(): List<String> {
        assertEquals(1, sheetWriter.rows.size, "Expected exactly one row written to the Sheet")
        return sheetWriter.rows.single()
    }

    private fun assertNotLogged(secret: String) {
        val leaked = logs.list.filter { it.formattedMessage.contains(secret) || it.throwableProxy?.message?.contains(secret) == true }
        assertTrue(leaked.isEmpty(), "An equalities answer reached the logs: ${leaked.map { it.loggerName }}")
    }

    @Test
    fun `answers go to the Sheet, then the request completes`() {
        val request = waitingRequest()

        submit(request.correlationId.toString(), validAnswers(benefits = "Yes"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.submitEqualities").value(true))

        val row = capturedRow()
        val header = EqualitiesQuestions.SHEET_HEADER
        assertEquals(header.size, row.size)
        assertEquals(request.correlationId.toString(), row[header.indexOf("Correlation ID")])
        assertEquals("Lambeth", row[header.indexOf("Borough")])
        assertEquals("Brixton North", row[header.indexOf("Ward")])
        assertEquals("", row[header.indexOf("Is your client No Recourse to Public Funds?")])

        val saved = deviceRequests.findById(request.id).get()
        assertEquals(DeviceRequestStatus.PROCESSING_EQUALITIES_DATA_COMPLETE, saved.status)
        assertNull(saved.correlationId)
    }

    @Test
    fun `a Sheet failure changes nothing, so the form can retry`() {
        sheetWriter.failing = true
        val request = waitingRequest()

        submit(request.correlationId.toString(), validAnswers(), ward = "WARD-SECRET-5521")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errors[0].extensions.code").value(EqualitiesSheetUnavailableException.CODE))

        val saved = deviceRequests.findById(request.id).get()
        assertEquals(DeviceRequestStatus.NEW, saved.status)
        assertNotNull(saved.correlationId)
        assertNotLogged("WARD-SECRET-5521")
    }

    @Test
    fun `invalid answers are refused before the Sheet, and are not logged`() {
        val request = waitingRequest()

        submit(request.correlationId.toString(), validAnswers(age = "AGE-SECRET-7731"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errors[0].extensions.code").value("EQUALITIES_INVALID_ANSWERS"))

        assertTrue(sheetWriter.rows.isEmpty())
        assertEquals(DeviceRequestStatus.NEW, deviceRequests.findById(request.id).get().status)
        assertNotLogged("AGE-SECRET-7731")
        assertTrue(
            logs.list.any { it.formattedMessage.contains("<redacted: equalities>") },
            "The GraphQL error should still be logged, with its variables redacted",
        )
    }

    @Test
    fun `an unknown or already used correlation id writes nothing`() {
        submit("12345", validAnswers())
            .andExpect(jsonPath("$.errors[0].extensions.code").value("EQUALITIES_REQUEST_NOT_FOUND"))
        submit("not-a-number", validAnswers())
            .andExpect(jsonPath("$.errors[0].extensions.code").value("EQUALITIES_REQUEST_NOT_FOUND"))
        assertTrue(sheetWriter.rows.isEmpty())

        val request = waitingRequest()
        val correlationId = request.correlationId.toString()
        submit(correlationId, validAnswers()).andExpect(jsonPath("$.data.submitEqualities").value(true))
        submit(correlationId, validAnswers())
            .andExpect(jsonPath("$.errors[0].extensions.code").value("EQUALITIES_REQUEST_NOT_FOUND"))
        assertEquals(1, sheetWriter.rows.size)
    }

    @Test
    fun `a coercion error on this operation does not log the submitted value`() {
        val request = waitingRequest()
        val body =
            """{"query":"mutation renamed(${'$'}data: SubmitEqualitiesInput!) { submitEqualities(data: ${'$'}data) }",""" +
                """"variables":{"data":{"correlationId":"${request.correlationId}","ward":{"x":"COERCE-SECRET-3390"},"answers":[]}}}"""
        mockMvc
            .perform(post("/graphql").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(jsonPath("$.errors").exists())

        assertTrue(sheetWriter.rows.isEmpty())
        assertNotLogged("COERCE-SECRET-3390")
    }
}
