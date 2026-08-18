package cta.db

import cta.app.schedulingtasks.GdprDonorCleanup
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.ApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtDecoder

/**
 * Two halves of the same 2026-08-18 decision: the in-app GDPR job is no longer switchable, and
 * the test suite must never run it.
 *
 * 1. THE FLAG IS GONE. V26.08.11.1450 seeded `gdpr-in-app-cleanup` OFF to stage the cutover
 *    from pg_cron; V26.08.18.1500 deletes it now that GdprDonorCleanup ignores it. A row left
 *    behind would keep offering an off switch the code no longer honours — a control that
 *    looks live and does nothing is worse than no control, particularly a compliance one.
 *
 * 2. THE JOB IS INERT UNDER THE `test` PROFILE. Its startup catch-up fires on
 *    ApplicationReadyEvent and, with the flag gone, has nothing left to hold it back. The
 *    embedded database has a real gdpr.performgdprcleanup(), so without the @Profile("!test")
 *    exclusion every @SpringBootTest in this suite would anonymise its own fixture data on the
 *    way up — intermittently, depending on what each test had inserted.
 *
 *    This is test isolation, not a kill switch: build.gradle sets spring.profiles.active=test
 *    for the test task only, so UAT and production always have the bean. Asserting on the bean
 *    rather than on the annotation means someone removing @Profile to "simplify" it fails here
 *    instead of in a puzzling, unrelated test three months later.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class GdprInAppCleanupFlagRetiredTest {
    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var context: ApplicationContext

    @MockBean
    private lateinit var jwtDecoder: JwtDecoder

    @Test
    fun `the gdpr-in-app-cleanup flag row does not survive the migrations`() {
        val rows =
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM feature_flags WHERE flag_key = 'gdpr-in-app-cleanup'",
                Int::class.java,
            )

        assertThat(rows).isZero()
    }

    @Test
    fun `the other feature flags are untouched`() {
        val remaining =
            jdbcTemplate.queryForList(
                "SELECT flag_key FROM feature_flags ORDER BY flag_key",
                String::class.java,
            )

        assertThat(remaining).isNotEmpty().doesNotContain("gdpr-in-app-cleanup")
    }

    @Test
    fun `the retention job is not registered under the test profile`() {
        assertThat(context.getBeanNamesForType(GdprDonorCleanup::class.java)).isEmpty()
    }
}
