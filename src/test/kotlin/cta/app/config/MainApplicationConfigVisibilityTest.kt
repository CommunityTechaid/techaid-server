package cta.app.config

import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.core.env.Environment
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.junit.jupiter.SpringExtension

/**
 * Pins that a Spring-context test sees the application's REAL configuration.
 *
 * Incident (issue #105): `src/test/resources/application.yml` and
 * `src/main/resources/application.yml` both compile to `application.yml` at the classpath
 * root. Gradle puts test resources first, so Spring found exactly one file — the test one —
 * and the main config was invisible. The two did NOT merge.
 *
 * Consequence: no test had ever run against the settings that ship. Every main-only
 * `spring.*` property silently fell back to a Spring Boot default, most dangerously
 * `spring.jpa.open-in-view`, which was `false` in production and `true` (Boot's default) in
 * every test — precisely the setting that decides whether a lazy association access works or
 * throws. Tests could pass while production broke.
 *
 * This test asserts the OUTCOME, not the mechanism: whatever layout the test config uses, the
 * production-critical properties must be in effect when a test boots a context.
 */
@ExtendWith(SpringExtension::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class MainApplicationConfigVisibilityTest {
    @MockBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var environment: Environment

    /**
     * The JPA session-boundary settings. These decide whether lazy loading works, and they are
     * the reason the shadowing was dangerous rather than merely untidy.
     */
    @Test
    fun `jpa session boundary settings match production`() {
        assertThat(environment.getProperty("spring.jpa.open-in-view"))
            .`as`("open-in-view must be false as in main config, not Boot's default true")
            .isEqualTo("false")
        assertThat(
            environment.getProperty("spring.jpa.properties.hibernate.enable_lazy_load_no_trans"),
        ).isEqualTo("true")
    }

    /** Schema management: tests must validate against Flyway output, never auto-DDL. */
    @Test
    fun `schema management settings match production`() {
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate")
        assertThat(environment.getProperty("spring.flyway.out-of-order")).isEqualTo("true")
    }

    /** Bean lifecycle: production boots lazily; tests must exercise the same wiring. */
    @Test
    fun `bean initialisation strategy matches production`() {
        assertThat(environment.getProperty("spring.main.lazy-initialization")).isEqualTo("true")
    }

    /**
     * A canary for the shadowing itself. `logging.level.cta` exists only in the main config and
     * has no bearing on behaviour — if it disappears, the main file has stopped being read at
     * all, which is the failure mode this test exists to catch.
     */
    @Test
    fun `main-only properties are reachable at all`() {
        assertThat(environment.getProperty("logging.level.cta"))
            .`as`("main application.yml is not being read")
            .isNotNull()
    }
}
