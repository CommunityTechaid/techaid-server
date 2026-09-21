# Carry-on: issue #209 dependency currency → Spring Boot 4.1.x

Rewritten 2026-09-21 (second session) to resume cold. **Delete this file when the work lands.**
Companion: `MAINTENANCE_PLAN.md` → "Tier 4 — Dependency currency, 2026-09" (the checklist).

> This rewrite exists because the first version of this note was wrong in four material ways.
> Everything below marked **VERIFIED** was read from a primary source in-session, not inferred.

## Where things are

| Thing | State |
|---|---|
| Branch | `chore/dependency-currency-209`, 12 commits, tree clean |
| Forked from | `dev` @ `1726099` (unmoved) |
| PR | **none opened yet** — a *draft* PR against `dev` is the end state |
| `master` / production / UAT | untouched. Prod is server 3.3.0 / `c605bf0` |
| Feature flags | untouched |
| Dependabot PRs | #212–#221 all still open; none merged or closed |
| release-please PR #205 | untouched |
| Suite | `314 passed / 0 failed / 2 skipped`, green at every commit |

**Hard constraint from Tony: nothing merges to `dev` yet.** A `dev` merge auto-deploys UAT and he
wants to soak this later in the week.

## The target

**Spring Boot 4.1.x. The 3.5 line is deliberately skipped.** Dependabot **#216 is to be closed, not
merged.** CVE-2026-40973 does not force a move: its precondition is inactive
(`server.servlet.session.persistent` unset everywhere, defaults false).

**Java stays at 17** — Boot 4.1 supports 17–26. No JDK move anywhere in the plan.

### VERIFIED — the Boot 4.1.1 BOM

Read directly from `spring-boot-dependencies-4.1.1.pom` on Maven Central. **Trust this table over
anything issue #209 or an older note says.**

| Managed property | Version | Note |
|---|---|---|
| `spring-framework.version` | 7.0.9 | |
| `spring-security.version` | **7.1.1** | **not 7.0.x** — see the research gap below |
| `spring-graphql.version` | **2.0.5** | **a major jump from 1.3.4**, not 1.3 → 1.5 |
| `graphql-java.version` | 25.0 | from 22.3 |
| `jackson-bom.version` | **3.1.5** | **Jackson 3** — a blocker the first note never mentioned |
| `kotlin.version` | 2.3.21 | floor for Boot 4.1 is 2.2 |
| `querydsl.version` | 5.1.0 | |
| `hibernate.version` | 7.4.5.Final | |
| `flyway.version` | 12.4.0 | via the new `spring-boot-starter-flyway` |
| `jakarta-persistence.version` | 3.2.0 | |
| `graphql-java-extended-scalars` | **NOT MANAGED** | grepped the POM; absent. See the trap below |

## Done (all on the branch)

| SHA | What |
|---|---|
| `3a37ffa` | `.github/dependabot.yml`: `semver-major` ignores added to the **docker** and **github-actions** blocks |
| `052f570` | `MAINTENANCE_PLAN.md` Tier 4 plan |
| `bf8c454` | App Insights agent `3.7.8` → `3.7.10` (Dockerfile ARG) |
| `9aa73bc` | ktlint gradle plugin `12.1.1` → `14.2.0` (engine pin stays 1.5.0) |
| `1a1ae6f` | plan retargeted to 4.1.x |
| `6cad5cf` | Postgres driver `42.7.13` — buildscript pin **and** `ext['postgresql.version']` |
| `3d53ab2` | the first version of this note |
| `fff0d49` | **Phase A1** zonky `embedded-database-spring-test` 2.5.1 → 2.8.0, `embedded-postgres` 2.1.0 → 2.2.2, h2 2.2.224 → 2.5.250. Folds Dependabot #218 |
| `4f1936d` | **Phase A2** `@MockBean` → `@MockitoBean`, **47 sites / 45 files** (not 48 — one match was a comment) |
| `953d03f` | **Phase A5** deleted `cta/commands` entirely + picocli + both `hibernate-tools` pins |
| `87789e5` | this note, rewritten against verified sources |
| `fde2284` | **Phase A** Gradle wrapper **and** build image `8.12.1` → `8.14.5`. Dependabot #217 (image → 9.7.1) deliberately NOT taken |
| `14f734c` | **Phase A** Kotlin `2.1.20` → `2.2.21`, plus a new `ext['kotlin.version']` override so stdlib/reflect follow the compiler |

### Why A5 became a deletion

The old plan said rewrite `DumpSchema.kt` off `SchemaExport`. It is gone instead, and this was the
single biggest simplification available.

`DumpSchema` arrived in the **2020 initial commit**, when Hibernate owned the schema via
`ddl-auto: update` and printing entity-derived DDL was useful. Flyway reversed that direction:
migrations own the schema and `SchemaValidationTest` validates entities *against* the database.
Every touch since 2020 was migration life-support, never a functional change. The `hibernate-tools`
pins were not a feature either — added in `f80f02d` during the Gradle 8 move purely to keep
`SchemaExport` resolving once it stopped arriving from `hibernate-core`.

**VERIFIED never invoked**, five ways: `schema:dump` appears nowhere outside its own definition;
the `console` profile is activated in no yml, `.env.sample`, compose file, chart, manifest,
workflow or Dockerfile; `api-production` runs `SPRING_PROFILES_ACTIVE=production` and `api-testing`
runs `testing`, neither overriding `command`/`args`, so the Dockerfile `CMD` runs with no `execute`
argument (the only thing `CommandRunner` acts on); every bean was `@Profile("console")`; no test
touched it.

**VERIFIED it was also a hard blocker**: `hibernate-tools-orm` and `-utils` have **no 7.4.x
release** — Central stops at `7.3.13.Final` and jumps to `8.0.0.Alpha1` — so pinning them to the
BOM `hibernate.version` on Boot 4.1 would have failed dependency resolution outright.

## Next steps

### Phase A — front-loadable onto Boot 3.4.4, one green commit each

1. **Delete `com.github.alexliesenfeld:querydsl-jpa-postgres-json:0.0.7`** — proven dead code,
   see issue #222 below. Two imports plus two dead method bodies.
2. **Pin `graphql-java-extended-scalars` explicitly** — see the trap below. Do this **before**
   anything touches the DGS plugin.
3. **Flyway 10.22.0 → 11.x** as the waypoint. **NOT 12.x — see below.**

> **Resolved, was Phase A step 6.** `io.github.microutils:kotlin-logging-jvm:3.0.5` was expected to
> break on Kotlin 2.2 (Kotlin 1.x metadata; the coordinate has moved to `io.github.oshai`). It
> compiles clean on 2.2.21 with **zero** metadata warnings, so no move is needed now. It may still
> break at 2.3.21 during the Boot bump — do not treat this as cleared permanently.
4. **Guard the schema-inspection config key with a test.** `application.yml:164-172` sets
   `spring.graphql.schema.inspection.enabled: false`, and it is **load-bearing** —
   SchemaMappingInspector breaks on Kotlin 2.x reflection, and Kotlin is already at 2.2.21 (`14f734c`).
   `GraphQlIntrospectionDisabledTest` would catch a dead *introspection* key, but nothing catches a
   renamed or dropped **`inspection`** key: it would silently start running the inspector again.
   spring-graphql 2.0 also **extends** the inspector to nullability checks, so an accidental
   re-enable would be loud in a new way. Write the test now, on Boot 3.4.4, pinning the current
   behaviour so a Boot 4 rename fails the suite instead of surfacing at startup.
5. **Consolidate the empty `type Mutation { }`** at `root.graphqls:109-110`. The GraphQL spec's
   `FieldsDefinition` requires >= 1 field; this only parses because graphql-java v25's grammar is
   `fieldsDefinition : '{' fieldDefinition* '}'` (zero-or-more, with `+` used only for *extension*
   definitions). It rides a deliberate vendor laxity rather than the spec. Note the fix is not a
   deletion — every other `.graphqls` does `extend type Mutation`, which requires the base type to
   exist, so a real field has to move onto the root. `SchemaAssemblyTest` is the check.

### Phase B — the Boot bump

`springBootVersion` + plugin to 4.1.x; replace the `flyway-core` / `flyway-database-postgresql`
pins with **`spring-boot-starter-flyway`**; `hypersistence-utils-hibernate-63` → **`-73`**
(3.16.0; there is no `-74`); QueryDSL 5.0.0 → **5.1.0** or drop the version and let the BOM
manage it; **keep `ext['postgresql.version'] = '42.7.13'`** (the 4.x BOM is also inside the
CVE-2026-54291 range); **the Jackson 2 → 3 migration (below)**; then Jakarta EE 11 / Servlet 6.1 /
Spring Framework 7 / Spring Security 7.1 fallout, and the spring-graphql 2.0 jump.

### Phase C — after it runs

Envers `NOT_AUDITED` re-verification (below); the #222 decision; **delete the vestigial DGS codegen
plugin — but only after Phase A step 2**; `bootRun` + `/actuator/health` locally.

## The four things the first note got wrong

1. **"Go to Flyway 12.x now on Boot 3.4.4 to isolate the Flyway risk (preferred)."** Impossible.
   Boot 3.4.4's `FlywayAutoConfiguration:283-284` unconditionally calls
   `FluentConfiguration.cleanOnValidationError(boolean)`, and Flyway 12.0.0 removed that method —
   `NoSuchMethodError` at context startup. Found by diffing `FluentConfiguration.java` between the
   `flyway-11.0.0` and `flyway-12.0.0` tags; Flyway's own release notes do not call it out. Boot
   *does* guard `executeInTransaction()` with `catch (NoSuchMethodError)` at lines 330-338, and
   deliberately did not guard this one. `spring.flyway.clean-on-validation-error` being unset is no
   protection — it is a primitive `boolean`, so `PropertyMapper` always calls the setter.
   Second, independent blocker: zonky added Flyway 11 support in 2.6.0, but **no zonky release
   documents Flyway 12**, so the embedded-Postgres harness could not verify a 12.x move anyway.
   → **Take 11.x as the waypoint; 12.x arrives with the Boot bump.**
   *Unchecked:* whether a later 12.x patch (12.1–12.4) re-added the method. Does not change the
   call, because the zonky constraint is independent.
2. **Spring Security is 7.1.1, not 7.0.x.** The 7.0 migration guide is one release short, and no
   7.1 migration guide was located. Still open.
3. **spring-graphql is 1.3.4 → 2.0.5, a major**, not 1.3 → 1.5. There is no spring-graphql 1.5 at
   all; the line is 1.3 → 1.4 → 2.0 → 2.1. Per the official compatibility table, 1.4.x supports
   Boot **3.5 only**, so skipping 3.5 means 1.4 is a waypoint you never land on.
4. **Jackson was never mentioned and is a blocker.** Boot 4 auto-configures a Jackson 3 `JsonMapper`
   under `tools.jackson.*`; no Jackson 2 `ObjectMapper` bean exists. Four constructor-injection
   sites fail at context startup: `SecurityConfig.kt`, `TokenAuthenticationFilter.kt`,
   `TypeformService.kt`, `GraphQlTelemetryInterceptor.kt`. Ten files under `src/main` import
   `com.fasterxml.jackson`. The deprecated `spring-boot-jackson2` module is published through 4.1.1
   as a bridge, but auto-detection is off in Framework 7.1 and it is removed in 7.2 — a one-release
   reprieve, not a resting place.

## The extended-scalars trap — read before touching the DGS plugin

`build.gradle:198` declares `com.graphql-java:graphql-java-extended-scalars` **with no version**.
It resolves to **19.0**, and the version arrives via the *vestigial* DGS codegen plugin classpath
(`graphql-dgs-codegen-shared-core-6.0.3` → `graphql-dgs-platform-dependencies:5.5.1`).

**VERIFIED**: the Boot 4.1.1 BOM does **not** manage extended-scalars (grepped the POM — only
`graphql-java` is there). So the Boot bump will not move it, and **Phase C's "delete the vestigial
DGS plugin" removes its only version source, making the dependency unresolvable.**

Pin it explicitly to the 25.0 line first. Note that is six majors in one hop: graphql-java 22.0
made `String`/`Boolean`/`Int`/`Float` `parseValue` strict, and extended-scalars 24.0 "removes all
the deprecated Coercing methods". The `GraphQLBigDecimal` / `GraphQLLong` coercion deltas
(`GraphQlConfig.kt:48-49`, `root.graphqls:2-3`) are **undocumented** — red/green test: each fed a
JSON string vs a number, before and after.

Related runtime risk: spring-graphql issue #1405 — Boot 4 with a stale graphql-java throws
`NoSuchMethodError: ExecutionInput.cancel()` **per request at runtime**, not at compile time. This
repo has a second graphql-java on the graph (19.2, via DGS). Gradle picks highest so it *should*
resolve to 25.0, but **a green build proves nothing here** — assert the resolved version with
`dependencyInsight`.

## GraphQL: smaller than feared, verified against v25.0 sources

The things the plan called the riskiest migration are **clean**:

- `GraphQlConfig.kt:70-101,123-156` already use the current **4-arg `Coercing`** signatures. v25
  keeps the deprecated 1-arg defaults, and its nullability annotations are *wider* than the repo's
  returns, so strict-JSpecify override checking passes.
- `MaxQueryDepthInstrumentation(15)` (`GraphQlConfig.kt:39`) exists in v25.0, undeprecated, same
  constructor.
- graphql-java 23.0's strict RuntimeWiring redefinition fires only on a **duplicate** name;
  `GraphQlConfig.kt:47-51` registers 4 distinct scalars and there are zero duplicate
  `@QueryMapping`/`@MutationMapping` field names.
- **The SDL survives.** 2,065 lines across 21 `.graphqls`, zero custom directives, interfaces,
  unions, subscriptions, `@deprecated` or argument defaults.
- **Security context propagation is low risk** — by mechanism, not by release note. Propagation
  only matters across a thread switch; the GraphQL path has zero `Mono`/`Flux`/`CompletableFuture`/
  `@Async`, no webflux, and no `ThreadLocalAccessor`. The 30 `@PreAuthorize` files read the
  SecurityContext off the servlet thread as before. **This is inference** — no 2.0 note mentions
  context propagation either way. Cheap proof is behavioural: `PublicSurfaceAuthorizationTest` plus
  one authenticated mutation against the bumped branch. Do not accept a green compile as evidence.

**`SchemaAssemblyTest.kt:20-53` is the canary** — assembles every `.graphqls` with the real scalar
wiring, no Spring context, no DB. Run it first after any GraphQL bump; it catches parser and wiring
regressions in seconds.

**Behavioural change that needs a test**: spring-graphql **1.4** (inherited by 2.0) aligned with
GraphQL-over-HTTP — `application/graphql-response+json`, **the default when the client expresses no
Accept preference**, returns **4xx** for parse/validation failures. A depth-15 breach is
validation-phase, so it flips 200 → 400 for any client not sending `Accept: application/json`.
`GraphQlErrorLoggingTest.kt:66-74` pins only the `application/json` path. The calendar-sync Apps
Script `'access denied'` retry is **safe** — authz denial is an execution error, still 200.

## Spring Security / Framework 7: the config is nearly clean

Checked and **clear**, stated so nobody re-derives it: `http.csrf{}`/`formLogin{}`/
`authorizeHttpRequests{}` are already lambda-DSL (7.0 removed `.and()` and `apply(...)`);
`anyRequest().permitAll()` uses no path matcher so the `AntPathRequestMatcher` → 
`PathPatternRequestMatcher` move is a no-op; `jwtAuthenticationConverter(...)` is unchanged in the
7.0.0 javadoc; `addFilterBefore`, `GenericFilterBean`, `OncePerRequestFilter`,
`ContentCachingResponseWrapper` and `FilterRegistrationBean` are all unchanged; `@Secured` is used
nowhere despite `securedEnabled = true`; all ~60 `@PreAuthorize` expressions are trivial
`hasAnyAuthority('…')` so Framework 7's new 10,000-operation SpEL cap cannot bite; `CorsConfig.kt:38`
maps the literal `/graphql`; no `AccessDecisionManager`/`AccessDecisionVoter`.

**Behavioural, silent:**

- `SecurityConfig.kt:49-54` — `NimbusJwtDecoder` default connect/read timeouts **500ms → 30s**. On
  a scale-to-zero app a stalled Auth0 JWKS fetch now holds the request 30s instead of failing fast.
- `SecurityConfig.kt:51` — `JwtValidators.createDefaultWithIssuer(...)` now automatically adds
  `JwtTypeValidator.jwt()`; tokens whose `typ` header is not JWT are rejected where they previously
  passed. **Check the Auth0 access token and the M2M client-credentials token `typ` before
  promoting.**
- `SecurityConfig.kt:106` — `JwtGrantedAuthoritiesConverter().convert(jwt)!!` then `.add(...)`
  relies on the returned collection being mutable *and* on platform-type nullability, both now
  JSpecify-annotated. Copy into an explicit `mutableListOf(...)`.

**Also breaking:**

- `build.gradle:184` `thymeleaf-extras-springsecurity6:3.1.3.RELEASE` — **no `…security7` exists**
  on Central (listing stops at security6) and the upstream repo is archived. `templates/` contains
  only `email/` with zero `sec:` usages → **delete it**, nothing consumes the dialect.
- `build.gradle:91,98` `-Xjsr305=strict` — Framework 7 replaced JSR-305 with **JSpecify**; expect
  Kotlin compile errors at Spring API call sites.
- `spring-boot-starter-web` → renamed `spring-boot-starter-webmvc`; the old coordinate still
  publishes at 4.1.1, so this is rename-when-convenient.
- `LocationService.kt:8,32` `RestTemplate` deprecated in Framework 7 → `RestClient`. (Same class as
  the known-broken `location()` proxy.)

## Research gap — still open before Phase B

- **Spring Security 7.1** specifically. Everything above came from the **7.0** migration guide; no
  7.1 guide was located. There may be a second layer on top.
- Whether `spring-boot-jackson2` genuinely restores an **injectable bean** vs just the classes.
  Settle by reading that module's `Jackson2AutoConfiguration` source.
- Whether `spring.graphql.schema.introspection.enabled` and **`inspection.enabled`** survive Boot 4
  (`application.yml:164-172`). Not researched — **Phase A step 7 guards it with a test instead**,
  which is cheaper than settling the documentation question.
- Whether spring-graphql 2.0 changed `DataFetcherExceptionResolverAdapter`, `RuntimeWiringConfigurer`
  or `WebGraphQlInterceptor`. The 2.0 notes list **no removed APIs at all** — weak evidence, not
  strong.
- spring-graphql 1.4's "performance enhancements for Servlet-based applications" — could not
  establish whether these change the execution thread model. This is the one thing that could
  invalidate the security-propagation reasoning above.

## Two live risks

- **Envers `NOT_AUDITED` changed semantics at Hibernate 7.3** — previously ignored, now respected,
  so those associations read **current** state instead of historic audit state. **9 sites**:
  `KitModels.kt` x4 (37, 71, 75, 163), `DonorModels.kt` x2 (28, 91), `OrganisationModels.kt` x2
  (28, 91), `DeviceRequestModels.kt` x1 (99). Behavioural — it will not fail a compile. Re-baseline
  `KitAuditNoOpRevisionTest`, `EnversSchemaContractTest`, `KitAuditTrailStatusConstraintTest` and
  the audit-trail queries against real data.
- **In Boot 4, missing `spring-boot-starter-flyway` means migrations silently stop auto-running.**

## Issue #222 — a live regression found on the way

**Kit free-text search has silently not matched `attributes.notes` since 2024-11-12** (~22 months).
`KitFilters.kt:332-333` has `attributes?.let { null }` where every sibling reads
`x?.let { builder.and(...) }`, so the input is accepted and dropped. Introduced by `6c2ced4`
(Akhil) to bypass a Hibernate 6 breakage rather than fix it. The **dashboard still sends the
filter** (`kit-index.component.ts:42`, the main kit-list search box), in an `OR` with `model` /
`serialNo` / `id` — so results come back HTTP 200, just missing notes matches. No test covers the
path, which is why it survived. Restoring it needs a `FunctionContributor` or a native jsonb
predicate — a product decision, not an upgrade blocker.

## Dependabot verdicts

- **Folded:** #218 (done in `fff0d49`) — can be closed.
- **Fold in:** #219 font-awesome, #220 auth0 1.12.1, #221 jakarta.mail 2.0.2.
- **Close, do not merge:** #216 (3.4.4 → 3.5.16).
- **Now required:** #217 (gradle build image) — pairs with the wrapper bump, Phase A step 1.
- **Hold:** #215 (temurin 17 → 25) — elective; 4.1 supports Java 17–26, and the AI agent has an
  open Java 25 native-access issue (ApplicationInsights-Java#4851).
- **Undecided:** #212/#213/#214 action majors. The inputs this repo passes are unaffected.
  Recommendation: a separate small PR, not folded here. Only `upload-artifact` is exercised by
  PR CI — the `build` job is guarded to push events, so the other two first run on a UAT deploy.

## Working rules that bit these sessions

- **Never pipe `gradlew`** — it wedges under the rtk hook. Redirect to a log file, then read the
  log; background it for the full suite.
- **`ktlintFormat` and `ktlintCheck` must be SEPARATE gradle invocations.** There is no task
  dependency between them, so `./gradlew ktlintFormat ktlintCheck test` races: check runs against
  unformatted files and fails all 45. Run `./gradlew ktlintFormat`, then `./gradlew ktlintCheck test`.
- **Never let a subagent run gradle while a build is in flight** — daemon and file-lock contention.
  Brief research agents as strictly read-only.
- **Run the FULL suite before each commit.** CI gates on `ktlintCheck`.
- The thrifty hook caps inline searches per prompt; bypass one command with
  `THRIFTY_ALLOW_INLINE=1 <cmd>`.
- **Do not chain two heredocs in one Bash call, and avoid apostrophes in heredoc bodies** — the
  wrapper re-quotes and the parse fails. Write the file with the Write tool instead.
- **`grep -i h2` matches `oauth2`.**
- Test config is **`src/test/resources/application-test.yml`**, not `application.yml` (the #105
  shadowing fix). `techaid-validation-and-qa` and `techaid-build-and-env` are stale on this, and
  on the test-file count (they say 19; it is 68).
- **Verify the resolved artifact, not the pin.** The whole point of `6cad5cf` — and of the
  extended-scalars trap above.

## Dead weight noticed, deliberately not removed (CLAUDE.md section 3)

`com.h2database:h2` — nothing in `src/main` or `src/test` references it (it was still version-bumped
in `fff0d49` rather than removed). `org.webjars:font-awesome` — unreferenced. The
`com.netflix.dgs.codegen` plugin and `graphQLVersion` — vestigial, **but see the extended-scalars
trap: deleting the plugin without pinning extended-scalars first breaks the build.** The
`MAINTENANCE_PLAN.md` header still says "PR to `master`", contradicting CLAUDE.md section 5.
