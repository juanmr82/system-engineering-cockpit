# CLAUDE.md — System Engineering Cockpit (SEC)

Guidance for Claude Code working in this repository. Read this fully before the first
edit of a session. If something here conflicts with a spec in `docs/`, the spec wins for
its own subject area (importer mechanics, graph schema, ad-hoc Cypher, a feature spec)
and this file wins for everything else.

---

## 1. What this is

**System Engineering Cockpit** is an internal web application that builds a single
navigable knowledge tree over the Systems Engineering artifacts of one project, pulled
out of several source tools and joined in one Neo4j graph:

| Source | What it contributes | Importer |
|---|---|---|
| IBM DOORS | Requirements, modules, hierarchy, traceability links | Python + `.bat`, **Windows 11 only** |
| PTC Windchill | Document metadata | Python |
| Cameo Systems Modeler | MBSE elements — SOI views, functions | Python |
| *(future)* | Test management, PLM, ... | must join on `SEItem`, nothing else |

The graph grows incrementally. **Every architectural decision must survive a new source
being added without touching the existing ones.** That constraint is the whole point of
the product; treat any design that hardcodes "DOORS" outside `importers/doors/` and the
DOORS-specific API routes as a bug.

**Deployment targets:** Red Hat Enterprise Linux **and** Windows 11. Both must work.
The DOORS importer runs only on Windows 11 (it needs a DOORS client); everything else is
cross-platform.

---

## 2. The rules that are never negotiable

> The numbers are stable identifiers referenced from `docs/` and from code comments, so
> they are never renumbered. There is no R4.

### R1 — `__` is the application namespace, and it has two tiers

**Every property name and relationship type beginning with `__` is ours, not the source
tool's.** No source system emits `__`-prefixed names, which is exactly what makes the
namespace safe to reserve. Everything *without* the prefix is verbatim source data and is
never modified, reformatted, or normalised by the application.

Inside the namespace there are two tiers, and which tier a thing belongs to decides what
shape it is allowed to take:

**Tier 1 — derived at import.** Deterministic functions of the source export, written by
the importer: `__id`, `__name`, `__version`, `__sortKey`, `__moduleUrl`, `__objectUrl`,
and the `__child` hierarchy relationship. These exist because the source gives us
structure only implicitly — DOORS hands over an outline number that does not sort as a
string and a parent/child relationship that is encoded in that number rather than stated.
Tier 1 makes that structure explicit and queryable.

Tier 1 **may** be node properties or relationships, because it is *regenerable*: delete
it, re-run the import over the same file, and you get byte-identical results.

**Tier 2 — knowledge the import cannot produce.** Review status, tags, notes,
assignments, ratings, classifications, hand-drawn links — anything a user or the
application decides. **Never a property.** Always a separate node, reached by a
`__`-prefixed relationship.

> The test is a single question: **could a fresh import of the same source file reproduce
> this?** Yes → Tier 1. No → Tier 2. Nothing sits between them.

**The application is read-only on imported data.** It never writes a property or a label
on a node an importer created — not source data, not Tier 1. The importers do write those
nodes, on every run, with `MERGE … SET n += props`; that asymmetry is precisely why
application data cannot live on them. Anything the app stored there would be silently
overwritten by the next import. Tier 2 exists to survive that.

### R2 — Tier 2 attaches as meta-relationships, never as properties

Everything the *application* knows that the source system does not — review status,
tags, comments, ratings, assignments, classifications, links the user drew by hand — is
modelled as a separate node reached by a relationship whose **type starts with `__`**.

```cypher
(:SEItem)-[:__reviewOf]->(:__Meta:__Review { ... })
(:SEItem)-[:__taggedAs]->(:__Meta:__Tag   { ... })
(:SEItem)-[:__noteOn]->(:__Meta:__Note    { ... })
```

Contract for every meta node:

| Property | Type | Meaning |
|---|---|---|
| `__metaId` | `string` (UUID v7) | unique key, owns the uniqueness constraint on `:__Meta` |
| `__metaKind` | `string` | closed enum, mirrors the second label — catalogue below |
| `__schemaVersion` | `integer` | payload generation, set on every node from day one |
| `__createdBy` / `__createdAt` | `string` / ISO-8601 UTC string | audit |
| `__updatedBy` / `__updatedAt` | `string` / ISO-8601 UTC string | audit |
| *(payload)* | | kind-specific, **without** `__` prefix |

Rules that follow from this and must be enforced in code and in review:

- **Tier 1 vs Tier 2 is decided by the node label `:__Meta`, not by the `__` prefix** —
  both tiers carry the prefix. `__child` is a Tier-1 relationship between two `:SEItem`
  nodes; `__reviewOf` is a Tier-2 relationship whose far end carries `:__Meta`. When you
  need to ask "is this ours to delete", ask about the label.
- A meta node is never the target of `refersTo` or `__child`, and never carries `:SEItem`.
  Tier 2 lives strictly at the edges of the imported graph, hanging off it.
- **The anchor is not always a single item.** A comment attaches to one requirement, but
  "this attribute is mandatory for this module" attaches to a `:DOORSModule` *and* names a
  DOORS attribute in its payload. Model the anchor explicitly rather than assuming
  item-scoped annotation — this shape appears as soon as the review views arrive, and
  widening it later means migrating live user data.
- **A re-import must not disturb Tier 2.** The importers `MERGE` on `__id` and
  `SET n += props`, which leaves relationships alone. Verify this holds after any
  importer change — a test asserting "meta survives a second import run" is mandatory.
- **Deleting all app data must be one query**, and it must be safe to run:
  `MATCH (m:__Meta) DETACH DELETE m`. If that query would ever destroy imported data,
  the model has drifted.
- The API layer's write endpoints touch `:__Meta` and its `__`-prefixed relationships
  **and nothing else**. Enforce this in one place (a guarded write helper), not per route.
- **Every feature that writes Tier 2 carries a regression test asserting the anchor node's
  property map is byte-identical before and after the write.** This is how R1's read-only
  guarantee stays true as views multiply.

#### The `__metaKind` catalogue — closed enum, three anchor shapes

Kinds are not a flat list. They come in three shapes, and the shape decides the schema.
Adding a kind means deciding its shape first.

**Shape A — annotation on one item.** `(:SEItem)-[:__x]->(:__Meta:__X)`

| `__metaKind` | Label | Relationship | Payload |
|---|---|---|---|
| `note` | `:__Note` | `__noteOn` | `text`, optional `replyTo` for threading |
| `tag` | `:__Tag` | `__taggedAs` | `namespace`, `value` — e.g. `domain:thermal` |
| `review` | `:__Review` | `__reviewOf` | `campaign`, `verdict`, `rationale` |
| `flag` | `:__Flag` | `__flagOn` | `severity`, `reason` |
| `classification` | `:__Classification` | `__classifiedAs` | `scheme`, `code` |

`flag` and `review` stay distinct deliberately. A flag is **data quality** — garbled text,
a dangling link, an empty `Absolute Number`. A review is a **verdict in a process**.
Different authors, different lifecycles, different views. Do not merge them.

A `classification` places an item on one axis of a controlled vocabulary. `scheme` names
the axis so future axes (criticality, discipline, domain) need no new label; `code` is
validated against a closed enum at the API boundary. **The display label is never stored** —
"L2 – Segment" is resolved from the alias map (R5), so the wording stays changeable. One
classification per `(item, scheme)`, enforced by the write query, since Community cannot
constrain it. First use: the system level on a `:DOORSModule`, set from the Modules
settings dialog.

**Shape B — a rule scoped to a set, not an item.**
`(:DOORSModule)-[:__policyFor]->(:__Meta:__Policy)`

| `__metaKind` | Label | Relationship | Payload |
|---|---|---|---|
| `policy` | `:__Policy` | `__policyFor` | `attributeName`, `rule` (`mandatory` / `forbidden` / `pattern`), `appliesToLabels` |
| `attributeSetting` | `:__AttributeSetting` | `__attributeSettingFor` | `attributeName`, `visible` (bool), `verification` (bool) |

This is the "which attributes are mandatory" case. One node governs every object in the
module. **Never model this per item** — 984 nodes that still cannot answer "what is the
rule" is the failure mode.

`attributeSetting` is deliberately **not** folded into `:__Policy` (`docs/REQ_REVIEW.md` §9.2).
A policy models a *rule about a value* — `mandatory` / `forbidden` / `pattern`, scoped by
`appliesToLabels`. `visible` and `verification` are *roles for an attribute*: no value
semantics, no label scope. Widening `rule` to carry them would make
`attribute-policy-checks.md` mean two things at once. One node per `(module, attributeName)`,
enforced by the write query since Community cannot constrain it.

`appliesToLabels` is **always stored, never implied**. A policy that applies to everything
is a policy nobody can reason about, and a default living in query code is a default that
drifts between call sites. Mandatory-attribute policies default to `['DOORSRequirement']`
— headings, information objects and table structure are not requirements and are never
checked. See `docs/features/attribute-policy-checks.md`.

**Shape C — a reified user-drawn link.**
`(:__Meta:__Link)-[:__linkFrom]->(:SEItem)` and `-[:__linkTo]->(:SEItem)`

| `__metaKind` | Label | Relationships | Payload |
|---|---|---|---|
| `link` | `:__Link` | `__linkFrom`, `__linkTo` | `semantics` (`satisfies` / `verifies` / `refines` / `references`), `rationale` |

This is the most valuable kind and the easiest to under-build. DOORS gives untyped
`refersTo` **within its own world and nothing at all across tools** — a requirement to a
Cameo function, a document to a module. Those links are the connective tissue that makes
this a knowledge tree rather than three catalogues sharing a database. Reify rather than
using a direct relationship, so the link carries author, rationale, and the link semantics
the DXL discards — and so it is still removed by the single `:__Meta` delete query.

**Explicitly not `:__Meta`:**

- **Anything derivable** — counts, coverage percentages, statistics, policy-check results.
  Computed on read, never stored. Stored derivations go stale silently.
- **Saved queries, saved filters, view layouts.** These anchor to a *user*, not to an
  `:SEItem`, so forcing them into `:__Meta` breaks the invariant that every meta node
  hangs off the imported graph. Give them their own label.

**Two invariants on every meta node:**

- `__metaKind` is validated against the closed enum at the API boundary. An unknown kind
  is a `400`, never a silently accepted node — the same discipline as the closed
  `Object Type` set in the importer spec.
- `__schemaVersion` (integer) from the very first node written. Tier 2 is the **only data
  in the system that cannot be fixed by re-importing**, so the day a payload shape changes
  you must be able to tell which generation you are reading.

### R3 — Tier 1 is source-agnostic: one tree, one set of derived relationships

Windchill documents and Cameo elements need exactly the same treatment DOORS objects get:
their own hierarchy made explicit, their own document order made sortable. They reuse the
**same** Tier-1 vocabulary.

- `__child` is *the* hierarchy relationship for every source. Do not invent
  `__windchillChild`, `__cameoContains`, or `__folderOf`. A single relationship type is
  what lets one tree component walk Requirements, Documents and MBSE elements without
  knowing which is which.
- `__sortKey` is *the* document-order key for every source. Its derivation is
  source-specific (DOORS zero-pads segments of `objectNumber`; another source will do
  something else), but the contract is identical: **a plain string sort on `__sortKey`
  reproduces the source tool's own display order.** That contract, not the algorithm, is
  what the frontend depends on.
- Source-native relationships keep their own names without the prefix — DOORS traceability
  links are `refersTo` because DOORS actually asserts them. If a source asserts a
  containment relationship explicitly, still project it into `__child` so the tree works,
  and keep the native one alongside it if it carries information `__child` loses.
- When adding a source, the new derivation goes in that source's importer package as a
  pure function. `derive_sort_key` gets a new implementation; `__sortKey` does not get a
  new name.

### R5 — the `__` namespace is internal and never reaches the user

The prefix is a machine convention. **A user never sees a `__`-prefixed name** — not as a
column header, field label, form control, tooltip, error message, CSV/Excel export header,
or URL segment.

Distinguish name from value: `__name`'s *value* is exactly what we display, because that
is content. The string `"__name"` is not. Same for `__version`, `__typeRaw`, and the
payload on a meta node.

- **One alias map, server-side, single source of truth** (`domain/Aliases.kt`). API DTOs
  are built from it. The frontend never carries a second copy and never string-manipulates
  a property name to make it presentable.
- **The runtime attribute-discovery query must filter the namespace out** before results
  reach the UI. `UNWIND keys(n) AS k ... WHERE NOT k STARTS WITH '__'` — otherwise every
  requirements table sprouts a `__sortKey` column the moment a new module is imported.
- **Routes carry an opaque handle, not `__id`.** A DOORS resource URL in the address bar
  is the namespace leaking through the front door. Base64url-encode `__id` for the route
  parameter: opaque to the user, reversible without server state, still shareable.
  Path shape: `/requirements/item/:ref`.
- **`__` data is used freely and heavily internally** — tree building, default ordering,
  joins across sources, dedup, cache keys, validation, ETags. That is what it is for. The
  rule is about exposure, not about restraint in using it.
- **Errors and empty states get human sentences.** "Referenced object has not been
  imported yet" — never "`__UNDEFINED`". Labels are internal state; the UI maps them to
  language.
- **One deliberate exception: the ad-hoc Cypher console.** A user writing their own Cypher
  is addressing the real graph and must see real names. Document the namespace and the
  alias map *in that view*, and nowhere else.

Reference alias map — extend it here when you add a field, do not invent aliases locally:

| Internal | Shown as |
|---|---|
| `__name` | **Name** (contextually *Title* for documents, *Element* for MBSE) |
| `__version` | **Baseline** — `"current"` renders as *Current* |
| `__id`, `__objectUrl` | never shown; opaque `:ref` in routes |
| `__sortKey` | never shown; silently drives default sort order |
| `__child` | never shown; silently drives the tree |
| `__moduleUrl` | rendered as the parent module's `__name`, as a link |
| `__typeRaw` | **Type** — preferred over the label chip when present |
| `:__UNDEFINED` | the *Not yet imported* state, with the owning module named |
| `__tableObject`, `__tableRowIndex`, `__tableColumnIndex` | never shown; drive table layout |
| `refersTo` | **References** (outgoing) |
| `:__Meta` kinds | **Review**, **Tag**, **Note**, **Flag**, **Rule**, **Link**, **Classification**, **Attribute setting** |
| `:__Note` in the review table | **Comment** — one per object, never a thread |
| `__noteOn` | never shown; the comment's attachment |
| `:__AttributeSetting` `visible` | **Shown in table** |
| `:__AttributeSetting` `verification` | **Verification attribute** |
| `__attributeSettingFor` | never shown |
| `__schemaVersion`, `__metaKind` | never shown |
| `__metaId`, `__metaKind` | never shown |
| `__createdBy` / `__createdAt` | **Added by** / **Added on** |

Controlled vocabularies and source-native field labels also live in `Aliases.kt`:

| Stored | Shown as |
|---|---|
| `:__Classification` + `scheme: systemLevel` | **System level** |
| `code: L0` / `L1` / `L2` / `L3` / `L4` | L0 – Customer / L1 – System of Systems / L2 – Segment / L3 – Subsystem / L4 – Component |
| `:__Policy` + `rule: mandatory` | **Mandatory attribute** |
| `description` | Description |
| `moduleFullPath` | Path |
| `prefix` | Object ID prefix |
| `created_By` / `created_On` | Created by / Created on |
| `last_Modified_By` / `last_Modified_On` | Last modified by / Last modified |
| `_ModuleType` | Module type |
| `wordDocBaseline`, `wordDocCaptionLevel`, `wordDocIssue`, `wordDocNumber`, `wordDocTitle` | Word export baseline / caption level / issue / number / title |

Note `_ModuleType` carries a **single** leading underscore — it is source data and is
displayed, unlike `__`-prefixed names.

### R6 — `__id` is application identity, not source identity

Every node in the database has `__id`, `__name`, `__version`. `__id` is globally unique
and is what the frontend uses as a route parameter and list key. A source system's own
identifier (DOORS `id`, a Windchill number, a Cameo element name) is **module-local or
tool-local and must never be used as a key**. See `docs/SE_ITEM_SCHEMA.md`.

### R7 — saving is local to the thing being edited

Every dialog and every editable table commits its own changes. **There is no global save
button, no staging layer, no pending-changes queue and no cross-view dirty state.** One
user gesture, one request, one server-side transaction. A user who presses Save has
written to the graph before the dialog closes; a user who navigates away without pressing
Save has written nothing.

Consequences:

- Dirty state is local to the open dialog, or to one editable table inside one view, and dies
  with it. No shared store, no cross-view state.
- A view that owns an editable table guards its own exit: changing module, changing route or
  closing the tab with pending edits asks first. This is the only place a guard exists, and it
  is scoped to the view that owns the buffer — never a router-wide guard reading a global
  store. (Amended for the batch comment save in `docs/REQ_REVIEW.md` §9.1: a table with pending
  comments *can* be navigated away from, which the original wording assumed impossible.)
- A save that spans two tabs of one dialog is still **one** request and one transaction.
- On failure, the dialog stays open with the user's input intact and shows the error
  inline. Never close a dialog on a failed write; without a staging layer there is no
  queue to recover from.

This is a UI rule only. The backend keeps its single guarded meta write path (§5) —
removing client-side staging must not produce a second server-side way to write `:__Meta`.

### Where a given piece of state lives

Four stores, and the boundaries are not negotiable. When you need to persist something
new, place it here **before** writing code.

| Kind of state | Lives in | Written by | Example |
|---|---|---|---|
| Source data | Neo4j, un-prefixed properties + native relationships | importers only | `Object Text`, `refersTo` |
| Derived structure (Tier 1) | Neo4j, `__`-prefixed | importers only | `__child`, `__sortKey`, `__id` |
| **Business annotation (Tier 2)** | Neo4j, `:__Meta` nodes | **the API, on an explicit save in the dialog or table that owns the data** | comments, mandatory-attribute rules, system level, review status |
| Application configuration | **backend config file**, git-versioned | a developer, at release | sidenav structure and order, feature flags |
| Per-user UI preference | browser, client-side | the browser | sidenav collapsed, column widths, last route |

The line between the last three is the one that gets blurred. Test it with two questions:
*does a user change it during normal work?* → Tier 2. *Is it the same for everyone and
changed only when we ship?* → config. *Does it matter only to one person on one machine?*
→ browser.

Navigation structure is **configuration, not data.** It is the same for every user, it
changes when a release adds a source, and putting it in the graph would mean an
admin-gated write path, an exposure question, and a schema — for something a code review
already handles better. It stays in `backend/src/main/resources/application.yaml`,
served read-only at `GET /api/v1/config/navigation`.

The one exception to "no browser storage" in §11 is this table's last row. That rule
exists to stop graph data being cached client-side; a boolean about a drawer is not graph
data.

---

## 3. Repository layout

One IntelliJ project, one Gradle multi-project build at the root. The frontend is an npm
workspace that Gradle can drive but that developers normally run directly.

```
system-engineering-cockpit/
├── CLAUDE.md                     ← this file
├── settings.gradle.kts           ← IntelliJ opens this
├── build.gradle.kts
├── gradle/
│   └── libs.versions.toml        ← single source of truth for JVM dependency versions
├── backend/                      ← Ktor service
│   ├── build.gradle.kts
│   └── src/{main,test}/kotlin/com/sec/...
├── frontend/                     ← Angular workspace
│   ├── package.json
│   ├── angular.json
│   └── src/app/...
├── importers/
│   ├── pyproject.toml            ← one Python project, several entry points
│   ├── src/sec_import/
│   │   ├── core/                 ← graph writer, identity, config, reporting (shared)
│   │   ├── doors/
│   │   ├── windchill/
│   │   └── cameo/
│   ├── win/                      ← .bat wrappers, Windows-only, thin
│   └── tests/
├── docs/
│   ├── SE_ITEM_SCHEMA.md
│   ├── DOORS_TO_NEO4J_IMPORTER_SPEC.md
│   ├── CYPHER_API_DESIGN.md
│   ├── features/                 ← one spec per dynamic-content view
│   │   ├── requirements-modules.md
│   │   └── attribute-policy-checks.md
│   └── adr/                      ← one short ADR per non-obvious decision
├── deploy/
│   ├── docker-compose.dev.yml    ← Neo4j Community for local dev
│   └── ...
└── .run/                         ← IntelliJ run configurations, committed
```

### Cross-platform hygiene — check this every time you add a file

- `.gitattributes`: `* text=auto eol=lf`, `*.bat text eol=crlf`, `*.ps1 text eol=crlf`.
- Never hardcode `/` or `\` in a path. Kotlin: `Path`. Python: `pathlib.Path`. Angular
  build config: forward slashes only, they are POSIX-normalised.
- No `bash`-only steps in Gradle tasks or npm scripts. Anything shell-shaped goes in a
  Kotlin/Python entry point invoked identically on both platforms.
- Windows-only code is confined to `importers/win/` and `importers/src/sec_import/doors/`.
  If Windows-only logic appears anywhere else, that is a defect.
- File encoding is UTF-8 everywhere, declared explicitly on every read/write. DOORS
  attribute names contain umlauts and the default Windows codepage will corrupt them.

---

## 4. Technology and versions

Pin these in `gradle/libs.versions.toml` and `package.json`. Do not float versions.

| Component | Version | Notes |
|---|---|---|
| Neo4j | **Community 2026.x** | CalVer. Requires **JDK 21+**. Community has no RBAC, no property-existence constraints, no query governor — see §7. |
| Neo4j Java driver | **6.x** | matches the 2026 server series |
| JVM | **21 (LTS)** | toolchain pinned in Gradle, not inherited from `JAVA_HOME` |
| Kotlin | **2.4.x** | |
| Ktor | **3.5.x** | latest stable at time of writing (3.5.1, June 2026). Netty engine. |
| Gradle | latest 9.x wrapper | `./gradlew` / `gradlew.bat`, both committed |
| Angular | **22** (released 3 June 2026) | |
| Angular Material | **22** | matched major to Angular |
| Node | **22+** | required by Angular 22 |
| TypeScript | **6.x** | required by Angular 22 |
| Python | **3.11+** | importers |

Frontend quality gate — these exist so `npm run lint` and `npm test` are real (§11):

| Component | Version | Notes |
|---|---|---|
| ESLint | **10.x** | flat config, `frontend/eslint.config.mjs` |
| angular-eslint | **22.x** | matched major to Angular; supplies the `lint` builder and the template rules |
| typescript-eslint | **8.x** | peer requirement of angular-eslint 22 |
| jsdom | **30.x** | the DOM for `@angular/build:unit-test`; without it `ng test` refuses to start |

There is **no static analysis on the backend yet** (ktlint/detekt). It needs its own decision —
`explicitApi()` already carries some of the weight, and adding a formatter is a separate call.

Before adding *any* dependency not in these tables, check whether the platform already
provides it. Prefer fewer libraries over convenience wrappers.

---

## 5. Backend — Ktor

### Structure

```
com.sec
├── Application.kt              ← module wiring only, no logic
├── config/                     ← typed config from application.yaml + env
├── graph/
│   ├── GraphDriver.kt          ← single Driver instance, app-lifecycle scoped
│   ├── Read.kt / Write.kt      ← the ONLY places a session is opened
│   └── cypher/                 ← Cypher as named constants, one file per domain
├── domain/                     ← SEItem, meta model, source-agnostic
├── source/                     ← per-source projections (doors/, windchill/, cameo/)
├── meta/                       ← R2 write path, guarded
├── domain/Aliases.kt           ← R5 alias map, single source of truth
├── meta/MetaSchema.kt          ← :__Meta constraints/indexes, applied at startup (§7)
├── api/
│   ├── Routes.kt               ← table of contents: registers the files below, nothing else
│   ├── routes/                 ← one file per feature — ModuleRoutes.kt, ConfigRoutes.kt, ...
│   ├── ProblemPages.kt         ← StatusPages → RFC 9457, the only error-to-wire mapping
│   └── dto/                    ← @Serializable wire types
└── security/                   ← auth, the ad-hoc Cypher guard
```

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
GET  /api/v1/items/{ref}/annotations    ← Tier-2 data attached to an item
POST /api/v1/items/{ref}/annotations    ← R2 write path
PATCH/DELETE /api/v1/annotations/{ref}
GET  /api/v1/modules                    ← DOORS-specific projection
GET  /api/v1/modules/{ref}              ← module detail for the settings dialog
GET  /api/v1/modules/{ref}/objects      ← review table rows, document order, paged + capped
POST /api/v1/modules/{ref}/settings     ← system level + mandatory diff + attribute flags, one txn
POST /api/v1/modules/{ref}/comments     ← every dirty comment for one module, one txn
GET  /api/v1/modules/{ref}/attributes   ← runtime attribute discovery, namespace filtered
GET  /api/v1/modules/{ref}/checks/attribute-policy
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

## 6. Frontend — Angular 22 + Material

### Angular 22 idioms — this codebase is signal-first

- **Standalone components only.** No `NgModule`, ever.
- **`inject()`**, not constructor parameter injection.
- **Signals for all state.** `input()`, `output()`, `model()`, `computed()`, `linkedSignal()`.
- **`httpResource()` / `resource()`** for server data. Not bare `HttpClient` subscriptions,
  not a hand-rolled loading/error/data triple.
- **Signal Forms** (stable in v22) for anything with user input. No `FormGroup`/`FormControl`.
- **Built-in control flow** `@if` / `@for` / `@switch` / `@defer`. `*ngIf` and `*ngFor` are
  forbidden in new templates.
- **`OnPush` is the v22 default — do not declare `changeDetection` at all.** If you ever
  genuinely need the old behaviour it is now `ChangeDetectionStrategy.Eager`, and it needs
  a comment justifying it.
- **Zoneless.** No `zone.js`, no `NgZone` injection, no `setTimeout` to "make change
  detection run".
- **`@angular/build` application builder.** Webpack builders are deprecated in v22 — never
  add `@angular-devkit/build-angular`.
- **Angular Aria** (stable in v22) for menus, listboxes and disclosure widgets that
  Material does not cover.
- Lazy-load every feature route with `loadComponent`.
- Client-side rendering only. This is an internal tool behind auth; do not add SSR.
- Tests use the workspace's scaffolded runner (Vitest via `@angular/build`). Do not
  re-introduce Karma or Jasmine.

### Folder layout

```
frontend/src/app/
├── app.config.ts               ← providers: router, httpClient(withFetch), material
├── app.routes.ts
├── core/                       ← singletons: api client, auth, error handling
├── shared/                     ← reusable dumb components, pipes, directives
├── layout/
│   ├── shell/                  ← the skeleton: toolbar + sidenav + router outlet
│   ├── sidenav/
│   └── toolbar/
├── features/
│   ├── requirements/{statistics,modules,review}/
│   ├── documents/windchill/
│   └── mbse/{soi-views,functions}/
└── styles/                     ← theme, tokens, typography
```

### Component file layout — the project standard

**A component is three files: `name.ts`, `name.html`, `name.scss`.** Wire them with
`templateUrl` and `styleUrl` (singular — `styles:`/`styleUrls:` are not used anywhere).

- **No inline `styles:` block, ever.** A component's CSS goes in its `.scss` file. This is
  what keeps a `.ts` file readable as logic and lets the stylesheet be edited, searched and
  reviewed as a stylesheet.
- **Templates move out too**, with one exception: a component whose entire template is a
  single element (`layout/sidenav/logo.ts`) may keep it inline, because a separate file for
  one tag adds noise, not clarity. It still gets its `.scss` file.
- **No `.component.ts` / `.service.ts` suffix**, matching the Angular v20+ style guide and
  the existing files: the class is `Modules`, the file is `modules.ts`. Feature specs in
  `docs/features/` written before this rule use the old `*.component.ts` spelling; the file
  *split* they ask for is the binding part, the suffix is not.

### Shared styles — `src/styles/_mixins.scss`

`src/styles` is on the Sass load path (`stylePreprocessorOptions.includePaths` in
`angular.json`), so any component imports shared patterns without `../../..` climbing:

```scss
@use 'mixins' as sec;

.sec-modules { @include sec.page-shell; }
.sec-modules__table-scroll { @include sec.scroll-panel; }
table { @include sec.data-table; }
```

- **Recurring UI patterns are mixins, not global utility classes.** Component styles stay
  scoped and semantically named, while the values that must not drift — table density, the
  Tier-2 accent, the bounded scroll container `position: sticky` depends on — live in one
  place. Add to `_mixins.scss` the second time a pattern appears; do not copy it.
- **Colour tokens are never `@use`d.** `_tokens.scss` emits the `--sec-*` custom properties
  once, globally, from `styles.scss`. Components reference `var(--sec-blue)` and never
  redeclare a token or hardcode a hex.
- **Material is adjusted only through M3 token overrides**, all of them in `_theme.scss`
  (`mat.table-overrides`, `mat.dialog-overrides`, …). No `::ng-deep`, and no rule targeting a
  `.mat-mdc-*` or `.mdc-*` class — those are internals and they move between minor versions.
  Styling `th`, `tr` or `table` from a component's own stylesheet is fine: that is the
  template's own markup.
- `_document.scss` holds the requirement-tree vocabulary (depth rails, object cards,
  verification and extended-attribute panels) from `docs/proposed_new_style.md`. Nothing
  includes it yet — it is the specified look for the review and tree views, kept in tokens so
  it does not rot in an untracked stylesheet. Mixins emit nothing until included, so it costs
  no bytes.
- Changing `angular.json` requires a **dev-server restart** — it is build configuration, not
  watched source, and a running `ng serve` will silently keep the old Sass load path.

### Dialogs

A dialog owns its own presentation. Give it a **static `open()`** and let call sites pass
data only, so no caller can size it wrongly or forget the modal contract:

```ts
static open(dialog: MatDialog, data: ModuleSettingsDialogData) {
  return dialog.open<ModuleSettingsDialog, ModuleSettingsDialogData, boolean>(
    ModuleSettingsDialog,
    { ...SEC_MODAL_DIALOG, width: '760px', height: '620px', data },
  );
}
```

`SEC_MODAL_DIALOG` (`shared/dialog/modal-dialog.config.ts`) carries the R7 contract —
`disableClose`, `autoFocus`, `restoreFocus`. Spread it into every dialog; never re-declare
`disableClose` per call site and never set it to `false`. The three type arguments to
`open<T, D, R>` are what make `afterClosed()` return a typed result instead of `any`.

### Icons — `core/icons/sec-icons.ts`

Custom icons are real `.svg` assets in `public/icons/`, registered once by
`provideSecIcons()` and used as `<mat-icon svgIcon="gearbox" />`. Add an icon by dropping the
file in and adding one line to the `SEC_ICONS` map — never paste an SVG path into a
component.

Paths in that map are **root-absolute** (`/icons/x.svg`): a relative path resolves against
the current route and 404s on anything deeper than the root.

This deliberately avoids the Material icon *font*, which §8 requires be self-hosted (no
Google Fonts CDN, GDPR) and which is not shipped yet — a ligature such as
`<mat-icon>settings</mat-icon>` renders as the raw text "settings" until it is.

### Material pitfalls already paid for

- **Sticky table headers inside `mat-tab-group`.** Tabs measure lazily; a sticky header
  rendered while its tab was hidden gets wrong offsets. Set `[preserveContent]="true"` and
  call `table.updateStickyHeaderRowStyles()` on `(selectedTabChange)` for the newly shown
  table.
- **`position: sticky` needs a bounded scroll container.** Give the panel a concrete
  `height`/`max-height` in SCSS — `flex: 1` alone is not enough — and put `overflow: auto`
  on the wrapper, not on the table.
- **Modal dialogs are not movable or minimisable, by default and by intent.** Do not add
  `cdkDrag`. `disableClose: true` plus explicit Save/Cancel is the shape (R7).

---

## 7. Neo4j Community — what it will not do for you

Read this before designing anything that assumes a normal database.

- **No role-based access control.** There is exactly one credential: the service account.
  User identity, authorization and read-only enforcement live entirely in the Ktor layer.
- **No property-existence or key constraints.** "Every node has `__name`" cannot be
  enforced by the database. The importer guarantees it; a validation query re-checks it;
  the frontend still treats it defensively.
- **Uniqueness constraints are available** and are what we use:
  `:SEItem(__id)`, `:DOORSObject(__objectUrl)`, `:__Meta(__metaId)`.
- **No query governor.** A single unbounded query can exhaust the instance. Every read
  path needs a `LIMIT` and a transaction `timeout`.
- **Single user database (`neo4j`).** Multi-tenancy, if ever needed, is labels or
  properties — never separate databases.
- Do not create an index on a property that already has a uniqueness constraint; the
  constraint creates a backing range index and a duplicate index errors.

### Indexes — label-property indexes are per-label

This one is not obvious and costs real performance. The importer creates
`FOR (n:DOORSObject) ON (n.__moduleUrl)`, but the planner will **not** use it for
`MATCH (r:DOORSRequirement {__moduleUrl: $u})` — it has no knowledge that every
`DOORSRequirement` is also a `DOORSObject`. Without a dedicated index that pattern degrades
to a label scan of every requirement in the database.

**Any query that filters a *type* label by `__moduleUrl` needs its own index.**

```cypher
CYPHER 25
CREATE INDEX doors_requirement_module IF NOT EXISTS
FOR (n:DOORSRequirement) ON (n.__moduleUrl);

CYPHER 25
CREATE INDEX meta_policy_attribute IF NOT EXISTS
FOR (p:__Policy) ON (p.attributeName);
```

`doors_requirement_module` belongs in the **importer's** schema phase
(`DOORS_TO_NEO4J_IMPORTER_SPEC.md` §7.3) alongside the other imported-label indexes — it
must exist even if the backend has never started. `meta_policy_attribute` belongs in the
backend's meta-schema migration and serves the inverse question, "which modules mark this
attribute mandatory".

Do **not** index attribute *values*. Attribute names differ per module (78 in the reference
module), so value indexes would mean dozens of indexes per module, created dynamically from
user data, on properties whose names contain spaces and umlauts.

---

## 8. Visual design — Airbus house style

The brief is to match `airbus.com`. Follow it exactly; this is not an axis to be creative on.

### Colour

| Token | Hex | Use |
|---|---|---|
| `--sec-blue` | `#00205B` | **Airbus blue.** Primary. Toolbar, sidenav, headings, primary actions. |
| `--sec-blue-deep` | `#005670` | hover/pressed on dark surfaces |
| `--sec-blue-mid` | `#0085AD` | secondary emphasis |
| `--sec-blue-light` | `#48A9C5` | selected states, chart series |
| `--sec-blue-pale` | `#74D2E7` | tints, hover backgrounds |
| `--sec-grey-blue` | `#8DB9CA` | disabled, dividers on blue |

Highlight colours (`#009F4D` green, `#84BD00` pistachio, `#EFDF00` yellow, `#FE5000`
orange, `#E4002B` red, `#DA1884`, `#A51890`, `#0077C8`, `#008EAA`) are **accents, and the
brand rule is one highlight per view**. Reserve them semantically and document the mapping
once in `styles/_tokens.scss`:

- `#009F4D` — verified / imported cleanly
- `#EFDF00` — TBD / unclassified (`DOORSTBD` without `__typeRaw`)
- `#FE5000` — unresolved placeholder (`__UNDEFINED`)
- `#E4002B` — import error, dangling link
- `#0077C8` — Tier-2 application data (R2), so a user annotation is instantly
  distinguishable from imported truth. This mapping matters: **a user must never mistake
  something the app added for something DOORS said.**

### Surfaces and neutrals — the paper style

The product is styled as **paper on a desk**, specified in `docs/proposed_new_style.md` and
implemented in `styles/_tokens.scss`. Content sits on white sheets with hairline edges over a
light blue-grey shell. Four rules generate the whole look, and a new pattern is derived from
them rather than invented:

1. **Separate with a hairline, not a shadow or a fill.** `--sec-line` at sheet and table edges,
   `--sec-line-soft` inside them. There is exactly one shadow token, for the sticky bar.
2. **Squared corners** — `--sec-radius` (2px) on a control, `--sec-radius-sheet` (3px) on a
   sheet. Never a pill; M3 defaults to pills and is overridden per component in `_theme.scss`.
3. **Colour is a rail or a rule, never a background** — the 3px navy top rule on a lead sheet,
   the depth rail down a card, the left rule on an accent panel. Two deliberate exceptions: the
   navy application toolbar, and the filled Tier-2 chip, because that distinction must never
   need a second look.
4. **Non-content text is 10px uppercase, letter-spaced, in `--sec-ink-3`** — column headers,
   field labels, the view eyebrow.

The neutral ramp is **cooled towards the blue** rather than taken from percentages of black:
against Airbus blue a pure-grey ramp reads as dirty, and white sheets on a neutral grey shell
read as holes rather than as paper. This is the one deliberate departure from the "use
percentages of black" guidance, and it is why the ramp is a closed set of tokens
(`--sec-shell`, `--sec-paper`, `--sec-wash`, `--sec-line`, `--sec-ink`…) — extend it in
`_tokens.scss` or not at all. Never hardcode a hex in a component.

Sizes, tracking and geometry are tokens too (`--sec-text-*`, `--sec-tracking-*`,
`--sec-radius*`). A component that needs a value not in the scale is a signal the scale is
wrong, not that the component is special.

### Typography

- **Inter** for all web UI — this is the Airbus web typeface.
- **Self-host it.** Airbus's own guidance requires self-hosted integration for GDPR
  compliance: no Google Fonts CDN, no `<link>` to `fonts.googleapis.com`. Ship woff2 in
  `frontend/public/fonts/` with `font-display: swap`.
- **Sentence case everywhere.** Capital only at sentence start.
- **Never italic.** Not for emphasis, not for captions, not for placeholder text.
- ALL CAPS only for headlines of three words or fewer, or where type is a design element.
- Left-align long copy.
- Use a tabular-figures variant (`font-variant-numeric: tabular-nums`) for all counts,
  IDs and object numbers — requirement tables read as data, not prose.

### Material theming

Angular Material's M3 palettes do not include Airbus blue. Generate a custom palette from
the seed rather than eyeballing one:

```
ng generate @angular/material:theme-color
# seed: #00205B ; secondary: #0085AD ; tertiary: #009F4D
```

Commit the generated `_theme-colors.scss` and apply it through `mat.theme()` in
`styles/_theme.scss`. **Do not override Material component internals with `::ng-deep`.**
Every visual adjustment goes through M3 system tokens or the component's own token
overrides (`mat.button-overrides(...)` and friends).

### Logo

Do **not** ship the Airbus logo or wordmark — it is a trademark with its own clear-space
and usage rules and this is not an Airbus-branded product. The sidenav logo block holds
the **System Engineering Cockpit** wordmark: set in Inter, sentence case, `#00205B` on
white, sized to the 64px sidenav header. Leave it as a single swappable
`layout/sidenav/logo.component.ts` so a real mark can drop in later.

### Density and motion

This is a data tool. Use Material's compact density (`-2`) for tables and lists,
default density for the toolbar and dialogs. Motion is functional only: sidenav
expand/collapse, menu open, route transition. No decorative animation. Respect
`prefers-reduced-motion` — this is part of the quality floor, not a nice-to-have.

---

## 9. The UI shell

**Status: built.** The current milestone is the first dynamic-content view,
**Requirements → Modules**, specified in `docs/features/requirements-modules.md`. Keep this
section as the contract the shell must continue to satisfy.

```
┌───────────────────────────────────────────────────────────────────┐
│ ░░░ toolbar (Airbus blue, fixed, 56px)                    [👤]    │
├──────────────────┬────────────────────────────────────────────────┤
│  LOGO            │                                                │
│  (64px header)   │                                                │
├──────────────────┤                                                │
│  Requirements    │                                                │
│   · Statistics   │            <router-outlet />                   │
│   · Modules      │            dynamic content                     │
│   · Req review   │                                                │
│                  │                                                │
│  Documents       │                                                │
│   · Windchill    │                                                │
│                  │                                                │
│  CAMEO           │                                                │
│   · SOI views    │                                                │
│   · Functions    │                                                │
└──────────────────┴────────────────────────────────────────────────┘
```

### Behaviour

- `mat-sidenav-container` filling the viewport; sidenav `mode="side"` and opened on
  desktop, `mode="over"` below 960px with a hamburger in the toolbar.
- Sidenav width 280px expanded. A collapse control rails it to 64px (icons only, labels
  as tooltips). Collapsed state is a **per-user browser preference** — client-side only,
  never the graph, never the backend.
- The three groups are **source families**, not arbitrary sections. The sidenav is
  rendered from a typed `NavGroup[]` fetched from `GET /api/v1/config/navigation` — never
  from hand-written markup, and never from the graph. Order is defined in the backend
  config file and is therefore identical for every user.
- **The backend config owns order; the frontend owns the items.** Config stores stable
  keys and their sequence; the route and component for each key are compiled in. A key
  present in code but absent from config falls back to its default position rather than
  disappearing — otherwise shipping a new view silently hides it until someone edits
  YAML.
- Ship a hardcoded default `NavGroup[]` in the frontend as the fallback when the config
  endpoint fails. A broken config file must not produce an app with no navigation.
- Group headers (Requirements, Documents, CAMEO) are the prominent level: `0.9375rem`,
  semibold, Airbus blue, on a faint tinted background (`color-mix(in srgb, var(--sec-blue)
  8%, white)` — stays in near-white territory, never a saturated fill). Not clickable
  accordions, unless the group grows past ~6 items.
- Sub-items are the quiet level: `0.8125rem` via the sidenav-scoped
  `--mat-list-list-item-label-text-size` override, one step down from the group header.
- Active route is marked with a 3px left rule in `--sec-blue-mid` plus a pale background —
  not a filled pill. M3 nav-list items default `--mat-list-active-indicator-shape` to
  `corner-full` (a pill); the sidenav overrides it to `0` so hover/focus/active states are
  square, matching this rule.
- **User icon** (right of toolbar): opens a `mat-menu` with display name, email, roles,
  connected graph/database name, and a sign-out item. It is the **only** toolbar action —
  there is no global save (R7).
- Every route is lazy (`loadComponent`) and renders a titled empty state naming what will
  live there. Empty states are an invitation to act, not an apology.
- Keyboard: visible focus rings on toolbar buttons and every nav item; the sidenav is a
  `<nav>` with a proper landmark label; skip-to-content link as the first focusable element.

### Routes

| Path | Component | Group |
|---|---|---|
| `/requirements/statistics` | `RequirementsStatisticsComponent` | Requirements |
| `/requirements/modules` | `ModulesComponent` | Requirements |
| `/requirements/review` | `RequirementReviewComponent` | Requirements |
| `/documents/windchill` | `WindchillDocumentsComponent` | Documents |
| `/mbse/soi-views` | `SoiViewsComponent` | CAMEO |
| `/mbse/functions` | `FunctionsComponent` | CAMEO |

`/` redirects to `/requirements/statistics`. Unknown paths render a not-found component
inside the shell, not a bare page.

---

## 10. Importers

- One Python package, `sec_import`, with a shared `core/` holding identity derivation,
  the batched graph writer, config and reporting. **Per-source packages contain only
  parsing and mapping.** If you write a second batched-`UNWIND` writer, you have
  duplicated `core/`.
- Derivation rules are **pure functions**, isolated and unit-tested, separate from driver
  code: `derive_id`, `derive_name`, `derive_labels`, `derive_parent`, `derive_sort_key`,
  and per-source helpers like `target_object_url`. A change to the identity scheme should
  touch `derive_id` and nothing else.
- **Every source implements the same Tier-1 derivation interface** (R3): identity triple,
  parent, sort key, labels. `core/` defines the protocol and the writer; the source
  package supplies the implementations. A new source is a new module implementing that
  protocol — not a new graph shape.
- Every importer is **idempotent**: `MERGE` on `__id`, and a second identical run creates
  zero nodes and zero relationships. This is the acceptance test, not a nice property.
- Every run writes a report (console summary + JSON) with counts and anomalies. Never
  silently swallow a malformed record.
- `--dry-run` performs parsing and derivation, writes the report, touches nothing.
- Batch 1 000–5 000 rows per transaction via `UNWIND $rows`, driver-side. Not `LOAD CSV`.
- The `.bat` files in `importers/win/` are **thin wrappers only** — resolve the Python
  interpreter, set encoding to UTF-8, call the module entry point, propagate the exit
  code. No business logic in batch, ever.
- The importers own the schema for imported labels: constraints and indexes on `:SEItem`,
  `:DOORSObject`, `:DOORSRequirement` are created in their schema phase, not by the
  backend. The backend owns only the `:__Meta` schema.
- DOORS specifics are fully described in `docs/DOORS_TO_NEO4J_IMPORTER_SPEC.md`, including
  seven known export defects that the importer must survive. Do not re-derive that design.
- **Exports sanitised for sharing outside the work environment blank every user
  attribute**, including `Object Type`, so every object imports as `DOORSTBD` and nothing
  carries a real type label. Real exports do not have this problem. Keep test fixtures
  realistic, or type-dependent tests silently assert nothing.

---

## 11. Working agreements

**Before writing code**

- If the task has a spec in `docs/features/`, read it first — it exists because the
  decisions in it were expensive.
- Check `gradle/libs.versions.toml` and `package.json` for the pinned version and use its
  current API, not a remembered one. Angular 22, Ktor 3.5 and Neo4j driver 6 all changed
  APIs recently.
- Search for an existing helper before adding one. This codebase should have exactly one
  graph read path, one graph write path, one meta write path, one HTTP client.

**While writing code**

- Kotlin: explicit API mode on, no `!!`, no unchecked casts, `Result`/sealed types over
  exceptions for expected failures.
- TypeScript: `strict` on, no `any`, no non-null assertions. DOORS attribute maps are
  `Record<string, unknown>` and get narrowed at the point of use.
- Never use a DOORS attribute name as an object key path, CSS class, or URL segment. They
  contain spaces, dots, slashes and umlauts. Treat them as display labels; access via a
  map.
- **No `__`-prefixed string ever appears in a template, a translation file, or an export
  header** (R5). If you need one for display, it is missing from `Aliases.kt` — add it
  there. **This is enforced**: `sec/no-internal-namespace`
  (`frontend/tools/eslint/sec-rules.mjs`) fails the build on a `__` name in a template, an
  inline `template:`, or any string literal. It tells an internal name from a BEM element by
  what precedes the underscores — `sec-modules__header` has a block name in front, `:__Meta`
  does not — so BEM class names are untouched. Comments are not checked; they are where these
  names *should* appear.
- `""` from DOORS means "attribute exists and is empty", not "absent". Render it as empty,
  do not fall back as if it were missing.
- Sort document-order lists by `__sortKey`, never by a source-native order field such as
  `objectNumber` — those do not sort correctly as strings, which is why `__sortKey` exists.

**Before saying you are done**

- `./gradlew check` passes, and from `frontend/`: `npm run lint && npm test && npm run build`.
  Run the npm commands **from the `frontend/` directory**, not with `npm --prefix frontend`
  from the root — `--prefix` also changes where `npm install` writes, and installing from the
  wrong directory silently creates `frontend/frontend/node_modules` and leaves `package.json`
  untouched.
- New graph behaviour has a Testcontainers test against a real Neo4j **Community** image.
  Never test against Enterprise; the constraint differences are the whole point.
- **Container tests are tagged `docker` and are not part of `check`.** Not every machine that
  builds this has Docker, and a gate that cannot pass locally is a gate that gets skipped —
  taking the tests that *could* have run with it. Run them with
  `./gradlew :backend:integrationTest`; CI must run both tasks. The image tag is pinned in
  `gradle/libs.versions.toml` (`neo4j-image`) and passed to the test as a system property, so it
  sits next to every other version rather than inside a test file.
- Any feature that writes Tier 2 has the byte-identical-anchor test from R2.
- Any decision that took real thought gets a short ADR in `docs/adr/`.
- Update this file if you changed something it describes.

**Do not, without asking**

- Add a dependency not in §4.
- Write a Tier-2 value as a node property, or invent a per-source alternative to
  `__child` / `__sortKey` (R1–R3).
- Write anything at all onto a node an importer created (R1).
- Surface a `__`-prefixed name to the user, or put a raw `__id` in a URL (R5).
- Add a global save button, a staging layer, or any cross-view dirty state (R7).
- Introduce a second persistence mechanism — no side database, no local cache of graph
  state, no browser storage of graph data.
- Store a derived value — counts, coverage, policy-check results — in the graph.
- Change the identity scheme, the label model, or the meta model.
- Make anything DOORS-specific outside `importers/.../doors/`, `source/doors/`, and the
  DOORS-specific API routes.