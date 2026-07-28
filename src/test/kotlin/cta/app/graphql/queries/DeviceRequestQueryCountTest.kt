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
import org.junit.jupiter.api.Assertions.assertEquals
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
 * Diagnostic instrumentation for the "18 SQL round trips per findAllDeviceRequests page"
 * production finding (14-day App Insights measurement: 209.8ms total / 163.5ms Postgres /
 * 18 SQL calls, vs. 5 for findAllKits). It seeds a realistic page of device requests
 * (referring org contact, notes, kits) and counts+attributes every SQL statement Hibernate
 * issues while rendering the deviceRequestConnection GraphQL query, the shape the
 * dashboard's device request grid actually sends.
 *
 * It now also PINS the @EntityGraph fix on DeviceRequestRepository.findAll: without it,
 * the implicitly-EAGER referringOrganisationContact costs one extra SELECT per distinct
 * contact on the page, and the statement count rises again.
 *
 * Two SQL-observation channels are used together:
 *  - the "org.hibernate.SQL" logger (DEBUG), captured via a ListAppender, gives the literal
 *    statement text for attribution (which table, formula subquery, etc).
 *  - Hibernate's Statistics API (hibernate.generate_statistics=true) gives
 *    prepareStatementCount as an independent cross-check of the round-trip count.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = [
        // open-in-view=false and enable_lazy_load_no_trans=true are inherited from the main
        // application.yml (issue #105), so the session boundary here already matches
        // production and must not be re-pinned. Only the statistics switch is test-specific.
        "spring.jpa.properties.hibernate.generate_statistics=true",
    ],
)
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class DeviceRequestQueryCountTest {
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

    /**
     * Seeds one page's worth of realistic data: 1 referring organisation, 5 contacts under
     * it (2 device requests each), 10 device requests (matching the default page size of
     * PaginationInput), 2 kits and 2 notes per device request. [prefix] isolates each test's
     * rows from the others, since the embedded DB/Spring context is shared across @Test
     * methods in this class.
     */
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

    private fun pageQuery(
        prefix: String,
        includeKits: Boolean,
    ): String {
        val kitsField = if (includeKits) "kits { id type status model }" else ""
        return """
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
                  $kitsField
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
    }

    private fun runAndReport(
        label: String,
        query: String,
    ): List<String> {
        appender.list.clear()
        val sessionFactory = entityManagerFactory.unwrap(SessionFactory::class.java)
        sessionFactory.statistics.clear()

        val body = objectMapper.writeValueAsString(mapOf("query" to query))
        val result =
            mockMvc
                .perform(
                    post("/graphql")
                        .with(jwt().authorities(SimpleGrantedAuthority("read:organisations")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body),
                ).andReturn()

        val responseBody = result.response.contentAsString
        assertTrue(!responseBody.contains("\"errors\""), "expected no GraphQL errors, got: $responseBody")

        val statements = appender.list.map { it.formattedMessage }
        val prepareStatementCount = sessionFactory.statistics.prepareStatementCount

        println("=== $label: response ===")
        println(responseBody)
        println("=== $label: ${statements.size} SQL statements (logger), prepareStatementCount=$prepareStatementCount ===")
        statements.forEachIndexed { index, sql ->
            println("--- statement #${index + 1} ---")
            println(sql)
        }
        println("=== end $label ===")
        return statements
    }

    @Test
    fun `count and attribute every SQL statement for a page of device requests with kits selected`() {
        seedPage("A")
        val statements = runAndReport("full selection (with kits)", pageQuery("A", includeKits = true))
        assertNoPerContactSelect(statements)
    }

    @Test
    fun `count and attribute every SQL statement for a page of device requests without kits selected`() {
        seedPage("B")
        val statements =
            runAndReport("list-view selection (kitCount formula only, no kits)", pageQuery("B", includeKits = false))
        assertNoPerContactSelect(statements)
    }

    /**
     * The regression guard for the @EntityGraph on DeviceRequestRepository.findAll.
     *
     * referringOrganisationContact is a @ManyToOne with no fetch type, so it is EAGER and
     * Hibernate loads it for every row. Without the join it issues one standalone SELECT per
     * DISTINCT contact on the page — 5 for this fixture's 10 requests across 5 contacts. With
     * the join it issues none, because the contact arrives with the page query.
     *
     * Counting standalone contact SELECTs rather than the total statement count keeps this
     * assertion tied to the specific defect: it cannot be accidentally satisfied by an
     * unrelated change that happens to move the total.
     */
    private fun assertNoPerContactSelect(statements: List<String>) {
        val perContactSelects =
            statements.filter { sql ->
                // hibernate.format_sql is true in the main config, so statements arrive
                // multi-line and leading-whitespace-prefixed — normalise before matching.
                val normalised = sql.replace(Regex("\\s+"), " ").trim().lowercase()
                // The discriminator is which table drives the FROM clause. A standalone
                // contact load selects FROM referring_organisation_contacts; the join-fetched
                // page query selects FROM device_requests and merely JOINs the contact in.
                // (Do not exclude on "join" generally — the standalone contact select itself
                // joins referring_organisations for its own eager parent.)
                normalised.startsWith("select") &&
                    normalised.contains("from referring_organisation_contacts")
            }

        assertEquals(
            0,
            perContactSelects.size,
            "referringOrganisationContact must be join-fetched with the page query, but " +
                "${perContactSelects.size} standalone contact SELECT(s) were issued — the " +
                "@EntityGraph on DeviceRequestRepository.findAll is missing or ineffective:\n" +
                perContactSelects.joinToString("\n"),
        )
    }
}
