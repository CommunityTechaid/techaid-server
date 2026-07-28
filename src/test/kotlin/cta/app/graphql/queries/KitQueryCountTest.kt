package cta.app.graphql.queries

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.ObjectMapper
import cta.app.Donor
import cta.app.DonorRepository
import cta.app.Kit
import cta.app.KitRepository
import cta.app.Note
import cta.app.NoteRepository
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import jakarta.persistence.EntityManagerFactory
import org.hibernate.SessionFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

/**
 * Contrast case for [DeviceRequestQueryCountTest]: same instrumentation, same page size,
 * applied to findAllKits (measured at 5 SQL calls/request in production vs. 18 for
 * findAllDeviceRequests). Kit's associations (donor, deviceRequest) are both explicit
 * FetchType.LAZY and Kit carries no @Formula — the structural opposite of DeviceRequest,
 * whose referringOrganisationContact is an implicit-EAGER @ManyToOne and which owns two
 * @OneToMany collections plus a @Formula. This test seeds an equivalent realistic page (10
 * kits, each with a donor and a note) and counts statements the same way, to make the
 * contrast concrete rather than asserted.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = [
        // Session-boundary settings come from the main application.yml (issue #105).
        "spring.jpa.properties.hibernate.generate_statistics=true",
    ],
)
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class KitQueryCountTest {
    @MockBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var entityManagerFactory: EntityManagerFactory

    @Autowired
    lateinit var donorRepository: DonorRepository

    @Autowired
    lateinit var kitRepository: KitRepository

    @Autowired
    lateinit var noteRepository: NoteRepository

    private val sqlLogger = LoggerFactory.getLogger("org.hibernate.SQL") as Logger
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun attachAppender() {
        appender.start()
        sqlLogger.addAppender(appender)
        sqlLogger.level = Level.DEBUG
    }

    @AfterEach
    fun detachAppender() {
        sqlLogger.detachAppender(appender)
        appender.stop()
    }

    /** 5 donors (2 kits each), 10 kits (matching the default page size), 1 note per kit. */
    private fun seedPage() {
        val donors =
            (1..5).map { i ->
                donorRepository.save(
                    Donor(
                        postCode = "SW9 $i",
                        phoneNumber = "0700000000$i",
                        email = "donor$i@example.com",
                        name = "Donor $i",
                        referral = "Word of mouth",
                    ),
                )
            }
        (1..10).forEach { i ->
            val kit =
                kitRepository.save(
                    Kit(model = "Model $i", age = 1, donor = donors[i % donors.size]),
                )
            noteRepository.save(Note(content = "Note for kit $i", kit = kit))
        }
    }

    private val pageQuery =
        """
        query {
          kitsConnection(page: { page: 0, size: 10 }, where: {}) {
            content {
              id
              type
              status
              model
              location
              createdAt
              updatedAt
              archived
              age
              donor { id name email phoneNumber }
              notes { id content }
            }
            totalElements
            totalPages
            size
            number
          }
        }
        """.trimIndent()

    @Test
    fun `count and attribute every SQL statement for a page of kits`() {
        seedPage()
        appender.list.clear()
        val sessionFactory = entityManagerFactory.unwrap(SessionFactory::class.java)
        sessionFactory.statistics.clear()

        val body = objectMapper.writeValueAsString(mapOf("query" to pageQuery))
        val result =
            mockMvc
                .perform(
                    post("/graphql")
                        .with(jwt().authorities(SimpleGrantedAuthority("read:kits")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body),
                ).andReturn()

        val responseBody = result.response.contentAsString
        assertTrue(!responseBody.contains("\"errors\""), "expected no GraphQL errors, got: $responseBody")

        val statements = appender.list.map { it.formattedMessage }
        val prepareStatementCount = sessionFactory.statistics.prepareStatementCount

        println("=== KitQueryCountTest: response ===")
        println(responseBody)
        println("=== KitQueryCountTest: ${statements.size} SQL statements (logger), prepareStatementCount=$prepareStatementCount ===")
        statements.forEachIndexed { index, sql ->
            println("--- statement #${index + 1} ---")
            println(sql)
        }
        println("=== end KitQueryCountTest ===")
    }
}
