# Carry-on: issue #209 dependency currency → Spring Boot 4.1.x

Rewritten 2026-09-21 (second session) to resume cold. **Delete this file when the work lands.**
Companion: `MAINTENANCE_PLAN.md` → "Tier 4 — Dependency currency, 2026-09" (the checklist).

> This rewrite exists because the first version of this note was wrong in four material ways.
> Everything below marked **VERIFIED** was read from a primary source in-session, not inferred.

## Where things are

| Thing | State |
|---|---|
| Branch | `chore/dependency-currency-209`, 26 commits, tree clean. **Phases A and B are COMPLETE; C all but the runtime check** |
| Forked from | `dev` @ `1726099` (unmoved) |
| PR | **none opened yet** — a *draft* PR against `dev` is the end state |
| `master` / production / UAT | untouched. Prod is server 3.3.0 / `c605bf0` |
| Feature flags | untouched |
| Dependabot PRs | #212–#221 all still open; none merged or closed |
| release-please PR #205 | untouched |
| Suite | **319 tests, 0 failures, 0 skipped**, green at every commit. NOTE: earlier entries in this file and in commit messages said "2 skipped" - that was a miscount of Gradle TASK skips, not tests. There have never been skipped tests. |

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
| `2dbda2e` | **Phase A** dropped `querydsl-jpa-postgres-json` and its two dead method bodies |
| `31809ca` | **Phase A** pinned `graphql-java-extended-scalars` at 19.0 — a verified no-op that closes the DGS trap |
| `aad1d80` | **Phase A** `GraphQlSchemaSwitchesTest`, plus removal of the `application-test.yml` override that made it unable to fire |
| `9211ed2` | **Phase A** root `type Mutation` declared legally (promoted in `adminConfig.graphqls`) |
| `e2c450c` | **Phase A** Flyway `10.22.0` → `11.20.3` |
| `bd88e5b` | **Phase B** Spring Boot `3.4.4` → `4.1.1` (Framework 7.0.9, Security 7.1.1, Hibernate 7.4.5, Kotlin 2.3.21, Flyway 12.4.0, QueryDSL 5.1.0, spring-graphql 2.0.5) |
| `8b6a3a8` | embedded Postgres binaries pinned to **17.11.0**, matching production |
| `ad96dd3` | **Jackson 3** - the spring-boot-jackson2 bridge removed |
| `5c9411d` | **Phase C** vestigial DGS codegen plugin deleted |
| `9b0eba0` | **Phase C** Envers `NOT_AUDITED` semantics measured and pinned |

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

### Phase A — COMPLETE

All seven steps landed, one green commit each, each verified against the **full** suite rather
than the tests expected to be affected. Nothing merged to `dev`.

What Phase A changed about the plan — read this before Phase B:

- **The QueryDSL/kapt blocker in issue #209 does not exist.** kapt still generates all 26 Q-classes
  on Kotlin 2.2.21. K2 kapt has been default since 2.1.20, which was already the pin.
- **Zero JSpecify diagnostics** appeared with `-Xjsr305=strict` still set. That means the Framework
  7 JSpecify fallout is entirely ahead of us; the Kotlin move did not surface any of it.
- **`kotlin-logging-jvm:3.0.5` survived** Kotlin 2.2 with no metadata warnings, so the
  `io.github.oshai` move is not needed *yet*. It may still be needed at 2.3.21.
- **extended-scalars cannot be moved while the DGS plugin exists.** `graphql-dgs-platform:5.5.1`
  applies `strictly [19.0, 20[`, a hard range, not a preference. And the latest release is **24.0**
  — there is no 23.x and no 25.x; the artifact does not track graphql-java's version line. 24.0
  declares graphql-java 24.1 while `io.spring.dependency-management` **forces** the BOM's
  graphql-java, which is a runtime `NoSuchMethodError` pairing a green build will not catch.
  Order: pin (done) → delete DGS plugin → move version, with coercion tests first.
- **`application-test.yml` can silently blind a test.** `GraphQlSchemaSwitchesTest` passed with the
  shipped flag flipped to `true`, because the test yml restated the same key — a leftover from
  before #105, when that file *replaced* `application.yml` instead of overlaying it. **Other
  pre-#105 leftovers may still be masking things in that file; nobody has swept it.**
- **The SchemaMappingInspector crash is now evidenced, not folklore.** Enabling it fails context
  refresh through `getOrCreateReport` → `checkFieldsContainer` → `checkField` →
  `DefaultInitializer.inspect`. It does not warn; it kills startup.
- **A schema input field with no Kotlin counterpart exists today**: `deviceRequests.graphqls:94`
  declares `filters: [JsonComparison!]` and `DeviceRequestItemsWhereInput` has no such property.
  That is exactly what SchemaMappingInspector would report, and it is off.
- **Gradle 9 is not a bump.** The build still reports "Deprecated Gradle features were used in this
  build, making it incompatible with Gradle 9.0". Dependabot #217 was declined for this reason.

### Phase B — COMPLETE

Landed as `bd88e5b` plus the three follow-ups above. Unlike Phase A it could not be split into
individually-compiling steps.

**What the plan did not predict:**

- **The Flyway trap has TWO halves and this note only recorded one.** It warned that missing
  `spring-boot-starter-flyway` makes migrations silently stop running. The opposite is also true:
  the starter ships **no database dialect** (it pulls spring-boot-flyway, spring-boot-jdbc and
  flyway-core only), and since Flyway 10 the dialects are separate modules, so flyway-core alone
  rejects **every** PostgreSQL with `Unsupported Database: PostgreSQL <version>`. You need the
  starter **and** `flyway-database-postgresql`.
- **`hypersistence-utils` was deleted, not upgraded.** Its only use was an `@Converts` block on
  `BaseEntity` mapping `attributeName` "json"/"jsonb" to `JsonStringType`/`JsonBinaryType`. Those
  are Hibernate **UserTypes, not JPA AttributeConverters** - identical in `-63` and `-73` - and
  `BaseEntity` has no attributes for the names to match. It compiled only because Jakarta
  Persistence 3.1 declared `Convert.converter()` as a raw `Class`; 3.2 tightened it. The real jsonb
  mapping is `@JdbcTypeCode(SqlTypes.JSON)` on `Kit.attributes`.
- **`org.hibernate:hibernate-envers` publishes a POM but no jar for 7.x** - a relocation stub. Use
  `org.hibernate.orm:hibernate-envers`, version omitted so the BOM keeps it with the JPA starter.
- **Boot 4 gutted `spring-boot-test-autoconfigure`** to the jdbc and json slices only. Test slices
  moved to per-technology `spring-boot-<tech>-test` artifacts that `spring-boot-starter-test` does
  NOT pull. Needed `spring-boot-webmvc-test` and `spring-boot-graphql-test`.
- **The QueryDSL/kapt blocker central to issue #209 never existed** (already established in Phase
  A): kapt generated all 26 Q-classes on Kotlin 2.3.21 too.

**Moved types:** `EntityScan` → `org.springframework.boot.persistence.autoconfigure`;
`ErrorController`/`ErrorAttributes` → `org.springframework.boot.webmvc.error` (but
`ErrorAttributeOptions` did **not** move); `GraphQlProperties` →
`org.springframework.boot.graphql.autoconfigure`; `AutoConfigureMockMvc` →
`org.springframework.boot.webmvc.test.autoconfigure` (22 files); `AutoConfigureGraphQlTester` →
`org.springframework.boot.graphql.test.autoconfigure.tester`.

**JSpecify produced seven errors, all fixed by intent rather than `!!`:** a JWT with no `aud` claim
now FAILS audience validation instead of throwing; a null GraphQL string literal raises
`CoercingParseLiteralException`; `AuthController` propagates a null principal into the 401 branch
that already existed; `UriComponentsBuilder.fromHttpUrl` is gone (→ `fromUriString`).

### Jackson 3 - three failure modes, three different detectors

Worth reading before any similar migration. Each needed a different instrument:

1. **Compile errors** - the import moves. Found by the compiler.
2. **Runtime only** - two tests `@Autowired` a `com.fasterxml.jackson` `ObjectMapper`. With the
   bridge gone Boot publishes a `JsonMapper` and no Jackson 2 `ObjectMapper` bean, so they would
   have failed at context startup. They **compiled fine**, because
   `logstash-logback-encoder` still drags Jackson 2 onto the classpath transitively. So "it
   compiles" proves nothing about whether this migration is complete.
3. **Silent rebinding** - Jackson 3 adds `<R> R map(Function<JsonNode, R>)` as a **member** of
   `JsonNode`. In Kotlin a member beats an extension, so `node.map { }` stops being `Iterable.map`
   over the node's children and becomes "apply this lambda to the node itself", returning one value
   instead of a list. Same syntax, different meaning. Caught only because the enclosing function
   declared `List<String>`; with a looser target type it would have compiled and been wrong. One
   occurrence in the repo, now `.values().map { }`.

**Annotations did NOT move** - Jackson 3 keeps them at
`com.fasterxml.jackson.core:jackson-annotations` / `com.fasterxml.jackson.annotation`. Five files
still match a grep for `com.fasterxml.jackson` and are **correct**: `TurnstileService`,
`LocationService`, `AppUser`, `KitModels`, `DeviceRequestModels`.

### Phase C — one item left

- [x] **DGS codegen plugin deleted** (`5c9411d`). It generated exactly one file,
  `DgsConstants.kt`, which nothing imports. Its real effect was the version constraint:
  `graphql-dgs-platform:5.5.1` applied `strictly [19.0, 20[` to extended-scalars. Removing it left
  the coordinate resolving purely from the Phase A pin (`19.0 (selected by rule)`) - which is why
  that pin had to come first.
- [x] **Envers `NOT_AUDITED` measured and pinned** (`9b0eba0`). See below; the prediction in this
  note was wrong.
- [x] **A real servlet container is now proven to start** (`ApplicationStartupSmokeTest`). Every
  other context-loading test in this suite uses `WebEnvironment.MOCK`, including
  `ActuatorHealthDetailsTest` - MockMvc calls the MVC stack directly, starts no Tomcat and opens no
  socket. Boot 4 renamed `spring-boot-starter-web` to `-webmvc` and split servlet from web-server
  support, so "the MVC stack responds" and "the container starts and listens" are different claims
  now. This one uses `RANDOM_PORT` and plain `java.net.http.HttpClient`.
- [x] **The shipped artefact is verified end to end** (2026-09-22). `docker build` succeeds - and
  note `Dockerfile:7` runs `gradle build -x test`, so **ktlintCheck runs inside the container** and
  the whole Boot 4 tree is resolved by bare `gradle` from the image, not the wrapper. The image was
  then run against a fresh PostgreSQL 17 with the real `api-testing` environment
  (`SPRING_PROFILES_ACTIVE=testing`, `DDL_AUTO` unset so `validate`):

      Flyway: Successfully applied 47 migrations to schema "public", now at v26.08.26.1000
      Started ApplicationKt in 11.498 seconds, Tomcat on 8080, restarts=0, 39 tables
      /actuator/health  {"groups":["liveness","readiness"],"status":"UP"}
      /actuator/info    version 3.3.0, commit 93b58dc
      anonymous __schema -> "Introspection has been disabled for this request"

  So Hibernate 7 `validate` passes against a Flyway-built schema with `hypersistence-utils` gone,
  and introspection is refused by the *running server*, not just bound in config.

### Trap found while doing that: a missing env var does not fail at startup

`application.yml` has exactly **three** placeholders with no default: `TOKEN_ATTRIBUTE`,
`AUTH_ADMIN_SECRET` and `HOSTNAME` (Docker supplies the last). Both of the first two are container
app env/secretRefs, so UAT and prod are fine - but `spring.main.lazy-initialization: true` means
`authService` is built on the **first request**, not at boot. A missing one therefore produces:

    Started ApplicationKt in 11.498 seconds     <- looks healthy
    ...then every request 500s on PlaceholderResolutionException

**"The container started" is not evidence the app is serving.** If a secretRef ever fails to
resolve mid-deploy, that is the shape it takes, and only the `/actuator/health` probe catches it.

### Envers NOT_AUDITED - measured, and not what this note predicted

This note said the associations would "read **current** state instead of historic audit state".
Measured on Hibernate 7.4.5, the two halves pull in opposite directions:

- the **foreign key IS audited**, so each revision reports the donor assigned AT that revision -
  reassignment is visible in the device history
- the **target is NOT audited**, so the `Donor` is then loaded from the live table and its own
  fields are present-day values

So the trail answers "which donor was this assigned to at the time" **correctly**, and answers
"what was that donor called at the time" with **today's** answer. `KitAuditDonorRelationTest` pins
both halves.

### CLOSED: LazyInitializationException on an Envers-materialised proxy

**Not a Boot 4 regression, and not currently reachable. Latent, not live.**

Reading `entity.donor?.name` **after** the `@Transactional` `kitAudits()` has returned throws:

    org.hibernate.LazyInitializationException: Could not initialize proxy [cta.app.Donor#1]
    - the owning session was closed

despite the shipped config carrying both `open-in-view: false` and
`hibernate.enable_lazy_load_no_trans: true`. The crutch does not cover proxies produced by the
`AuditReader`.

**Measured, not assumed.** The identical probe was run in a git worktree at `c407173` (the last
pre-Boot-4 commit, Boot 3.4.4 / Hibernate 6.6) and fails exactly the same way. The upgrade did not
cause this.

**Why nothing has ever hit it:** both dashboard audit components select scalars only.
`kit-audit-component.component.ts` asks for `model, status, serialNo, updatedAt, createdAt` plus
`subStatus` (an `@Embedded`, not an association); `device-request-audit-component.component.ts`
asks for `status, clientRef, details, borough, ...` plus `deviceRequestItems`. Neither requests
`donor` or `deviceRequest`.

**The trap for whoever touches this next:** adding `donor { ... }` or `deviceRequest { ... }` to
either audit query would fail at runtime in production, with nothing in the test suite to warn
them - and it would look like an upgrade regression when it is six years old. Note this is the
same field list `KitAuditNoOpRevisionTest`'s KDoc already calls out as too narrow for the
dashboard to do its own change detection.

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
  (`application.yml:164-172`). Not researched — **`GraphQlSchemaSwitchesTest` (`aad1d80`) guards it
  with a test instead**, which is cheaper than settling the documentation question. Note its scope
  limit: it catches a changed yml path, not an inspector that crashes at startup.
- Whether spring-graphql 2.0 changed `DataFetcherExceptionResolverAdapter`, `RuntimeWiringConfigurer`
  or `WebGraphQlInterceptor`. The 2.0 notes list **no removed APIs at all** — weak evidence, not
  strong.
- spring-graphql 1.4's "performance enhancements for Servlet-based applications" — could not
  establish whether these change the execution thread model. This is the one thing that could
  invalidate the security-propagation reasoning above.

## Two live risks — BOTH RESOLVED, kept for the corrections

- ~~**Envers `NOT_AUDITED` changed semantics at Hibernate 7.3** — previously ignored, now
  respected, so those associations read **current** state instead of historic audit state.~~
  **Wrong as stated.** The 9 sites are correctly listed (`KitModels.kt` 37/71/75/163,
  `DonorModels.kt` 28/91, `OrganisationModels.kt` 28/91, `DeviceRequestModels.kt` 99), but the
  measured behaviour splits: the **foreign key is audited** (revisions show the donor assigned at
  the time) while the **target is not** (that donor's own fields are present-day). See
  "Envers NOT_AUDITED — measured" above and `KitAuditDonorRelationTest`.
- ~~**In Boot 4, missing `spring-boot-starter-flyway` means migrations silently stop
  auto-running.**~~ True but only **half** the trap, and the missing half is what actually bit.
  The starter ships **no database dialect**, and since Flyway 10 the dialects are separate modules,
  so `flyway-core` alone rejects every PostgreSQL with `Unsupported Database: PostgreSQL <version>`.
  You need the starter **and** `flyway-database-postgresql`. Symptom is every `@SpringBootTest`
  failing at context startup, and the version named in the message is a red herring — it changes
  with the server and is never the cause.

## Issue #222 — a live regression found on the way

**Kit free-text search has silently not matched `attributes.notes` since 2024-11-12** (~22 months).
`KitFilters.kt:332-333` has `attributes?.let { null }` where every sibling reads
`x?.let { builder.and(...) }`, so the input is accepted and dropped. Introduced by `6c2ced4`
(Akhil) to bypass a Hibernate 6 breakage rather than fix it. The **dashboard still sends the
filter** (`kit-index.component.ts:42`, the main kit-list search box), in an `OR` with `model` /
`serialNo` / `id` — so results come back HTTP 200, just missing notes matches. No test covers the
path, which is why it survived. Restoring it needs a `FunctionContributor` or a native jsonb
predicate — a product decision, not an upgrade blocker.

## Dependabot - actioned 2026-09-22

**Closed** (comments on each PR record the reasoning):
- **#218** test-infrastructure - superseded, those exact versions landed in `fff0d49`.
- **#216** spring 3.5.16 - the 3.5 line is skipped; branch is on 4.1.1.
- **#217** gradle 9.7.1 - image-only, would re-create the wrapper/image divergence `fde2284` closed.
- **#219 / #220 / #221** - folded into `f9b70bd` (font-awesome 7.3.0, auth0 1.12.1,
  jakarta.mail 2.0.2) rather than merged, since a branch cut from `dev` would conflict with the
  upgrade.

**Left open, deliberately out of this deploy:**
- **#215** temurin 17 -> 25. Elective; App Insights has an open Java 25 native-access issue, and
  stacking a JRE change on a Boot 4 UAT deploy makes any failure ambiguous.
- **#212 / #213 / #214** action majors. CI-only, and only `upload-artifact` runs on PRs - the other
  two first execute **during a UAT deploy**, so bundling them would make a broken deploy impossible
  to attribute. Worth a separate small PR after this lands.
- **#205** release-please. Note the `feat!:` commit means it will want a **major** once this
  reaches `dev`.

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
