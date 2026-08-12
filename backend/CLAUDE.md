# CLAUDE.md — System Engineering Cockpit backend

Guidance for Claude Code working under `backend/`. This file loads **in addition to** the
root `CLAUDE.md`, which stays always-loaded and holds the rules that bind every stack: the `__`
namespace and Tier 1 / Tier 2 model (R1–R3, R5–R7), where each kind of state lives, the pinned
versions, the Neo4j Community limits, and the working agreements. Read that first; nothing here
overrides it.

Section numbers below are the root file's own and are deliberately unchanged — code comments and
`docs/` reference them as "CLAUDE.md §6", and those references still resolve.

## 5. Backend — Ktor

### Structure

```
com.sec
├── Application.kt              ← module wiring only, no logic
├── config/                     ← typed config from application.yaml + env
│                                 ConfigArgs.kt makes -config= an overlay, not a replacement (§2)
├── graph/
│   ├── GraphDriver.kt          ← single Driver instance, app-lifecycle scoped
│   ├── Read.kt / Write.kt      ← the ONLY places a session is opened
│   └── cypher/                 ← Cypher as named constants, one file per domain
├── domain/                     ← SEItem, meta model, source-agnostic
├── source/                     ← per-source projections (doors/, windchill/, cameo/)
├── meta/                       ← R2 write path, guarded
├── domain/GraphNames.kt        ← every source-agnostic graph name (ADR 0010) — see below
├── domain/Aliases.kt           ← R5 alias map, single source of truth
├── source/doors/DoorsNames.kt  ← every DOORS name; one such file per source
├── meta/MetaSchema.kt          ← :__Meta constraints/indexes, applied at startup (§7)
├── api/
│   ├── ApiPaths.kt             ← /api and /api/v1, once — the routes and the SPA fallback
│   │                             both depend on them and must not drift
│   ├── Routes.kt               ← table of contents: registers the files below, nothing else
│   ├── routes/                 ← one file per feature — ModuleRoutes.kt, ConfigRoutes.kt, ...
│   ├── ProblemPages.kt         ← StatusPages → RFC 9457, the only error-to-wire mapping
│   └── dto/                    ← @Serializable wire types
└── security/                   ← auth, the ad-hoc Cypher guard
```

### Names: one declaration each (ADR 0010)

**No Kotlin code addresses the graph with a string literal.** Property names, labels, relationship
types, `__metaKind` values and meta payload keys each have exactly one declaration:

- `domain/GraphNames.kt` — `Prop`, `Rel`, `NodeLabel`, `MetaKind`, `MetaProp`, `MetaValue`. The
  `__` namespace, `:SEItem`, `:__UNDEFINED`, all of Tier 2. Source-agnostic, imports nothing.
- `source/doors/DoorsNames.kt` — `DoorsAttr`, `DoorsModuleAttr`, `DoorsProp`, `DoorsRel`,
  `DoorsLabel`. **A new source adds its own names file; it never edits another's.**
- `source/jira/JiraNames.kt`, `source/windchill/WindchillNames.kt` — the same, per source. Note the
  rule both inherit: **no Kotlin type in those packages may share its name with a label the file
  declares**, because the inverse guard reads statement *source* including its import lines. That is
  why the wire type is `WindchillDocumentRow` and the parsed record is `WindchillRecord`, and why
  neither is called `WindchillDocument`.

`props["__id"]` and `labels.contains("DOORSTBD")` are defects. So is a second constant for a name
that already has one — `__UNDEFINED` was declared twice before this rule existed.

**The Cypher interpolates every one of them — nothing addresses the graph by literal.** Renaming a
name is one edit, in the file that declares it. Use **single-name imports** so the statements stay
readable; the constant's simple name is the graph name in SCREAMING_SNAKE, and a collision between
two vocabularies is resolved by aliasing the import, never by qualifying it in the string.

```kotlin
import com.sec.domain.Prop.MODULE_URL                       // -> __moduleUrl
import com.sec.source.doors.DoorsLabel.OBJECT as DOORS_OBJECT

MATCH (o:$DOORS_OBJECT {$MODULE_URL: ${'$'}moduleUrl})      // a name, then a parameter
```

`const val` initialisers may interpolate other `const val`s, so the statements stay compile-time
constants. Two things are deliberately **not** interpolated: `MetaSchema`'s constraint and index
names, which are its own database objects, and query **parameter** names, which are a contract
between one statement and its one call site.

**`GraphNamesTest` is what makes that stick, and it must keep passing.** Two checks in opposite
directions: every name in a *compiled* statement is declared, **and** no graph name appears as a
literal in a statement's *source* (comments stripped first — comments are where these names belong).
The second is the one that matters, because a hand-written `__id` compiles to the identical string
and the first check cannot see it. Adding a Cypher file means adding its statements to that test; a
completeness check fails if you forget.

**Test fixtures deliberately keep the literals.** A fixture that writes `"Object Text"` and asserts
the projection read it independently pins the constant's value; building the fixture from the
constant too would let a wrong constant pass.

### Non-negotiables

- **One `Driver` for the process lifetime**, created in `Application.kt`, closed on
  `ApplicationStopping`. Never per-request. Sessions are per-request and short-lived.
- **`CYPHER 25` prefix on every statement.** The 2026.01 server default depends on how
  the DB was created; prefixing makes behaviour deterministic.
- **Never build Cypher by string concatenation from user or source data.** DOORS
  attribute names contain spaces, dots, slashes, parentheses and non-ASCII characters.
  Pass maps as parameters and use `SET n += $props`; map keys need no quoting. The only
  string-built part of any statement is a label chosen from a closed enum.
- **Reads use `session.executeRead`, writes use `session.executeWrite`.** This is not
  style: on Community, per-transaction access mode is the only server-side write
  protection that exists.
- Start with the driver's blocking API on `Dispatchers.IO` inside `withContext`. Only
  move to the reactive API bridged through `kotlinx-coroutines-reactive` if profiling
  shows it matters. Do not mix the two.
- Content negotiation with `kotlinx.serialization`. DTOs are explicit `@Serializable`
  classes — never leak a driver `Record` or `Node` to the wire.
- The dynamic DOORS attribute bag is `Map<String, JsonElement>` on the wire. **Do not
  generate a typed DTO per module.** Attribute sets differ per module by design.
- Structured logging (`kotlin-logging` + Logback, JSON encoder in production). Every
  request gets a correlation id via `CallId`.
- Errors: `StatusPages` mapping domain exceptions to RFC 9457 problem details. No stack
  traces to the client, ever.
- OpenAPI: use Ktor 3.4+ built-in OpenAPI generation; the spec is a build artifact, and
  the frontend's API client is generated from it, not hand-written.

### API shape

```
GET  /api/v1/health                     ← liveness: the process is up, touches no database
GET  /api/v1/ready                      ← readiness: pings the graph, 503 when it is unreachable
GET  /api/v1/tree                       ← root of the knowledge tree, lazy children
GET  /api/v1/items/{ref}                ← one SEItem, source-agnostic envelope
GET  /api/v1/items/{ref}/children
GET  /api/v1/items/{ref}/traces         ← refersTo out; ?direction=in for incoming (schema §8.2)
GET  /api/v1/items/{ref}/breakdown      ← the decomposition forest; maxDepth/maxNodes bounded
GET  /api/v1/items/{ref}/graph          ← the dependency graph; ?depth=1..5&direction=&levels=, 300-node cap
GET  /api/v1/items/{ref}/annotations    ← Tier-2 data attached to an item
POST /api/v1/items/{ref}/annotations    ← R2 write path
PATCH/DELETE /api/v1/annotations/{ref}
GET  /api/v1/modules                    ← DOORS-specific projection
GET  /api/v1/modules/{ref}              ← module detail for the settings dialog
GET  /api/v1/modules/{ref}/objects      ← review table rows, document order, paged + capped
POST /api/v1/modules/{ref}/settings     ← system level + mandatory diff + attribute flags, one txn
POST /api/v1/modules/system-levels      ← the Modules table's batch save; spans modules, so not {ref}-scoped
POST /api/v1/modules/{ref}/comments     ← every dirty comment for one module, one txn
GET  /api/v1/modules/{ref}/attributes   ← runtime attribute discovery, namespace filtered
GET  /api/v1/modules/{ref}/checks/attribute-policy
GET  /api/v1/modules/{ref}/tables      ← reconstructed DOORS tables, no parameters
GET  /api/v1/items/{ref}/table         ← the table a table, row or cell belongs to
GET  /api/v1/statistics/requirements     ← Statistics view; ?module={ref} scopes it
GET  /api/v1/statistics/requirements/cycles  ← loop detection, its own endpoint so Band 4 loads apart
GET  /api/v1/windchill/health           ← is a Windchill host configured; no credential exists
GET  /api/v1/windchill/documents        ← every imported document, unpaged and server-capped
POST /api/v1/windchill/import           ← upload an OData export and import it, one request (ADR 0015)
GET  /api/v1/config/navigation          ← sidenav structure, read-only
GET  /api/v1/config/system-levels       ← classification vocabulary, cacheable
POST /api/v1/cypher/explain             ← see docs/CYPHER_API_DESIGN.md
POST /api/v1/cypher/run
```

`{ref}` is the base64url encoding of `__id` (R5). Decode it in one place — a route
parameter converter — never inline in a handler. **Decoding is total**: a hand-edited address bar
is a `400`, not an uncaught `IllegalArgumentException` reported as a `500` (`Ref.decodeOrNull`).

Liveness and readiness are separate endpoints on purpose. An orchestrator restarts on a failed
liveness probe but only withholds traffic on a failed readiness probe, so a slow or briefly
unreachable database must not be able to trigger a restart loop. Only `/ready` opens a session.

Every failure — including an unmatched path and an unhandled exception — leaves the service as an
RFC 9457 problem detail carrying the `CallId` in `instance`. Exception messages are logged, never
echoed: they contain internal type names and JDK text that R5 keeps off the wire.

Feature-shaped write endpoints such as `POST /modules/{ref}/settings` exist because a
dialog is one transaction, not N annotation calls. They **route through the same guarded
meta writer** as `POST /items/{ref}/annotations`. One meta write path, however many
endpoints reach it.

**The built frontend ships inside the backend jar.** `mvn -Pui package` copies
`frontend/dist/frontend/browser` into the artifact under `static/`, and `api/routes/UiRoutes.kt`
serves it: assets by path, and `index.html` for any non-`/api` path with no file extension, so an
Angular route survives a reload. Two rules that must not drift, both covered by `PackagedUiTest`:

- **`/api/**` is never answered with a page.** An unknown endpoint stays an RFC 9457 problem
  detail — a 200 with HTML in it is the least useful possible answer to a mistyped API call.
- **A missing *file* is a 404, not the index.** Returning `index.html` for a stale
  `main-A1B2C3.js` after a redeploy hands the browser an HTML document with status 200, which it
  reports as a syntax error. The extension is what separates an asset from a route.

Ktor's own `staticResources("/", …)` cannot be used for this: mounted at the root it installs a
catch-all that answers 404 itself, taking both rules with it.

Item responses carry `labels: string[]` so the frontend can switch on the type label, and
this is the one place raw label strings cross the wire. They are a *state channel*, not
display text: the UI maps `__UNDEFINED` to "Not yet imported" and `DOORSTBD` to "TBD".
Always include `__UNDEFINED` when present — placeholders must never render as real
requirements.

### Ad-hoc Cypher endpoint

Implement exactly the four layers in `docs/CYPHER_API_DESIGN.md`: read access mode,
static validation, `EXPLAIN` plan inspection, resource limits. Do not simplify it, and do
not implement it before the read API is working. The denylist snippet in that document is
illustrative — the real implementation must tokenize, not substring-match.

---
