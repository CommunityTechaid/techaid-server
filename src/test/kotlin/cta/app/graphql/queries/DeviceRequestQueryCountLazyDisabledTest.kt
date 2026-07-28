package cta.app.graphql.queries

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.ObjectMapper
import cta.app.DeviceRequest
import cta.app.DeviceRequestItems
import cta.app.DeviceRequestNeeds
import cta.app.DeviceRequestNote
import cta.app.DeviceRequestNoteRepository
import cta.app.DeviceRequestRepository
import cta.app.Kit
import cta.app.KitRepository
import cta.app.ReferringOrganisation
import cta.app.ReferringOrganisationContact
import cta.app.ReferringOrganisationContactRepository
import cta.app.ReferringOrganisationRepository
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import jakarta.persistence.EntityManagerFactory
import org.hibernate.SessionFactory
import org.junit.jupiter.api.AfterEach
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

/**
 * Companion to [DeviceRequestQueryCountTest]: reruns the same deviceRequestConnection page
 * with `hibernate.enable_lazy_load_no_trans` forced to false (the value application.yml
 * currently overrides to true). The application's normal config relies on this crutch to
 * paper over lazy-loading DeviceRequest.kits/deviceRequestNotes/referringOrganisationContact
 * from outside a Hibernate session (open-in-view is off). With the crutch removed, this
 * test captures whether the query count drops (meaning nothing outside a session was being
 * touched, so the crutch was a no-op for this path) or a LazyInitializationException is
 * thrown (meaning the resolver genuinely depends on the crutch) — and if it throws, exactly
 * which association/field is the trigger, from the exception message/stack.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = [
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.jpa.properties.hibernate.enable_lazy_load_no_trans=false",
        // src/test/resources/application.yml shadows src/main/resources/application.yml on
        // the test classpath, so open-in-view does NOT inherit main's "false" — it must be
        // pinned here, or Spring Boot's OpenEntityManagerInViewInterceptor (default true)
        // silently keeps a session open for the whole MockMvc request and this test would
        // give a false negative (no exception) that does not reflect production.
        "spring.jpa.open-in-view=false",
    ],
)
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class DeviceRequestQueryCountLazyDisabledTest {
    @MockBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var entityManagerFactory: EntityManagerFactory

    @Autowired
    lateinit var referringOrganisationRepository: ReferringOrganisationRepository

    @Autowired
    lateinit var referringOrganisationContactRepository: ReferringOrganisationContactRepository

    @Autowired
    lateinit var deviceRequestRepository: DeviceRequestRepository

    @Autowired
    lateinit var deviceRequestNoteRepository: DeviceRequestNoteRepository

    @Autowired
    lateinit var kitRepository: KitRepository

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

    private fun seedPage(prefix: String) {
        val org = referringOrganisationRepository.save(ReferringOrganisation(name = "Test Referring Org $prefix"))
        val contacts =
            (1..5).map { i ->
                referringOrganisationContactRepository.save(
                    ReferringOrganisationContact(
                        fullName = "Contact $prefix$i",
                        email = "contact$prefix$i@example.com",
                        phoneNumber = "0700000000$i",
                        address = "$i Test Street",
                        referringOrganisation = org,
                    ),
                )
            }

        (1..10).forEach { i ->
            val dr =
                deviceRequestRepository.save(
                    DeviceRequest(
                        deviceRequestItems = DeviceRequestItems(laptops = 1),
                        referringOrganisationContact = contacts[i % contacts.size],
                        isSales = false,
                        clientRef = "$prefix-CR-$i",
                        borough = "Southwark",
                        details = "Household needs a laptop",
                        deviceRequestNeeds = DeviceRequestNeeds(false, false, false),
                    ),
                )
            (1..2).forEach { n ->
                deviceRequestNoteRepository.save(
                    DeviceRequestNote(content = "Note $n for request $i", deviceRequest = dr),
                )
            }
            (1..2).forEach { k ->
                kitRepository.save(
                    Kit(model = "Model $k", age = 1, deviceRequest = dr),
                )
            }
        }
    }

    private fun pageQuery(prefix: String): String =
        """
        query {
          deviceRequestConnection(page: { page: 0, size: 10 }, where: { clientRef: { _contains: "$prefix-CR-" } }) {
            content {
              id
              correlationId
              deviceRequestItems { phones tablets laptops allInOnes desktops other commsDevices broadbandHubs }
              status
              createdAt
              updatedAt
              referringOrganisationContact { id fullName email phoneNumber archived requestCount }
              isSales
              clientRef
              borough
              details
              kitCount
              deviceRequestNeeds { hasInternet hasMobilityIssues needQuickStart }
              deviceRequestNotes { id content volunteer }
              collectionDate
              collectionMethod
              collectionContactName
              isPrepped
            }
            totalElements
            totalPages
            size
            number
          }
        }
        """.trimIndent()

    @Test
    fun `with the lazy-load-no-trans crutch disabled, report count drop or the exact LazyInitializationException`() {
        seedPage("C")
        appender.list.clear()
        val sessionFactory = entityManagerFactory.unwrap(SessionFactory::class.java)
        sessionFactory.statistics.clear()

        val body = objectMapper.writeValueAsString(mapOf("query" to pageQuery("C")))
        var responseBody = ""
        var thrown: Throwable? = null
        try {
            var result =
                mockMvc
                    .perform(
                        post("/graphql")
                            .with(jwt().authorities(SimpleGrantedAuthority("read:organisations")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body),
                    ).andReturn()

            // GraphQL over Spring MVC dispatches asynchronously; on the happy path
            // .andReturn() already has the body, but when a data fetcher throws mid-stream
            // the response isn't committed until the async dispatch completes.
            if (result.request.isAsyncStarted) {
                result = mockMvc.perform(asyncDispatch(result)).andReturn()
            }
            responseBody = result.response.contentAsString
        } catch (e: Throwable) {
            // MockMvc rethrows an uncaught servlet-layer exception (as opposed to a graceful
            // per-field GraphQL error in the response body) as a ServletException wrapping
            // the real cause. Capturing it here — rather than letting the test fail — is the
            // point of this test: the throw itself, and exactly which cause it wraps, is the
            // diagnostic result.
            thrown = e
        }

        val statements = appender.list.map { it.formattedMessage }
        val prepareStatementCount = sessionFactory.statistics.prepareStatementCount

        println("=== DeviceRequestQueryCountLazyDisabledTest: response ===")
        println(responseBody)
        if (thrown != null) {
            println("=== DeviceRequestQueryCountLazyDisabledTest: mockMvc.perform() threw ===")
            var cause: Throwable? = thrown
            while (cause != null) {
                println("${cause::class.qualifiedName}: ${cause.message}")
                cause = cause.cause
            }
        }
        println(
            "=== DeviceRequestQueryCountLazyDisabledTest: ${statements.size} SQL statements (logger), " +
                "prepareStatementCount=$prepareStatementCount ===",
        )
        statements.forEachIndexed { index, sql ->
            println("--- statement #${index + 1} ---")
            println(sql)
        }
        println("=== end DeviceRequestQueryCountLazyDisabledTest ===")
        // No assertion on outcome by design: this test exists to OBSERVE whether disabling
        // the crutch drops the query count or throws LazyInitializationException, and where.
        // See the investigation report for the captured result.
    }
}
