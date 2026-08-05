# Backend review — Kotlin / Ktor / Neo4j

**Date:** 2026-08-05
**Scope:** `backend/` at the current uncommitted working tree (~1 050 lines of Kotlin).
**Baseline:** `CLAUDE.md` §4, §5, §7, §11; `docs/features/requirements-modules.md`;
`docs/features/attribute-policy-checks.md`.

This is a review of backend code, patterns and Ktor usage. Findings are ordered by severity.
Every claim marked **verified** was reproduced against the running service or the build; claims
marked **predicted** are reasoned from the code and explicitly not yet reproduced.

---

## 1. Verdict

The architecture is genuinely good. The hard decisions — the `__` namespace, Tier 1 vs Tier 2,
one guarded meta write path, Cypher as parameterised constants, a single driver, sealed results
instead of exceptions for expected failures — are all made correctly and consistently. That is
the part that is expensive to change later, and it is right.

The weaknesses are almost entirely in the **edges**: what happens on malformed input, what
happens when the database is slow or down, and whether the quality gate actually runs. Several
of these are not "improvements" but **stated requirements in `CLAUDE.md` that the code does not
yet meet**, and two of them currently leak internal details to HTTP clients.

Nothing here requires an architectural change. The list is a day or two of focused work.

### How this was verified

```
curl against the running service on :8080     → error-handling behaviour (§3.1)
./gradlew :backend:check                      → quality gate status (§3.2)
grep over backend/src/main/kotlin             → timeouts, logging, connectivity (§3.3, §4.3)
cypher-shell against the live database        → schema/constraint state (§3.4)
```

---

## 2. What is already right — keep it

Worth stating explicitly so none of it is "cleaned up" later by mistake:

- **`explicitApi()` is on.** Public surface is deliberate, not accidental.
- **Sealed results over exceptions for expected failures** (`SaveModuleSettingsOutcome`). The
  route's `when` is exhaustive, so a new failure mode is a compile error, not a runtime 500.
  This is the single best pattern in the codebase — extend it, do not dilute it.
- **Cypher lives in named constants and is always parameterised.** `SET n += $props`, maps as
  parameters, no string concatenation from source data. Given DOORS attribute names contain
  spaces, dots and umlauts, this is what keeps the system injection-proof and correct.
- **One `Driver` for the process, sessions per request**, with reads on `executeRead` and
  writes on `executeWrite`. On Community, per-transaction access mode is the only server-side
  write protection there is, and it is used correctly.
- **The alias map is server-side only** and DTOs are built from it, so `__`-prefixed names
  cannot reach the UI by construction.
- **`ref` is base64url of `__id`** and decoding is centralised rather than smeared across
  handlers.
- The **R1/R2 regression intent** in `ModulesFeatureTest` (asserting the anchor node's property
  map is byte-identical across a write) is exactly the right test to have written first.

---

## 3. Priority 1 — correctness and stated-contract violations

### 3.1 `StatusPages` is installed but empty, and the service leaks internals — **verified**

`Application.kt` installs `StatusPages` with a body containing only comments. Nothing maps
exceptions to RFC 9457. Reproduced against the running service:

| Request | Actual | Expected |
|---|---|---|
| `GET /api/v1/modules/!!!not-base64!!!` | **`500`**, body `Illegal base64 character 21` | `400` + problem detail |
| `POST …/settings` with `{bad json` | `400`, body `Failed to convert request body to class com.sec.api.dto.ModuleSettingsRequestDto` | `400` + problem detail |
| `GET /api/v1/nope` | `404`, **empty body** | `404` + problem detail |

Two of those leak internal detail to the client — a raw JDK exception message, and the
fully-qualified name of an internal DTO class. `CLAUDE.md` §5 says *"Errors: `StatusPages`
mapping domain exceptions to RFC 9457 problem details. No stack traces to the client, ever."*
The first row is also a **client** error being reported as a server error, which will pollute
any future alerting.

Root cause of the 500: `Ref.decode` calls `Base64.getUrlDecoder().decode()`, which throws on
malformed input, and `decodeRef()` does not handle it.

**Fix.** Make decoding total, and give `StatusPages` real handlers:

```kotlin
// domain/Ref.kt — decoding a user-supplied path segment is an expected failure, not an exception.
public fun decodeOrNull(ref: String): String? =
    runCatching { String(decoder.decode(ref), Charsets.UTF_8) }.getOrNull()
```

```kotlin
install(StatusPages) {
    exception<BadRequestException> { call, _ ->
        call.respondProblem(HttpStatusCode.BadRequest, "Malformed request",
            "The request body could not be read.")           // never echo the cause
    }
    status(HttpStatusCode.NotFound) { call, _ ->
        call.respondProblem(HttpStatusCode.NotFound, "Not found", "No such endpoint.")
    }
    exception<Throwable> { call, cause ->
        logger.error(cause) { "Unhandled failure" }           // full detail to the log only
        call.respondProblem(HttpStatusCode.InternalServerError, "Internal error",
            "Something went wrong. Quote reference ${call.callId} when reporting this.")
    }
}
```

Note the last line: `CallId` is already installed but the correlation id is never surfaced to
the client. Putting it in the problem detail (`instance`, or the sentence above) is what makes a
user-reported 500 traceable in the logs. Cheap, and worth doing at the same time.

### 3.2 The quality gate is red — **verified**

```
$ ./gradlew :backend:check
ModulesFeatureTest > initializationError FAILED
    java.lang.IllegalStateException: Could not find a valid Docker environment.
6 tests completed, 1 failed
BUILD FAILED
```

`CLAUDE.md` §11 makes `./gradlew check` a precondition for calling work done. It cannot pass on
this machine, because there is no Docker. The practical consequence is that the gate gets
skipped habitually, which is how the other five tests stop being trusted too.

**Fix — separate "needs Docker" from "does not".** Tag the container tests and exclude them from
the default `test` task, with a dedicated task that runs them:

```kotlin
tasks.test {
    useJUnitPlatform { excludeTags("docker") }
}

val integrationTest by tasks.registering(Test::class) {
    useJUnitPlatform { includeTags("docker") }
    shouldRunAfter(tasks.test)
}
```

`check` then stays green locally and CI runs `integrationTest` where Docker exists. This is
better than deleting or ignoring the test.

### 3.3 A second, latent failure in `ModulesFeatureTest` — **predicted, not verified**

The Docker error masks a lifecycle bug that will surface the moment this runs on a machine that
*has* Docker:

```kotlin
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModulesFeatureTest {
    @Container
    private val neo4j = Neo4jContainer(...)     // ← instance field, not static

    @BeforeAll
    fun setUp() { graphDriver = GraphDriver(Neo4jSettings(neo4j.boltUrl, ...)) }
```

The Testcontainers JUnit 5 extension starts **static** `@Container` fields in `beforeAll` and
**instance** fields in `beforeEach`. This field is an instance field, so it starts *after*
`@BeforeAll` has already run — and `boltUrl` calls `getMappedPort`, which throws
`IllegalStateException("Mapped port can only be obtained after the container is started")`.
Even if the ordering were fixed, an instance container restarts per test method, so the driver
built once in `@BeforeAll` would point at a dead port for the second test.

**Fix — drop the annotations and own the lifecycle explicitly.** Clearer than remembering the
static/instance rule, and it composes with `PER_CLASS`:

```kotlin
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("docker")
class ModulesFeatureTest {
    private val neo4j = Neo4jContainer("neo4j:2026.01-community").withoutAuthentication()

    @BeforeAll fun setUp() {
        neo4j.start()
        graphDriver = GraphDriver(Neo4jSettings(neo4j.boltUrl, "neo4j", "neo4j", "ignored"))
        …
    }

    @AfterAll fun tearDown() {
        graphDriver.close()
        neo4j.stop()
    }
}
```

Also: the test pins `neo4j:2026.01-community` while the local install is `2026.06.0`. Pin the
image to the same series you deploy, and put the version in `libs.versions.toml` so it is
visible next to everything else.

### 3.4 No transaction timeouts — **verified**, and a comment asserts otherwise

`grep` finds no `TransactionConfig`, no timeout, anywhere in `main/`. Yet
`graph/cypher/ModuleCypher.kt:4` states:

> *"Every statement is CYPHER 25-prefixed, parameterised, and reads carry a LIMIT + transaction
> timeout (CLAUDE.md §5, §7)."*

The `LIMIT` half is true; the timeout half is not implemented. A comment that documents an
absent guarantee is worse than no comment, because the next reader trusts it.

This matters more here than in most systems: `CLAUDE.md` §7 is explicit that Community has
**no query governor**, so *"a single unbounded query can exhaust the instance"* and the
application-side timeout is the only protection that exists. The ad-hoc Cypher console
(`docs/CYPHER_API_DESIGN.md`) makes that a user-reachable risk, not a theoretical one.

**Fix.** Put the timeout in the two functions that own every session, so no call site can forget:

```kotlin
// graph/Read.kt
private val READ_TX: TransactionConfig =
    TransactionConfig.builder().withTimeout(Duration.ofSeconds(10)).build()

public suspend fun <T> GraphDriver.executeRead(query: Query, transform: (List<Record>) -> T): T =
    withContext(Dispatchers.IO) {
        driver.session(SessionConfig.forDatabase(database)).use { session ->
            session.executeRead({ tx -> transform(tx.run(query).list()) }, READ_TX)
        }
    }
```

Make the duration configurable (`neo4j.readTimeout`) so the Cypher console can be given a
tighter one than internal reads. Then fix the comment.

### 3.5 The `:__Meta` schema is never created — **verified**

`grep` finds no `CREATE CONSTRAINT` or `CREATE INDEX` in the backend. `CLAUDE.md` §7 lists
`:__Meta(__metaId)` among the uniqueness constraints *"that are what we use"*, §10 assigns
`:__Meta` schema ownership to the backend, and `attribute-policy-checks.md` §3 requires
`meta_policy_attribute`. None of them exist in the live database.

So every meta node written so far has been written with **no uniqueness enforcement**, and the
"which modules mark this attribute mandatory" query the Statistics view will need has no index.

**Fix.** A `meta/MetaSchema.kt` applied once at startup, before routing is configured. Schema
statements cannot share a transaction with data, so run them individually:

```kotlin
private val STATEMENTS = listOf(
    "CYPHER 25 CREATE CONSTRAINT meta_id_unique IF NOT EXISTS FOR (m:__Meta) REQUIRE m.__metaId IS UNIQUE",
    "CYPHER 25 CREATE INDEX meta_policy_attribute IF NOT EXISTS FOR (p:__Policy) ON (p.attributeName)",
)
```

Both are `IF NOT EXISTS`, so this is idempotent and safe on every boot. Keep it strictly to
`:__Meta` labels — imported-label schema belongs to the importers (§10), and the split must not
blur.

---

## 4. Priority 2 — Ktor and configuration idiom

### 4.1 Credentials bypass Ktor's config system

`config/AppConfig.kt` reads `System.getenv` directly:

```kotlin
user = System.getenv("SEC_NEO4J_USER") ?: error("SEC_NEO4J_USER is not set"),
```

Ktor already resolves environment variables inside config files, so this reimplements a
platform feature and then pays for it: because `module()` unconditionally requires those two
env vars, `backend/build.gradle.kts` needs

```kotlin
tasks.test {
    environment("SEC_NEO4J_USER", "test")     // a workaround for the line above
    environment("SEC_NEO4J_PASSWORD", "test")
}
```

and `ApplicationTest` cannot supply credentials through `MapApplicationConfig` like every other
setting. A test-only hack in the build file is a signal that production code has the wrong seam.

**Fix.** Move the indirection into the yaml and delete both workarounds:

```yaml
neo4j:
  uri: "bolt://localhost:7687"
  database: "neo4j"
  user: "$SEC_NEO4J_USER"
  password: "$SEC_NEO4J_PASSWORD"
```

```kotlin
user = config.property("neo4j.user").getString(),
password = config.property("neo4j.password").getString(),
```

Config now has exactly one source, tests override it the same way they override everything else,
and credentials still never appear in the file. Keep failing fast on absence — just let Ktor
produce the error.

### 4.2 The app starts successfully against a dead database

`GraphDriver` constructs the driver but never calls `verifyConnectivity()`. The Neo4j driver is
lazy, so startup succeeds, the process reports healthy, and the first request fails. Meanwhile
`GET /api/v1/health` returns a hardcoded `"ok"` that never touches Neo4j — so an orchestrator's
health probe cannot distinguish "working" from "database unreachable".

**Fix.** Fail fast at startup, and split the two concerns:

- `verifyConnectivity()` in `GraphDriver.init` (or right after construction in `module()`), so a
  misconfigured deployment dies immediately with a clear cause instead of serving 500s.
- Keep `/health` as a cheap **liveness** probe, and add `/ready` that runs `RETURN 1` against the
  database for **readiness**. Only the latter should gate traffic.

### 4.3 `kotlin-logging` is a declared dependency with zero call sites — **verified**

There is not a single logger in `main/`. All observability comes from `CallLogging`'s
request lines. When the unhandled-exception handler from §3.1 lands it will need a logger
immediately, so this is really "unfinished", not "unused".

Also outstanding from `CLAUDE.md` §5: *"Structured logging … JSON encoder in production."*
`logback.xml` has only a plain pattern encoder. Add a JSON profile selected by environment —
`callId` is already in the MDC, so structured output makes requests traceable end to end with no
further code changes.

### 4.4 Wiring and route organisation

`configureRouting` news up its own collaborators:

```kotlin
val doorsProjection = DoorsProjection(graphDriver)
val metaWriter = MetaWriter(graphDriver, doorsProjection)
```

At this size that is fine and I would **not** add a DI framework for it (that would need
approval under §11 anyway). But two cheap improvements pay off before the next feature:

1. Construct collaborators once in `module()` and pass them in, so routing does not own object
   lifecycles and tests can substitute fakes.
2. Split routes per feature — `api/routes/ModuleRoutes.kt` with
   `fun Route.moduleRoutes(...)` — leaving `Routes.kt` as a table of contents. `Routes.kt` is
   123 lines covering one feature; the API list in §5 has roughly fifteen more endpoints.

### 4.5 No OpenAPI, and the frontend client is hand-written

`CLAUDE.md` §5: *"OpenAPI: use Ktor 3.4+ built-in OpenAPI generation; the spec is a build
artifact, and the frontend's API client is generated from it, not hand-written."*

Today `frontend/src/app/features/requirements/modules/modules.model.ts` restates
`api/dto/ModuleDtos.kt` by hand. Seven interfaces are duplicated across two languages with
nothing enforcing agreement — renaming a DTO field is a silent runtime break with a green build
on both sides.

This is the largest *structural* gap, but it is also the one whose fix should be verified
against the Ktor 3.5.1 docs rather than taken from memory — the OpenAPI story has moved between
minor versions. Treat it as a scoped spike: confirm what 3.5.1 generates, then wire the frontend
client generation into the build. Until then, the duplication is a known risk, not an accident.

---

## 5. Priority 3 — smaller notes, mostly non-obvious

- **`executeRead`'s `transform` runs inside the retryable transaction.** `session.executeRead`
  retries on transient failures, re-invoking the whole lambda — including mapping. It is pure
  today; keep it that way, and say so in the KDoc. A future `transform` that increments a metric
  or writes a cache would double-count invisibly under retry.
- **`DISCOVER_ATTRIBUTES` uses `LIMIT` without `ORDER BY`**, so the 25-object sample is
  nondeterministic. `requirements-modules.md` §4.2 accepts sampling, but an unordered `LIMIT`
  makes the endpoint's output vary run to run for the same data, which is confusing to debug.
  Order by `__sortKey` and the sample becomes "the first 25 in document order" — reproducible,
  and no slower given the index.
- **Attribute discovery is not cached**, though §4.2 says *"Cache the discovery result per module
  for the process lifetime — the imported zone only changes when an importer runs."* Every save
  that adds an attribute re-runs the scan.
- **Check-then-act across three transactions.** `saveModuleSettings` runs `moduleExists`, then
  `discoverAttributeNames`, then the write — three separate transactions. Low risk for an
  internal tool with one writer, but the validation is not atomic with the write. Worth a comment
  acknowledging it; worth fixing only if concurrent imports become real.
- **`SEItemDto` is in the wrong layer.** It is a `@Serializable` wire type living in `domain/`,
  while every other DTO is in `api/dto/`. §5's structure assigns DTOs to `api/`. Move it before
  more types follow the wrong example.
- **Row order depends on `mapOf` iteration order.** `DoorsProjection` builds the properties list
  by iterating `Aliases.modulePropertyLabels`, so the dialog's row order is the map's insertion
  order. That works (`mapOf` returns a `LinkedHashMap`) but it is an implicit contract. One
  comment on the map stating "insertion order is the display order" prevents someone
  alphabetising it later.
- **`LIST_MODULES` truncates silently** at `$limit` (default 500) with no signal to the client.
  `attribute-policy-checks.md` §4 already establishes the right pattern — a `truncated` flag.
  Use the same shape here.
- **No static analysis.** `check` runs tests only; there is no ktlint/detekt/`.editorconfig`.
  Given `explicitApi()` and a strict style already described in §11, a formatter would remove a
  whole class of review comments. This needs dependency approval under §11 — flagging, not
  assuming.
- **Empty scaffolding files** (`CypherGuard`, `ItemCypher`, `CameoProjection`,
  `WindchillProjection`) are fine as placeholders, but `CypherGuard` in particular is a
  security-critical stub whose class exists with no implementation. Make sure nothing wires it up
  believing it guards anything.

---

## 6. Suggested order of work

1. **`StatusPages` handlers + `Ref.decodeOrNull`** (§3.1) — stops the internal-detail leak and
   the 500-on-client-error. Highest value, ~30 minutes.
2. **Tag the Docker tests and fix the container lifecycle** (§3.2, §3.3) — gets `./gradlew check`
   green so the gate means something again.
3. **`:__Meta` schema migration** (§3.5) — restores the uniqueness guarantee §7 claims, and the
   longer it is missing the more unconstrained data accumulates.
4. **Transaction timeouts** (§3.4), then correct the comment that claims they exist.
5. **Config via Ktor env substitution** (§4.1) — deletes the build-file test hack.
6. **`verifyConnectivity` + a real `/ready`** (§4.2), and put the logger to use (§4.3).
7. **Split routes, hoist wiring into `module()`** (§4.4) — do this before the next feature, not
   after.
8. **OpenAPI spike** (§4.5) — schedule deliberately; it removes the cross-language duplication.

Items 1–6 are small and independent. Item 7 is cheap now and expensive later. Item 8 is the only
one needing design time.

---

## 7. Anything requiring a decision

Two items touch rules in `CLAUDE.md` §11 ("do not, without asking"):

- **Static analysis** (§5, ktlint or detekt) adds a dependency and is not in §4's table.
- **OpenAPI generation** (§4.5) may add a plugin or dependency depending on what Ktor 3.5.1
  provides natively.

Everything else in this document is implementable with the current dependency set and does not
change the identity scheme, the label model, or the meta model.
