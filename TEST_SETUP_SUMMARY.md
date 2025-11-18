# GraphQL API Test Coverage Setup - Summary

## ✅ What's Been Created

I've set up a complete testing infrastructure for your GraphQL APIs. Here's everything that's been added:

### 📁 New Files Created

#### Test Infrastructure
1. **`src/test/kotlin/cta/graphql/GraphQLTestBase.kt`**
   - Base classes for integration and unit tests
   - Provides `GraphQLIntegrationTestBase` with full Spring context
   - Provides `GraphQLControllerTestBase` for focused controller tests

2. **`src/test/kotlin/cta/fixtures/TestDataFactory.kt`**
   - Factory methods for creating test data
   - Supports: Donor, Kit, DeviceRequest, ReferringOrganisation
   - Consistent test data across all tests

#### Example Tests
3. **`src/test/kotlin/cta/graphql/queries/KitQueriesTest.kt`**
   - Unit tests for Kit query resolvers
   - Shows how to mock dependencies with MockK
   - Tests: `statusCount`, `typeCount`, `kit` queries

4. **`src/test/kotlin/cta/graphql/mutations/KitMutationsTest.kt`**
   - Unit tests for Kit mutation resolvers
   - Tests: `createKit`, `quickCreateKit` mutations
   - Demonstrates testing with mocked services

5. **`src/test/kotlin/cta/graphql/integration/KitGraphQLIntegrationTest.kt`**
   - Full integration tests with real GraphQL execution
   - Tests: Pagination, filtering, counts
   - Uses embedded PostgreSQL database

6. **`src/test/kotlin/cta/graphql/integration/SecurityGraphQLTest.kt`**
   - Security and authorization tests
   - Verifies `@PreAuthorize` annotations
   - Tests: Authentication, permissions, admin access

#### Test Resources
7. **`src/test/resources/application-test.yml`**
   - Test-specific configuration
   - Disables external services (email, geocoding)
   - Configures embedded database and logging

8. **`src/test/resources/graphql-test/kitsConnection.graphql`**
   - Example external GraphQL query file
   - Can be referenced by name in tests

9. **`src/test/resources/graphql-test/createKit.graphql`**
   - Example external GraphQL mutation file
   - Demonstrates mutation testing

#### Documentation
10. **`TESTING_GUIDE.md`**
    - Comprehensive 300+ line testing guide
    - Test strategies, patterns, and best practices
    - Running tests, debugging, CI/CD integration
    - Step-by-step examples

11. **`TEST_SETUP_SUMMARY.md`** (this file)
    - Quick reference for what's been set up

### 🔧 Modified Files

#### `build.gradle`
Added MockK testing dependencies:
```gradle
testImplementation 'io.mockk:mockk:1.13.14'
testImplementation 'com.ninja-squad:springmockk:4.0.2'
```

## 🎯 Test Coverage Summary

### Current Coverage
- **Before**: 1 smoke test (context loading)
- **After**: 5 comprehensive example test classes covering:
  - Unit tests (queries and mutations)
  - Integration tests (full GraphQL execution)
  - Security tests (authorization)

### Test Types Demonstrated

1. **Unit Tests**
   - Mock all dependencies
   - Test individual methods
   - Fast execution (milliseconds)
   - Example: `KitQueriesTest.kt`

2. **Integration Tests**
   - Real Spring context
   - Real database (embedded PostgreSQL)
   - Full GraphQL request/response cycle
   - Example: `KitGraphQLIntegrationTest.kt`

3. **Security Tests**
   - `@WithMockUser` annotation
   - Test different authorities
   - Verify access control
   - Example: `SecurityGraphQLTest.kt`

## 🚀 Quick Start

### 1. Run Example Tests

```bash
# Run all tests
./gradlew test

# Run specific test class
./gradlew test --tests KitQueriesTest

# Run integration tests only
./gradlew test --tests '*IntegrationTest'

# Run with coverage report
./gradlew test jacocoTestReport
```

### 2. View Test Results

```bash
# Test results
open build/reports/tests/test/index.html

# Coverage report
open build/reports/jacoco/test/html/index.html
```

### 3. Write Your First Test

**Example: Test Donor Queries**

```kotlin
// src/test/kotlin/cta/graphql/queries/DonorQueriesTest.kt
package cta.graphql.queries

import cta.app.DonorRepository
import cta.app.graphql.queries.DonorQueries
import cta.fixtures.TestDataFactory
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class DonorQueriesTest {
    private lateinit var donorRepository: DonorRepository
    private lateinit var donorQueries: DonorQueries

    @BeforeEach
    fun setup() {
        donorRepository = mockk()
        donorQueries = DonorQueries(donorRepository, mockk())
    }

    @Test
    fun `donor query finds donor by id`() {
        // Given
        val donor = TestDataFactory.createDonor(id = 1L)
        every { donorRepository.findOne(any()) } returns Optional.of(donor)

        // When
        val result = donorQueries.donor(mockk(relaxed = true))

        // Then
        assertEquals(donor, result.get())
    }
}
```

## 📊 Testing Strategy

### Recommended Approach

**Phase 1: High-Value Tests** (Start here!)
1. Integration tests for most-used GraphQL queries
2. Security tests to verify authorization
3. Mutation tests for data-modifying operations

**Phase 2: Comprehensive Coverage**
1. Unit tests for all query resolvers
2. Unit tests for all mutation resolvers
3. Service layer tests

**Phase 3: Edge Cases**
1. Error handling tests
2. Validation tests
3. Complex filtering and pagination tests

### Coverage Goals

| Component | Target | Priority |
|-----------|--------|----------|
| Kit APIs | 80%+ | HIGH |
| Donor APIs | 80%+ | HIGH |
| DeviceRequest APIs | 80%+ | HIGH |
| Security/Auth | 90%+ | CRITICAL |
| Services | 70%+ | MEDIUM |
| Repositories | 60%+ | LOW |

## 🔍 Key Testing Patterns

### Pattern 1: Unit Test with MockK

```kotlin
@Test
fun `test method name`() {
    // Given - Setup mocks
    every { repository.findById(1L) } returns Optional.of(entity)

    // When - Execute
    val result = service.getEntity(1L)

    // Then - Verify
    assertEquals(entity, result)
    verify { repository.findById(1L) }
}
```

### Pattern 2: GraphQL Integration Test

```kotlin
@Test
@WithMockUser(authorities = ["read:kits"])
fun `test query name`() {
    // Given - Insert test data
    repository.save(TestDataFactory.createKit())

    // When & Then - Execute GraphQL
    graphQlTester
        .document("query { kits { id } }")
        .execute()
        .path("kits")
        .entityList(Object::class.java)
        .hasSize(1)
}
```

### Pattern 3: Security Test

```kotlin
@Test
@WithMockUser(authorities = ["wrong:permission"])
fun `unauthorized access is rejected`() {
    graphQlTester
        .document("query { adminConfig { id } }")
        .execute()
        .errors()
        .satisfy { errors -> assert(errors.isNotEmpty()) }
}
```

## 🛠️ Tools & Libraries

### Testing Stack
- **JUnit 5** - Test framework
- **MockK** - Kotlin-friendly mocking
- **Spring GraphQL Test** - GraphQL testing support
- **Spring Security Test** - `@WithMockUser` annotation
- **Embedded PostgreSQL** - Real database for integration tests
- **JaCoCo** - Code coverage reporting

### Available Annotations
- `@Test` - Mark test methods
- `@BeforeEach` / `@AfterEach` - Setup/teardown
- `@DisplayName` - Readable test names
- `@WithMockUser` - Mock authentication
- `@ParameterizedTest` - Data-driven tests
- `@Disabled` - Skip tests (use sparingly)

## 📚 Further Reading

1. **TESTING_GUIDE.md** - Comprehensive guide (read this!)
2. [Spring GraphQL Testing](https://docs.spring.io/spring-graphql/docs/current/reference/html/#testing)
3. [MockK Documentation](https://mockk.io/)
4. [JUnit 5 Guide](https://junit.org/junit5/docs/current/user-guide/)

## 🎓 Learning Path

### Beginner
1. Read `TESTING_GUIDE.md` sections on Unit Tests and Integration Tests
2. Study the example tests in `src/test/kotlin/cta/graphql/`
3. Run existing tests: `./gradlew test`
4. Modify one example test to understand the structure

### Intermediate
1. Write your first unit test for a query resolver
2. Write an integration test for a GraphQL mutation
3. Add security tests for a protected endpoint
4. Aim for 50% code coverage

### Advanced
1. Test complex filtering and pagination logic
2. Add parameterized tests for multiple scenarios
3. Test error handling and validation
4. Achieve 80%+ code coverage
5. Set up continuous testing in CI/CD

## 🚨 Common Pitfalls

### ❌ Don't Do This
```kotlin
// DON'T: Test Spring framework code
@Test
fun `Spring Boot context loads`() {
    assertNotNull(applicationContext)
}

// DON'T: Share state between tests
class BadTest {
    val sharedKit = Kit() // BAD!
}

// DON'T: Ignore test cleanup
@Test
fun test() {
    repository.save(kit)
    // Missing: @AfterEach cleanup
}
```

### ✅ Do This Instead
```kotlin
// DO: Test your business logic
@Test
fun `createKit saves kit with correct status`() {
    // Test actual application behavior
}

// DO: Create fresh data per test
@BeforeEach
fun setup() {
    val kit = TestDataFactory.createKit()
}

// DO: Clean up after tests
@AfterEach
fun cleanup() {
    repository.deleteAll()
}
```

## 📈 Next Steps

### Immediate Actions
1. ✅ Run example tests: `./gradlew test`
2. ✅ Review test examples in IDE
3. ✅ Read `TESTING_GUIDE.md`

### This Week
1. Write tests for Donor queries
2. Write tests for DeviceRequest mutations
3. Add security tests for admin endpoints
4. Aim for 30% code coverage

### This Month
1. Test all GraphQL queries and mutations
2. Test service layer methods
3. Test repository custom queries
4. Achieve 60% code coverage

### Long Term
1. Maintain 70%+ code coverage
2. Add tests for new features before coding
3. Run tests in CI/CD pipeline
4. Regular test maintenance and refactoring

## 🎉 Success Metrics

You'll know the testing infrastructure is working when:

✅ All example tests pass: `./gradlew test`
✅ Coverage report generates: `./gradlew jacocoTestReport`
✅ You can write a new test in under 10 minutes
✅ Tests catch bugs before they reach production
✅ Code coverage steadily increases
✅ Team feels confident refactoring code

---

**Need Help?**
- Check `TESTING_GUIDE.md` for detailed examples
- Review existing tests in `src/test/kotlin/cta/graphql/`
- Run tests frequently to catch issues early

**Happy Testing! 🧪**
