# CLAUDE.md — System Engineering Cockpit (SEC)

Guidance for Claude Code working in this repository. Read this fully before the first
edit of a session. If something here conflicts with a spec in `docs/`, the spec wins for
its own subject area (importer mechanics, graph schema, ad-hoc Cypher) and this file
wins for everything else.

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

## 2. The six rules that are never negotiable

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
assignments, ratings, hand-drawn links — anything a user or the application decides.
**Never a property.** Always a separate node, reached by a `__`-prefixed relationship.

> The test is a single question: **could a fresh import of the same source file reproduce
> this?** Yes → Tier 1. No → Tier 2. Nothing sits between them.

### R2 — Tier 2 attaches as meta-relationships, never as properties

Everything the *application* knows that the source system does not — review status,
tags, comments, ratings, assignments, links the user drew by hand — is modelled as a
separate node reached by a relationship whose **type starts with `__`**.

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

`flag` and `review` stay distinct deliberately. A flag is **data quality** — garbled text,
a dangling link, an empty `Absolute Number`. A review is a **verdict in a process**.
Different authors, different lifecycles, different views. Do not merge them.

**Shape B — a rule scoped to a set, not an item.**
`(:DOORSModule)-[:__policyFor]->(:__Meta:__Policy)`

| `__metaKind` | Label | Relationship | Payload |
|---|---|---|---|
| `policy` | `:__Policy` | `__policyFor` | `attributeName`, `rule` (`mandatory` / `forbidden` / `pattern`), optional `appliesToLabels` |

This is the "which attributes are mandatory" case. One node governs every object in the
module. **Never model this per item** — 984 nodes that still cannot answer "what is the
rule" is the failure mode.

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

- **Anything derivable** — counts, coverage percentages, statistics. Computed on read,
  never stored. Stored derivations go stale silently.
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
| `:__Meta` kinds | **Review**, **Tag**, **Note**, **Flag**, **Rule**, **Link** |
| `__schemaVersion`, `__metaKind` | never shown |
| `__createdBy` / `__createdAt` | **Added by** / **Added on** |
| `__metaId`, `__metaKind` | never shown |

### R6 — `__id` is application identity, not source identity

Every node in the database has `__id`, `__name`, `__version`. `__id` is globally unique
and is what the frontend uses as a route parameter and list key. A source system's own
identifier (DOORS `id`, a Windchill number, a Cameo element name) is **module-local or
tool-local and must never be used as a key**. See `docs/SE_ITEM_SCHEMA.md`.

### Where a given piece of state lives

Four stores, and the boundaries are not negotiable. When you need to persist something
new, place it here **before** writing code.

| Kind of state | Lives in | Written by | Example |
|---|---|---|---|
| Source data | Neo4j, un-prefixed properties + native relationships | importers only | `Object Text`, `refersTo` |
| Derived structure (Tier 1) | Neo4j, `__`-prefixed | importers only | `__child`, `__sortKey`, `__id` |
| **Business annotation (Tier 2)** | Neo4j, `:__Meta` nodes | **the API, on user action** | comments, mandatory-attribute rules, review status |
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

Before adding *any* dependency not in this table, check whether the platform already
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
├── api/                        ← routes, DTOs, serialization
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
GET  /api/v1/tree                       ← root of the knowledge tree, lazy children
GET  /api/v1/items/{ref}                ← one SEItem, source-agnostic envelope
GET  /api/v1/items/{ref}/children
GET  /api/v1/items/{ref}/traces         ← refersTo, out only (see schema doc §8.2)
GET  /api/v1/items/{ref}/annotations    ← Tier-2 data attached to an item
POST /api/v1/items/{ref}/annotations    ← R2 write path
PATCH/DELETE /api/v1/annotations/{ref}
GET  /api/v1/modules                    ← DOORS-specific projection
GET  /api/v1/modules/{ref}/attributes   ← runtime attribute discovery, namespace filtered
POST /api/v1/cypher/explain             ← see docs/CYPHER_API_DESIGN.md
POST /api/v1/cypher/run
```

`{ref}` is the base64url encoding of `__id` (R5). Decode it in one place — a route
parameter converter — never inline in a handler.

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
├── core/                       ← singletons: api client, auth, meta store, error handling
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
  distinguishable from
  imported truth. This mapping matters: **a user must never mistake something the app
  added for something DOORS said.**

Backgrounds are white and near-white greys. Airbus permits percentages of black from 10%
to 90% — use those for greys rather than inventing a neutral ramp.

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

## 9. The UI skeleton (current milestone)

Build exactly this shell, with placeholder content in every feature route. Getting the
shell right and empty is the milestone; the views come after.

```
┌───────────────────────────────────────────────────────────────────┐
│ ░░░ toolbar (Airbus blue, fixed, 56px)          [👤] [💾]         │
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
- Group headers are section labels in the Airbus blue at reduced opacity, not clickable
  accordions, unless the group grows past ~6 items.
- Active route is marked with a 3px left rule in `--sec-blue-mid` plus a pale background —
  not a filled pill.
- **User icon** (right of toolbar): opens a `mat-menu` with display name, email, roles,
  connected graph/database name, and a sign-out item.
- **Save icon** (right of toolbar): commits pending **Tier-2 business annotations** —
  comments on an item, which attributes a module treats as mandatory, review outcomes.
  Nothing else routes through it: navigation order is config, sidenav state is a browser
  preference, and neither is ever "unsaved".
  It is disabled when nothing is dirty and shows a `mat-badge` with the pending-change
  count when it is. Dirty state comes from an `AnnotationStore` signal service in `core/`.
  Wire the button and the dirty signal **now**, even though no view produces edits yet —
  retrofitting a global save across a dozen views is far harder than stubbing one, and the
  store is where optimistic update, conflict handling and audit fields will land.
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
- DOORS specifics are fully described in `docs/DOORS_TO_NEO4J_IMPORTER_SPEC.md`, including
  seven known export defects that the importer must survive. Do not re-derive that design.

---

## 11. Working agreements

**Before writing code**

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
  there. A lint rule catching `__` inside `.html` templates is worth the ten minutes.
- `""` from DOORS means "attribute exists and is empty", not "absent". Render it as empty,
  do not fall back as if it were missing.
- Sort document-order lists by `__sortKey`, never by a source-native order field such as
  `objectNumber` — those do not sort correctly as strings, which is why `__sortKey` exists.

**Before saying you are done**

- `./gradlew check` and `npm --prefix frontend run lint && npm --prefix frontend test` pass.
- New graph behaviour has a Testcontainers test against a real Neo4j **Community** image.
  Never test against Enterprise; the constraint differences are the whole point.
- Any decision that took real thought gets a short ADR in `docs/adr/`.
- Update this file if you changed something it describes.

**Do not, without asking**

- Add a dependency not in §4.
- Write a Tier-2 value as a node property, or invent a per-source alternative to
  `__child` / `__sortKey` (R1–R3).
- Surface a `__`-prefixed name to the user, or put a raw `__id` in a URL (R5).
- Introduce a second persistence mechanism — no side database, no local cache of graph
  state, no browser storage of graph data.
- Change the identity scheme, the label model, or the meta model.
- Make anything DOORS-specific outside `importers/.../doors/`, `source/doors/`, and the
  DOORS-specific API routes.
