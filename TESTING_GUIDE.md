# GraphQL API Testing Guide

This guide explains how to add comprehensive test coverage for the TechAid Server GraphQL APIs.

## 📊 Current State

- **1 smoke test** (context loading)
- **30+ GraphQL resolvers** (untested)
- **Full test infrastructure** configured and ready
- **JaCoCo** code coverage with 50% minimum requirement

## 🎯 Test Strategy

### Test Pyramid

```
           /\
          /  \         E2E Tests (Few)
         /----\        - Full system tests
        / Intg \       Integration Tests (Some)
       /--------\      - GraphQL API tests
      /   Unit   \     - Security tests
     /------------\    Unit Tests (Many)
                       - Resolver tests
                       - Service tests
                       - Repository tests
```

## 🏗️ Test Types

### 1. **Unit Tests** (Fast, Isolated)

**Purpose**: Test individual components in isolation with mocked dependencies.

**What to test**:
- Individual GraphQL query/mutation methods
- Service layer business logic
- Repository custom queries
- Utility functions

**Example**: `KitQueriesTest.kt`

```kotlin
@DisplayName("KitQueries Unit Tests")
class KitQueriesTest {
    private lateinit var kitRepository: KitRepository
    private lateinit var kitQueries: KitQueries

    @BeforeEach
    fun setup() {
        kitRepository = mockk()
        kitQueries = KitQueries(kitRepository, mockk())
    }

    @Test
    fun `statusCount returns kit counts`() {
        // Given
        every { kitRepository.statusCount() } returns listOf(...)

        // When
        val result = kitQueries.statusCount()

        // Then
        assertEquals(expectedCounts, result)
    }
}
```

### 2. **Integration Tests** (Real GraphQL execution)

**Purpose**: Test the full GraphQL request/response cycle with real Spring context and database.

**What to test**:
- Complete GraphQL queries and mutations
- Data persistence and retrieval
- Filter and pagination logic
- Error handling

**Example**: `KitGraphQLIntegrationTest.kt`

```kotlin
@DisplayName("Kit GraphQL Integration Tests")
class KitGraphQLIntegrationTest : GraphQLIntegrationTestBase() {

    @Test
    @WithMockUser(authorities = ["read:kits"])
    fun `kitsConnection query returns paginated results`() {
        // Given - Insert test data
        kitRepository.save(TestDataFactory.createKit())

        // When & Then - Execute GraphQL query
        graphQlTester
            .document("""
                query {
                    kitsConnection(page: {page: 0, size: 10}) {
                        content { id model }
                        totalElements
                    }
                }
            """)
            .execute()
            .path("kitsConnection.totalElements")
            .entity(Int::class.java)
            .isEqualTo(1)
    }
}
```

### 3. **Security Tests** (Authorization)

**Purpose**: Verify `@PreAuthorize` annotations enforce correct permissions.

**What to test**:
- Unauthenticated requests are rejected
- Wrong permissions are rejected
- Correct permissions allow access
- Admin-only endpoints are protected

**Example**: `SecurityGraphQLTest.kt`

```kotlin
@Test
@WithMockUser(authorities = ["wrong:permission"])
fun `query with wrong authority is rejected`() {
    graphQlTester
        .document("query { statusCount { status count } }")
        .execute()
        .errors()
        .satisfy { errors ->
            assert(errors.isNotEmpty())
        }
}
```

## 📁 Project Structure

```
src/
├── main/kotlin/cta/
│   ├── app/
│   │   ├── graphql/
│   │   │   ├── queries/      # GraphQL query resolvers
│   │   │   └── mutations/    # GraphQL mutation resolvers
│   │   └── services/         # Business logic
│   └── auth/                 # Authentication & authorization
│
└── test/kotlin/cta/
    ├── graphql/
    │   ├── GraphQLTestBase.kt           # Base test classes
    │   ├── queries/
    │   │   ├── KitQueriesTest.kt        # Unit tests
    │   │   ├── DonorQueriesTest.kt
    │   │   └── DeviceRequestQueriesTest.kt
    │   ├── mutations/
    │   │   ├── KitMutationsTest.kt      # Unit tests
    │   │   └── DonorMutationsTest.kt
    │   └── integration/
    │       ├── KitGraphQLIntegrationTest.kt    # Integration tests
    │       └── SecurityGraphQLTest.kt          # Security tests
    ├── services/
    │   ├── KitServiceTest.kt            # Service unit tests
    │   └── MailServiceTest.kt
    ├── repositories/
    │   └── KitRepositoryTest.kt         # Repository tests
    └── fixtures/
        └── TestDataFactory.kt            # Test data builders
```

## 🔧 Testing Tools

### Dependencies (Already Configured)

```gradle
// JUnit 5
testImplementation 'org.junit.jupiter:junit-jupiter-api'
testRuntimeOnly 'org.junit.jupiter:junit-jupiter-engine'

// MockK for Kotlin mocking
testImplementation 'io.mockk:mockk:1.13.14'
testImplementation 'com.ninja-squad:springmockk:4.0.2'

// Spring Boot Test
testImplementation 'org.springframework.boot:spring-boot-starter-test'
testImplementation 'org.springframework.security:spring-security-test'

// Embedded Database
testImplementation 'io.zonky.test:embedded-database-spring-test:2.6.1'
testImplementation 'com.h2database:h2:2.3.232'
```

### Key Testing Utilities

1. **GraphQlTester** - Spring's GraphQL testing framework
2. **MockK** - Kotlin-friendly mocking library
3. **@WithMockUser** - Security testing annotation
4. **@AutoConfigureEmbeddedDatabase** - Embedded PostgreSQL for tests

## 📝 Writing Tests

### Step 1: Create Test Data Factory

Use `TestDataFactory` to create consistent test data:

```kotlin
val donor = TestDataFactory.createDonor(
    email = "test@example.com",
    name = "Test Donor"
)

val kit = TestDataFactory.createKit(
    model = "Dell Latitude",
    type = KitType.LAPTOP,
    donor = donor
)
```

### Step 2: Write Unit Tests

**Mock dependencies** and test individual methods:

```kotlin
class KitQueriesTest {
    private lateinit var kitRepository: KitRepository
    private lateinit var kitQueries: KitQueries

    @BeforeEach
    fun setup() {
        kitRepository = mockk()
        kitQueries = KitQueries(kitRepository, mockk())
    }

    @Test
    fun `test method behavior`() {
        // Given - Setup mocks
        every { kitRepository.statusCount() } returns listOf(...)

        // When - Execute method
        val result = kitQueries.statusCount()

        // Then - Verify results
        assertEquals(expected, result)
        verify { kitRepository.statusCount() }
    }
}
```

### Step 3: Write Integration Tests

**Test full GraphQL queries** with real database:

```kotlin
class KitGraphQLIntegrationTest : GraphQLIntegrationTestBase() {

    @Autowired
    private lateinit var kitRepository: KitRepository

    @BeforeEach
    fun setup() {
        kitRepository.deleteAll()
    }

    @Test
    @WithMockUser(authorities = ["read:kits"])
    fun `test GraphQL query`() {
        // Given - Insert test data
        kitRepository.save(TestDataFactory.createKit())

        // When & Then - Execute GraphQL
        graphQlTester
            .document("""
                query {
                    kits(where: {}) {
                        id
                        model
                    }
                }
            """)
            .execute()
            .path("kits")
            .entityList(Object::class.java)
            .hasSize(1)
    }
}
```

### Step 4: Write Security Tests

**Verify authorization** works correctly:

```kotlin
@Test
@WithMockUser(authorities = ["read:kits"])
fun `correct authority allows access`() {
    graphQlTester
        .document("query { statusCount { status count } }")
        .execute()
        .errors()
        .verify() // No errors
}

@Test
fun `no auth rejects request`() {
    graphQlTester
        .document("query { statusCount { status count } }")
        .execute()
        .errors()
        .satisfy { errors -> assert(errors.isNotEmpty()) }
}
```

## 🎨 GraphQL Query Documents

### Option 1: Inline Queries

```kotlin
graphQlTester.document("""
    query {
        kits(where: {}) {
            id
            model
        }
    }
""")
```

### Option 2: External Files (Recommended for complex queries)

Create: `src/test/resources/graphql-test/kitsConnection.graphql`

```graphql
query KitsConnection($page: PaginationInput, $where: KitWhereInput) {
    kitsConnection(page: $page, where: $where) {
        content {
            id
            model
            type
            status
        }
        totalElements
        totalPages
    }
}
```

Use in test:

```kotlin
graphQlTester
    .documentName("kitsConnection")
    .variable("page", mapOf("page" to 0, "size" to 10))
    .execute()
```

## 🔍 Testing Patterns

### Pattern 1: Arrange-Act-Assert (AAA)

```kotlin
@Test
fun `test description`() {
    // Arrange (Given) - Setup test data
    val kit = TestDataFactory.createKit()
    every { repository.findById(1L) } returns Optional.of(kit)

    // Act (When) - Execute the code under test
    val result = service.getKit(1L)

    // Assert (Then) - Verify the results
    assertEquals(kit, result)
    verify { repository.findById(1L) }
}
```

### Pattern 2: Test Data Builders

```kotlin
object TestDataFactory {
    fun createKit(
        id: Long = 1L,
        model: String = "Default Model",
        // ... other parameters with defaults
    ): Kit {
        return Kit().apply {
            this.id = id
            this.model = model
            // ...
        }
    }
}
```

### Pattern 3: Parameterized Tests

```kotlin
@ParameterizedTest
@EnumSource(KitType::class)
fun `test all kit types`(kitType: KitType) {
    val kit = TestDataFactory.createKit(type = kitType)
    // Test with each kit type
}
```

## 🚀 Running Tests

### Run All Tests

```bash
./gradlew test
```

### Run Specific Test Class

```bash
./gradlew test --tests KitQueriesTest
```

### Run Tests with Coverage Report

```bash
./gradlew test jacocoTestReport
```

Report location: `build/reports/jacoco/test/html/index.html`

### Run Only Integration Tests

```bash
./gradlew test --tests '*IntegrationTest'
```

### Run in Watch Mode

```bash
./gradlew test --continuous
```

## 📈 Code Coverage Goals

Current JaCoCo configuration requires **50% minimum coverage**.

### Coverage Targets

| Layer | Target Coverage |
|-------|----------------|
| GraphQL Queries | 80%+ |
| GraphQL Mutations | 80%+ |
| Services | 70%+ |
| Repositories | 60%+ |
| Controllers | 70%+ |

### Excluded from Coverage

- QueryDSL generated classes (Q* classes)
- Configuration classes
- Data classes (models)

## 🐛 Debugging Tests

### Enable Detailed Logging

`src/test/resources/application-test.yml`:

```yaml
logging:
  level:
    org.springframework.graphql: DEBUG
    cta: DEBUG
```

### Print GraphQL Errors

```kotlin
graphQlTester
    .document("query { ... }")
    .execute()
    .errors()
    .satisfy { errors ->
        errors.forEach { println(it) }
    }
```

### Use TestContainers for Real PostgreSQL

```kotlin
@Testcontainers
class RealDatabaseTest {
    @Container
    val postgres = PostgreSQLContainer("postgres:16")
        .withDatabaseName("testdb")
}
```

## ✅ Best Practices

### DO

✅ Use descriptive test names with backticks
✅ Follow AAA pattern (Arrange-Act-Assert)
✅ Clean up test data in `@AfterEach`
✅ Use `@DisplayName` for better test reports
✅ Mock external dependencies (email, geocoding)
✅ Test both success and failure cases
✅ Verify security annotations with `@WithMockUser`
✅ Use `TestDataFactory` for consistent test data

### DON'T

❌ Don't test framework code (Spring, Hibernate)
❌ Don't share state between tests
❌ Don't use Thread.sleep() - use proper synchronization
❌ Don't test implementation details
❌ Don't ignore flaky tests - fix them
❌ Don't commit commented-out tests

## 📚 Resources

- [Spring GraphQL Testing Docs](https://docs.spring.io/spring-graphql/docs/current/reference/html/#testing)
- [MockK Documentation](https://mockk.io/)
- [JUnit 5 User Guide](https://junit.org/junit5/docs/current/user-guide/)
- [AssertJ Documentation](https://assertj.github.io/doc/)

## 🎯 Recommended Testing Priority

### Phase 1: Foundation (Week 1)
1. ✅ Setup test infrastructure (DONE)
2. Create test data factories
3. Write 2-3 example tests per layer

### Phase 2: Core Coverage (Week 2-3)
1. Test all Kit queries and mutations (highest usage)
2. Test Donor queries and mutations
3. Test DeviceRequest queries and mutations

### Phase 3: Comprehensive Coverage (Week 4-5)
1. Test remaining GraphQL resolvers
2. Test service layer methods
3. Test repository custom queries

### Phase 4: Security & Edge Cases (Week 6)
1. Comprehensive security tests
2. Error handling tests
3. Edge case and validation tests

## 🔄 CI/CD Integration

Add to `.circleci/config.yml`:

```yaml
- run:
    name: Run tests
    command: ./gradlew test

- run:
    name: Generate coverage report
    command: ./gradlew jacocoTestReport

- store_test_results:
    path: build/test-results/test

- store_artifacts:
    path: build/reports/jacoco
```

## 📞 Need Help?

- Check existing test examples in `src/test/kotlin/cta/graphql/`
- Review GraphQL schema files in `src/main/resources/graphql/`
- Consult Spring GraphQL testing documentation

---

**Happy Testing! 🎉**
