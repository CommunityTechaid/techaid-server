# Dependency Upgrade and Security Fixes - November 2025

## Overview
This upgrade brings the techaid-server codebase up to the latest stable versions of all major dependencies, patches critical security vulnerabilities, and removes unused dependencies.

## Version Upgrades

### Core Framework Versions
| Component | Old Version | New Version | Notes |
|-----------|-------------|-------------|-------|
| **Gradle** | 8.6 | 9.2.1 | Latest stable, requires Java 17+ |
| **Kotlin** | 1.9.22 | 2.2.21 | Latest stable release |
| **Spring Boot** | 3.2.3 | 3.5.7 | Latest stable 3.x release |
| **Spring Dependency Management** | 1.1.4 | 1.1.7 | Updated for compatibility |
| **Netflix DGS Codegen** | 6.0.3 | 6.3.1 | GraphQL code generation |

### Critical Security Updates
| Dependency | Old Version | New Version | Security Issues Fixed |
|------------|-------------|-------------|----------------------|
| **PostgreSQL JDBC** | 42.2.10 | 42.7.5 | CVE-2024-1597 (SQL injection), XXE vulnerability, CVE-2022-41946 (insecure temp files), arbitrary file write |
| **Auth0 Java** | 1.15.0 | 2.26.0 | General security improvements |
| **Auth0 JWT** | 3.10.3 | 4.5.0 | JWT handling improvements |
| **Flyway Core** | 9.22.3 | 11.17.0 | Added flyway-database-postgresql |
| **Hibernate Types** | 52:2.9.8 | 60:2.21.1 | Updated for Hibernate 6 compatibility |
| **QueryDSL JPA** | 5.0.0:jakarta | 5.1.0:jakarta | Latest stable with Jakarta support |

### Library Updates
| Dependency | Old Version | New Version |
|------------|-------------|-------------|
| **Google API Client** | 1.35.0 | 2.8.1 |
| **Google API Client Gson** | 2.3.0 | 2.8.0 |
| **Google Gmail API** | v1-rev20211108-1.32.1 | v1-rev20241029-2.0.0 |
| **kotlin-logging** | io.github.microutils:1.6.26 | io.github.oshai:7.0.3 |
| **Logstash Logback Encoder** | 6.3 | 8.0 |
| **Logback Classic** | 1.4.14 | 1.5.17 |
| **Picocli Spring Boot Starter** | 4.0.2 | 4.7.6 |
| **H2 Database** | 1.4.200 | 2.3.232 |
| **Embedded Database Spring Test** | 1.5.3 | 2.6.1 |

## Dependencies Removed

The following unused dependencies were removed to reduce attack surface and build size:

1. **com.auth0:mvc-auth-commons:1.+** - Not imported anywhere in the codebase
2. **org.springframework.boot:spring-boot-starter-thymeleaf** - No HTML templates exist
3. **org.thymeleaf.extras:thymeleaf-extras-springsecurity5** - Unused
4. **org.webjars:bootstrap:4.2.1** - Unused WebJar
5. **org.webjars:font-awesome:4.7.0** - Unused WebJar

## Code Changes

### Removed Dead Code
- **LoginController.kt** - Removed as no login template exists and functionality is unused

### Security Configuration Updates (SecurityConfig.kt)
1. **Enabled HSTS** - HTTP Strict Transport Security now properly configured:
   ```kotlin
   headers.httpStrictTransportSecurity { hsts ->
       hsts.includeSubDomains(true)
           .maxAgeInSeconds(31536000) // 1 year
   }
   ```
2. **Updated Deprecated APIs** - Migrated from deprecated methods to modern lambda-based configuration:
   - `http.csrf().disable()` → `http.csrf { it.disable() }`
   - `http.oauth2ResourceServer().jwt()` → Modern lambda syntax
   - `http.authorizeRequests()` → `http.authorizeHttpRequests { }`
3. **Removed Dead Form Login** - Removed unused form login configuration as there's no login page

### Kotlin Logging Migration
The kotlin-logging library changed group ID and package name in version 5+. All imports updated:
- **Old**: `import mu.KotlinLogging`
- **New**: `import io.github.oshai.kotlinlogging.KotlinLogging`

**Files Updated:**
- SecurityConfig.kt
- TypeformWebhookController.kt
- CommandRunner.kt
- DumpSchema.kt
- cors.kt
- SecretAuthentication.kt
- LocationService.kt
- MailService.kt
- TypeformService.kt
- DeclineIncompleteDeviceRequests.kt

## Security Vulnerabilities Patched

### Critical (CVE)
1. **CVE-2024-1597** - PostgreSQL JDBC SQL injection vulnerability
2. **XXE Vulnerability** - PostgreSQL JDBC XML External Entity vulnerability (fixed in 42.2.13+)
3. **CVE-2022-41946** - Insecure temporary file creation in PostgreSQL JDBC
4. **ResultSet.refreshRow() SQL Injection** - PostgreSQL JDBC injection vulnerability
5. **Arbitrary File Write** - PostgreSQL JDBC logger properties vulnerability

### Configuration Issues Fixed
1. **HSTS Disabled** - Now properly enabled with 1-year max age
2. **Deprecated Security APIs** - Updated to modern Spring Security 6.x APIs

## Breaking Changes & Migration Notes

### Kotlin Logging
If you have any custom scripts or documentation referencing `mu.KotlinLogging`, update to `io.github.oshai.kotlinlogging.KotlinLogging`.

### Flyway
Flyway 11.x requires PostgreSQL-specific dialect. Added `org.flywaydb:flyway-database-postgresql:11.17.0` dependency.

### Gradle 9.x
- Requires Java 17+ (already in use)
- Uses Kotlin 2 and Groovy 4
- Adopts Semantic Versioning (SemVer)

### Hibernate Types
Changed from `hibernate-types-52` to `hibernate-types-60` for Hibernate 6 compatibility (aligned with Spring Boot 3.5.x).

## Testing Recommendations

Before deploying to production, test the following areas:

1. **Database Migrations** - Verify Flyway migrations run correctly with version 11.17.0
2. **Authentication** - Test OAuth2/JWT authentication flows with updated Auth0 libraries
3. **GraphQL API** - Verify all queries and mutations work with updated dependencies
4. **Email Service** - Test Gmail integration with updated Google API client
5. **TypeForm Webhooks** - Verify webhook signature validation still works
6. **Database Operations** - Test QueryDSL queries and Hibernate operations
7. **Security Headers** - Verify HSTS headers are present in responses

## Build Configuration

### Gradle Wrapper
Updated `gradle/wrapper/gradle-wrapper.properties`:
```properties
distributionUrl=https\://services.gradle.org/distributions/gradle-9.2.1-bin.zip
```

### Build Script
Updated `build.gradle`:
- Kotlin version: 2.2.21
- Spring Boot version: 3.5.7
- All dependency versions as listed above

## Compatibility

- **Java**: 17+ (unchanged)
- **PostgreSQL**: Compatible with all versions supported by JDBC 42.7.5
- **Docker**: Compatible with existing Dockerfile configuration
- **Kubernetes**: No changes to deployment manifests required

## Next Steps

1. Run full test suite: `./gradlew test`
2. Verify local build: `./gradlew build`
3. Test with Docker Compose: `docker-compose up`
4. Review and merge to staging environment
5. Conduct integration testing
6. Deploy to production with monitoring

## References

- [Gradle 9.2.1 Release Notes](https://docs.gradle.org/current/release-notes.html)
- [Kotlin 2.2.21 Release Notes](https://kotlinlang.org/docs/releases.html)
- [Spring Boot 3.5.7 Release Notes](https://github.com/spring-projects/spring-boot/releases)
- [PostgreSQL JDBC Security Advisories](https://jdbc.postgresql.org/security/)
- [Auth0 Java SDK Documentation](https://github.com/auth0/auth0-java)
