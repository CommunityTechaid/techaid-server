package cta.db

import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.security.oauth2.jwt.JwtDecoder

/**
 * Proves the Flyway migrations alone build a schema that satisfies every JPA mapping.
 *
 * Boots against a fresh embedded Postgres containing only what Flyway created, with
 * Hibernate in validate mode. If an entity needs a table or column no migration provides,
 * EntityManagerFactory creation (forced below despite spring.main.lazy-initialization)
 * fails and this test goes red — the fix is a new migration, not ddl-auto=update.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["spring.jpa.hibernate.ddl-auto=validate"],
)
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class SchemaValidationTest {
    @MockBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var entityManagerFactory: EntityManagerFactory

    @Test
    fun `flyway migrations build a schema that passes hibernate validation`() {
        assertThat(entityManagerFactory.metamodel.entities).isNotEmpty
    }
}
