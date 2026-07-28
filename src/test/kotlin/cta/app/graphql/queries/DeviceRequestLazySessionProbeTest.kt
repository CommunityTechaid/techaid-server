package cta.app.graphql.queries

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
import org.hibernate.Hibernate
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.ApplicationContext
import org.springframework.data.domain.PageRequest
import org.springframework.security.oauth2.jwt.JwtDecoder

/**
 * Bypasses GraphQL/MockMvc entirely to answer a narrow question raised while investigating
 * the findAllDeviceRequests query count: after DeviceRequestRepository's (Spring-Data,
 * @Transactional) findAll(...) call returns to plain test code with NO surrounding
 * transaction of its own, is the Hibernate session genuinely closed (open-in-view is false
 * in application.yml), or does something keep it bound to the thread regardless?
 */
@SpringBootTest(
    properties = ["spring.jpa.properties.hibernate.enable_lazy_load_no_trans=false"],
)
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class DeviceRequestLazySessionProbeTest {
    @MockBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var applicationContext: ApplicationContext

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

    @Test
    fun `probe whether the session survives past the repository call with the crutch disabled`() {
        val osivBeans =
            applicationContext.beanDefinitionNames.filter {
                it.contains("openEntityManager", ignoreCase = true) || it.contains("EntityManagerInView", ignoreCase = true)
            }
        println("=== OSIV-related bean names in context: $osivBeans ===")
        println(
            "=== environment.getProperty(spring.jpa.open-in-view) = ${applicationContext.environment.getProperty(
                "spring.jpa.open-in-view",
            )} ===",
        )
        println(
            "=== environment.getProperty(spring.jpa.hibernate.ddl-auto) = ${applicationContext.environment.getProperty(
                "spring.jpa.hibernate.ddl-auto",
            )} ===",
        )
        println(
            "=== environment.getProperty(spring.datasource.url) = ${applicationContext.environment.getProperty(
                "spring.datasource.url",
            )} ===",
        )
        println(
            "=== environment.getProperty(spring.jpa.properties.hibernate.enable_lazy_load_no_trans) = " +
                "${applicationContext.environment.getProperty("spring.jpa.properties.hibernate.enable_lazy_load_no_trans")} ===",
        )
        println(
            "=== environment.getProperty(spring.application.name) = ${applicationContext.environment.getProperty(
                "spring.application.name",
            )} ===",
        )
        println("=== environment.getProperty(server.port) = ${applicationContext.environment.getProperty("server.port")} ===")
        println("=== environment.getProperty(auth.admin-secret) = ${applicationContext.environment.getProperty("auth.admin-secret")} ===")
        val propertySourceNames =
            (applicationContext.environment as org.springframework.core.env.ConfigurableEnvironment)
                .propertySources
                .map {
                    it.name
                }
        println("=== property source names: $propertySourceNames ===")

        val org = referringOrganisationRepository.save(ReferringOrganisation(name = "Probe Org"))
        val contact =
            referringOrganisationContactRepository.save(
                ReferringOrganisationContact(
                    fullName = "Probe Contact",
                    email = "probe@example.com",
                    phoneNumber = "07000000000",
                    address = "1 Probe Street",
                    referringOrganisation = org,
                ),
            )
        val dr =
            deviceRequestRepository.save(
                DeviceRequest(
                    deviceRequestItems = DeviceRequestItems(laptops = 1),
                    referringOrganisationContact = contact,
                    isSales = false,
                    clientRef = "PROBE-1",
                    borough = "Southwark",
                    details = "probe",
                    deviceRequestNeeds = DeviceRequestNeeds(false, false, false),
                ),
            )
        deviceRequestNoteRepository.save(DeviceRequestNote(content = "probe note", deviceRequest = dr))
        kitRepository.save(Kit(model = "Probe Model", age = 1, deviceRequest = dr))

        // This call runs inside SimpleJpaRepository's own @Transactional(readOnly = true) —
        // by the time it returns HERE, that transaction (and, if open-in-view is really off,
        // the Hibernate session) should be finished.
        val page = deviceRequestRepository.findAll(PageRequest.of(0, 10))
        val loaded = page.content.first { it.id == dr.id }

        println(
            "=== referringOrganisationContact initialized right after repo call: ${Hibernate.isInitialized(
                loaded.referringOrganisationContact,
            )} ===",
        )
        println("=== kits initialized right after repo call: ${Hibernate.isInitialized(loaded.kits)} ===")
        println("=== deviceRequestNotes initialized right after repo call: ${Hibernate.isInitialized(loaded.deviceRequestNotes)} ===")

        // Now force lazy initialization from plain test code with NO transaction of its own,
        // NO open-in-view, and the enable_lazy_load_no_trans crutch DISABLED. If the session
        // truly closed when findAll() returned, this must throw LazyInitializationException.
        try {
            val kitsSize = loaded.kits.size
            println("=== kits.size access SUCCEEDED with crutch disabled: $kitsSize (no exception thrown) ===")
        } catch (e: Exception) {
            println("=== kits.size access THREW ${e::class.qualifiedName}: ${e.message} ===")
        }
    }
}
