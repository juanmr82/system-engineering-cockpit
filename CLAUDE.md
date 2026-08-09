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
- **A re-import must not disturb Tier 2 — with exactly one exception, and it is named here.**
  The importers `MERGE` on `__id` and `SET n += props`, which leaves relationships alone. Verify
  this holds after any importer change — a test asserting "meta survives a second import run" is
  mandatory. The exception: when a source deletes the item an annotation hangs off, the annotation
  is deleted with it (ADR 0012). A note about a requirement DOORS no longer has is a note about
  nothing, and the alternative — keeping it alive on a node the source has disowned — leaves Tier 2
  anchored to something no export will ever mention again. This is the **only** circumstance in
  which an importer may delete a `:__Meta` node, and any second one needs its own ADR.
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
| `__name` | **Name** (contextually *Title* for documents, *Element* for MBSE). **Not shown at all in the Req review table** — that column is **Description**, built from source attributes, see below |
| `objectNumber` + `Object Heading` (a heading) / `Object Text` (anything else) | **Description** — the Req review table's mandatory third column (`docs/REQ_REVIEW.md` §5). Source data, so it is displayed; the two attributes it consumes are marked `fixed` by the API and cannot also be chosen as columns of their own |
| `__version` | **Version** — `"current"` renders as *Current*. Deliberately **not** *Baseline*: a DOORS baseline is a frozen, numbered release of a module, which this is not, and which will need the word when it arrives |
| `__id`, `__objectUrl` | never shown; opaque `:ref` in routes |
| `__sortKey` | never shown; silently drives default sort order |
| `__child` | never shown; silently drives the tree |
| `__moduleUrl` | rendered as the parent module's `__name`, as a link |
| `__typeRaw` | **Type** — preferred over the label chip when present |
| `:__UNDEFINED` | the *Not yet imported* state, with the owning module named. Reached from **either** side of a link now: a target this module points at, or a source that points at this module, which its own `__inputLinks` name. That second case is the one worth having — it is how a reviewer sees that something refines this requirement before the referencing module exists here at all |
| `:__DELETED` | **Deleted in DOORS** — an object an import once brought in that a later export of its module no longer contains. It keeps every label, attribute and id it had, so the view still says *which* requirement went away; it is out of the tree and out of every module listing. Carried as `deletedInSource`, never as a label string. In the References column: the target's id, struck through, in **error red**, not a link. In Issues: *n links to or from objects deleted in DOORS*. Every one of those says the fix is **in DOORS**, because this application holds no copy of the link |
| `__tableObject`, `__tableRowIndex`, `__tableColumnIndex` | never shown; a **cross-check** on table geometry, never the source of it (`docs/DOORS_TABLES.md` §2.1) |
| a `DOORSTable`'s reconstructed geometry | drawn as the table itself, in the Description column, with the **ID and Type columns blank** for it — as in DOORS. A cell shows its `Object Text` and nothing else: no id, no other attribute, no weight on the first row |
| a table's findings | **n findings on this table**, a disclosure above it. Computed on read, never stored (R2) |
| `refersTo` | **References** (outgoing) |
| `refersTo` **in the Breakdown tab only** | **refines ‹parent id›** — `A -[:refersTo]-> B` reads as *A refines B*, at every level, and the row names B. A display convention of that one tab, stated visibly in it, and never to be confused with an authored `:__Meta:__Link` carrying `semantics: 'refines'` (`docs/requirement-breakdown-tree.md` §2). A requirement with several parents is drawn under each of them, so naming the parent is what tells two copies apart (§10.1) |
| a `refersTo` the Breakdown tree cannot follow | **loops back to ‹id›** — the branch stops rather than repeating |
| the dependency graph's direction control | **What these refine** / **What refines these** / **Both directions** — never *upstream* / *downstream*. An outgoing `refersTo` is read as *refines*, so following it goes **up** the decomposition, and the two words would point opposite ways at the same arrow (`docs/REQ_BREAKDOWN_GRAPH_VIEW` §3.1, ADR 0011) |
| the dependency graph's level bands | the `:__Classification` system level's own wording — *L2 – Segment* — so the band and the badge inside it never disagree. Unplaced nodes get one explicit band, **No system level set**, always last and never folded into a real level |
| a dependency-graph node with links outside the picture | **+n**, a badge, with *This requirement has links to objects that are not in this graph* on hover. A graph that stops with nothing to say it stopped is read as a graph that ended (§1.1) |
| the dependency graph's incoming arrows | **no caveat at all** — and its removal is load-bearing rather than tidy-up. The importer reads `__inputLinks`, so a link into a requirement is in the graph whether or not its source module has been imported, and a missing incoming arrow really is a missing dependency. The standing sentence that used to say otherwise is now *wrong*: it would tell a reviewer to distrust an emptiness that carries real information. What remains is the unresolved-modules banner, which names modules and only appears when there are some (ADR 0012) |
| the item a Breakdown tree was opened for | **The requirement you opened** — on every copy of it, in words as well as in `--sec-subject` |
| a `:__Classification` `systemLevel` that is not set | the level badge stays, **empty and outlined**, with *No system level set for this module* on hover. Dropping it un-aligns every id in the column and reads as a fault rather than an absence |
| `id` in the detail panel | the panel's **heading**. `__name` is its second line — for a requirement that is `Object Text`, which a sanitised export makes identical on every object |
| an attribute the object carries with **no value** (`""`) | **Empty**, in `--sec-ink-3`, upright — never italic (§8). The row belongs in the list, because `""` means "exists and is empty"; leaving the value blank reads as the panel having failed to show something (`docs/REQ_REVIEW.md` §7) |
| an item with no incoming `refersTo` in the closure | **No incoming links** — never "no upstream links" |
| `:__AttributeSetting` `verification: true`, in the Breakdown tab | the **Verification** box; with none flagged, *No verification attribute defined yet for this requirement* — quietly, because it is an absence of configuration, not a finding |
| `:__Meta` kinds | **Review**, **Tag**, **Note**, **Flag**, **Rule**, **Link**, **Classification**, **Attribute setting** |
| `:__Note` in the review table | **Comment** — one per object, never a thread |
| consistency-check findings on an object | **Issues** — the review table column, in error red. Fixed rules render as a sentence (*Object Type shall not be TBD*), configured ones as the unfilled attribute's name. Computed on read, never stored (R2) |
| `DOORSTBD` in a check message | **TBD** — as in *Object Type shall not be TBD*; the label itself never reaches the user |
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

**Deployment configuration overlays that file; it never replaces it.** `application.yaml` shipped
in the jar holds every default and all the plumbing; `-config=<path>` at startup supplies only what
one environment changes, and `config/ConfigArgs.kt` is what makes that a merge rather than a
replacement (Ktor's own `-config=` replaces). A per-key `-P:neo4j.uri=…` overrides on top, for a
container that must not write a file. **An operator's file never contains a module name, a port it
did not mean to change, or anything a code review owns** — if a deployment file has to state
`ktor.application.modules`, something has regressed. Credentials stay `$SEC_NEO4J_USER`-style
environment lookups, which fail to load when unset, on purpose. Adding a REST client is a new
section in the packaged file, not a new mechanism.

The one exception to "no browser storage" in §11 is this table's last row. That rule
exists to stop graph data being cached client-side; a boolean about a drawer is not graph
data.

---

## 3. Repository layout

One IntelliJ project, one Maven reactor at the root. The frontend is an npm workspace that
the Maven build deliberately does not drive - developers run it directly.

```
system-engineering-cockpit/
├── CLAUDE.md                     ← this file
├── pom.xml                       ← IntelliJ opens this. Aggregator, and the single source
│                                   of truth for JVM dependency versions and plugin config
├── mvnw / mvnw.cmd               ← Maven wrapper, "only-script" flavour: NO jar
├── .mvn/wrapper/                 ← maven-wrapper.properties, nothing else
├── backend/                      ← Ktor service
├── frontend/                     ← Angular workspace
├── importers/
│   ├── pyproject.toml            ← one Python project, several entry points
│   ├── src/sec_import/
│   │   ├── core/                 ← graph writer, identity, config, reporting (shared)
│   │   └── doors/, windchill/, cameo/   ← one package per source (§1), nothing shared
│   ├── win/                      ← .bat wrappers, Windows-only, thin
│   └── tests/
│       └── fixtures/             ← smoke_module_current.json, a 6-object DOORS export
├── scripts/win/                  ← PowerShell 5.1, the offline-workstation runbook made runnable
│   ├── sec-up.ps1                ← THE entry point: starts all three, -Status, -Stop
│   ├── sec-ports.ps1             ← one dual-stack port probe, shared (ng serve binds ::1 only)
│   ├── sec-env.ps1               ← dot-source per session; -Persist writes JAVA_HOME permanently
│   ├── sec-doctor.ps1            ← one line per prerequisite, changes nothing
│   ├── sec-neo4j.ps1             ← Neo4j from the console, not as a service
│   ├── sec-package.ps1           ← ng build + mvn -Pui package → ONE deployable jar
│   ├── sec-backend.ps1 / sec-frontend.ps1
│   ├── sec-importers-setup.ps1   ← venv + install, honouring a company pip mirror
│   └── sec-import-doors.ps1      ← -Smoke, -Test, or straight through to the importer CLI
├── docs/
│   ├── RUNNING.md                ← no-admin, proxy-only, mirror-only, no-Docker Windows box
│   ├── features/                 ← one spec per dynamic-content view
│   ├── REQ_BREAKDOWN_GRAPH_VIEW  ← the dependency graph; see ADR 0011 for where it was amended
│   └── adr/                      ← one short ADR per non-obvious decision
├── deploy/
│   └── docker-compose.dev.yml    ← Neo4j Community for local dev
└── .run/                         ← IntelliJ run configurations, committed
```

**Not every machine that builds this has Docker, direct internet, or administrator rights.**
`docs/RUNNING.md` is the environment contract for the workstation the DOORS importer actually
runs on: **no admin rights**, proxy-only internet, a pip mirror, Neo4j unzipped under the user
profile and run from the console, `JAVA_HOME` unset at login. Anything that would make the
build require Docker, a service-installed database, an unproxied download, a machine-wide
environment variable, a port below 1024, or a write outside the user profile breaks that
machine — which is the only machine that can talk to DOORS.

### Cross-platform hygiene — check this every time you add a file

- `.gitattributes`: `* text=auto eol=lf`, `*.bat text eol=crlf`, `*.ps1 text eol=crlf`.
- Never hardcode `/` or `\` in a path. Kotlin: `Path`. Python: `pathlib.Path`. Angular
  build config: forward slashes only, they are POSIX-normalised.
- No `bash`-only steps in Maven plugin config or npm scripts. Anything shell-shaped goes in a
  Kotlin/Python entry point invoked identically on both platforms.
- Windows-only code is confined to `importers/win/` and `importers/src/sec_import/doors/`.
  If Windows-only logic appears anywhere else, that is a defect.
- File encoding is UTF-8 everywhere, declared explicitly on every read/write. DOORS
  attribute names contain umlauts and the default Windows codepage will corrupt them.

---

## 4. Technology and versions

Pin these in the root `pom.xml` and `package.json`. Do not float versions.

| Component | Version | Notes |
|---|---|---|
| Neo4j | **Community 2026.x** | CalVer. Requires **JDK 21+**. Community has no RBAC, no property-existence constraints, no query governor — see §7. |
| Neo4j Java driver | **6.x** | matches the 2026 server series |
| JVM | **21 (LTS)** | `maven.compiler.release` and the Kotlin plugin's `jvmTarget`. Maven compiles with the JDK it runs on, so `JAVA_HOME` **is** the build JDK — `sec-env.ps1` prefers 21 for that reason |
| Kotlin | **2.4.x** | |
| Ktor | **3.5.x** | latest stable at time of writing (3.5.1, June 2026). Netty engine. |
| Maven | **3.9.x** | `mvnw` / `mvnw.cmd` committed, `distributionType=only-script` so there is no wrapper jar to be quarantined. A real Maven install is preferred over the wrapper — see ADR 0007 |
| Angular | **22** (released 3 June 2026) | |
| Angular Material | **22** | matched major to Angular |
| Node | **22+** | required by Angular 22 |
| TypeScript | **6.x** | required by Angular 22 |
| ag-grid Community | **36.1.0**, exact | `ag-grid-angular` + `ag-grid-community`, both MIT. The **only** table implementation — see ADR 0006 and §6. Pinned exactly, not `^`: ag-grid ships majors fast and an upgrade is a deliberate act. |
| echarts | **6.1.0**, exact | Apache-2.0. The **only** charting implementation — see ADR 0008 and §6. Pinned exactly for the same reason ag-grid is. Imported through `shared/charts/echarts-core`, never from `'echarts'` wholesale |
| ngx-echarts | **22.0.0**, exact | Apache-2.0, matched major to Angular. The standalone `NgxEchartsDirective` only; `NgxEchartsModule` is in the package and is never imported |
| elkjs | **0.11.0**, exact | EPL-2.0. The **only** graph-layout implementation — see ADR 0011. Loaded solely inside `features/requirements/graph/layout/elk.worker.ts`, so it never enters the initial bundle; it is CommonJS, hence the one entry in `allowedCommonJsDependencies`. Pinned exactly for the same reason ag-grid and echarts are |
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

## §5, §6, §8, §9, §10 — moved next to the code they govern

Each loads when you work in that directory; numbering is unchanged, so "§6" still resolves.

- §5 Backend — Ktor → `backend/CLAUDE.md`
- §6 Frontend, §8 Visual design, §9 UI shell → `frontend/CLAUDE.md`
- §10 Importers → `importers/CLAUDE.md`

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

## 11. Working agreements

**Before writing code**

- If the task has a spec in `docs/features/`, read it first — it exists because the
  decisions in it were expensive.
- Check the root `pom.xml` and `package.json` for the pinned version and use its
  current API, not a remembered one. Angular 22, Ktor 3.5 and Neo4j driver 6 all changed
  APIs recently.
- Search for an existing helper before adding one. This codebase should have exactly one
  graph read path, one graph write path, one meta write path, one HTTP client — and exactly
  one declaration of every graph name (§5, ADR 0010).

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

- `mvn verify` passes, and from `frontend/`: `npm run lint && npm test && npm run build`.
  Run the npm commands **from the `frontend/` directory**, not with `npm --prefix frontend`
  from the root — `--prefix` also changes where `npm install` writes, and installing from the
  wrong directory silently creates `frontend/frontend/node_modules` and leaves `package.json`
  untouched.
- New graph behaviour has a Testcontainers test against a real Neo4j **Community** image.
  Never test against Enterprise; the constraint differences are the whole point.
- **Container tests are tagged `docker` and are not part of `mvn verify`.** Not every machine
  that builds this has Docker, and a gate that cannot pass locally is a gate that gets skipped —
  taking the tests that *could* have run with it. Surefire carries
  `<excludedGroups>docker</excludedGroups>`; the `docker` profile inverts that to
  `<groups>docker</groups>`, so the same test classes run under the opposite filter. Run them
  with `mvn -Pdocker test`; CI must run both. The image tag is pinned in the root `pom.xml`
  (`neo4j-image.version`) and passed to the test as a system property, so it sits next to every
  other version rather than inside a test file.
- Any feature that writes Tier 2 has the byte-identical-anchor test from R2.
- Any decision that took real thought gets a short ADR in `docs/adr/`.
- Update this file if you changed something it describes.

**Do not, without asking**

- Add a dependency not in §4.
- Write a Tier-2 value as a node property, or invent a per-source alternative to
  `__child` / `__sortKey` (R1–R3).
- Write anything at all onto a node an importer created (R1).
- Surface a `__`-prefixed name to the user, or put a raw `__id` in a URL (R5).
- Write a graph name as a string literal in Kotlin, or declare a second constant for one
  that already has one (§5, ADR 0010).
- Add a global save button, a staging layer, or any cross-view dirty state (R7).
- Introduce a second persistence mechanism — no side database, no local cache of graph
  state, no browser storage of graph data.
- Store a derived value — counts, coverage, policy-check results — in the graph.
- Change the identity scheme, the label model, or the meta model.
- Make anything DOORS-specific outside `importers/.../doors/`, `source/doors/`, and the
  DOORS-specific API routes.