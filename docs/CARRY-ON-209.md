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

### Two different failure modes for the two defaultless variables - do not conflate them

`application.yml` has exactly three placeholders with no default: `TOKEN_ATTRIBUTE`,
`AUTH_ADMIN_SECRET` and `HOSTNAME` (the platform supplies the last). Both of the first two are set
on `api-testing` and `api-production`, so neither environment is at risk. **But they fail
differently, and that difference is the whole point:**

- **`TOKEN_ATTRIBUTE`** feeds `filterService` -> `userTelemetryFilter`, a **servlet filter**.
  Filters are registered when Tomcat initialises, so it fails **eagerly**. The app never starts and
  never reports healthy. Always safe.
- **`AUTH_ADMIN_SECRET`** feeds `authService` in the security chain, which
  `spring.main.lazy-initialization: true` defers to the **first request**. That produced the
  dangerous shape:

      Started ApplicationKt in 11.498 seconds     <- container reports healthy
      ...then every request 500s deep inside WebSecurityConfiguration

`RequiredConfigurationCheck` (`6e7db16`) closes the second case with `@Lazy(false)`. **Proven, not
assumed** - the image was run with `AUTH_ADMIN_SECRET` omitted and now exits 1 during context
refresh with zero `Started ApplicationKt` lines and a message naming the property, the variable and
why it has no default. A first attempt asserted on environment VARIABLE names and broke all 48
context-loading tests, because `application-test.yml` supplies the Spring properties directly; it
asserts on resolved properties now, with a test pinning that distinction.

The defaults stay absent deliberately: `X-Auth-Admin-Secret` grants full authorities, so defaulting
it - an empty default above all - would fail **open**.

### GraphQL over HTTP: the 4xx flip is real but narrower than this note claimed

Measured against the running server. This note previously said `application/graphql-response+json`
is "THE DEFAULT when the client expresses no Accept preference". **That is wrong** - a client
sending no `Accept` header gets 200.

| | `Accept: application/json` | Apollo 4's real Accept | no Accept header |
|---|---|---|---|
| valid query | 200 | 200 | 200 |
| **authz denial (execution error)** | **200** | **200** | **200** |
| validation error | 200 | **400** | 200 |

The dashboard runs `@apollo/client ^4.1.7`, which *does* send
`application/graphql-response+json, application/json;q=0.9`, so it **is** in scope - but only for
validation errors, which need a query/schema mismatch to occur. **Authz denials stay 200 under
every header**, so normal operation and the calendar-sync Apps Script's `'access denied'` retry are
unaffected.

### VERIFIED against a replica of UAT's real schema

The fresh-schema test was not enough: UAT predates Flyway and diverges from a clean build (#91),
and both environments run `ddl-auto: validate`. A structure-only dump of UAT `public` plus the
`flyway_schema_history` rows was restored into a local PostgreSQL 17 and the image booted against
it:

    replica: 36 tables, history at 26.08.26.1000 (matches UAT)
    Flyway:  Current version 26.08.26.1000 -> "Schema is up to date. No migration necessary."
    health:  UP in 15s,  schema-validation errors: 0

So on the real schema shape: Flyway checksum validation passes, **zero** migrations apply, and
Hibernate 7 `validate` is clean. **Not covered:** the `gdpr` schema (dump was `-n public`), and any
query against real rows.

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
