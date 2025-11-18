package cta.graphql

import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.graphql.GraphQlTest
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.GraphQlTester
import org.springframework.test.context.junit.jupiter.SpringExtension

/**
 * Base class for GraphQL integration tests.
 * Provides full Spring context with embedded database.
 */
@ExtendWith(SpringExtension::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureEmbeddedDatabase
abstract class GraphQLIntegrationTestBase {

    @Autowired
    protected lateinit var graphQlTester: GraphQlTester
}

/**
 * Base class for GraphQL controller unit tests.
 * Uses @GraphQlTest for faster, more focused testing of individual controllers.
 */
@GraphQlTest
abstract class GraphQLControllerTestBase {

    @Autowired
    protected lateinit var graphQlTester: GraphQlTester
}
