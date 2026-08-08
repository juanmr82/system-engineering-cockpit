# Feature spec — Requirements → Modules

**Path in repo:** `docs/features/requirements-modules.md`
**Status:** ready to implement. First dynamic-content view after the shell milestone (§9 of `CLAUDE.md`).
**Route:** `/requirements/modules` → `ModulesComponent`
**Read first:** `CLAUDE.md` §2 (R1, R2, R5, R6), §5 (backend), §6 (frontend), §8 (visual design);
`docs/SE_ITEM_SCHEMA.md` §3 (`DOORSModule` properties), §5 (dynamic attribute bag).

This is the reference implementation for every later view. The patterns established here —
Tier-2 write path, runtime attribute discovery, alias-driven column headers, dialog shape,
**where saving happens** — get copied. Get them right.

> **§9 lists three decisions to settle first, two of which require edits to `CLAUDE.md`.**

---

## 1. Scope

List every imported DOORS module. Let the user search it. Let the user attach two kinds of
Tier-2 knowledge to a module through a modal dialog:

1. a **system level** classification (L0–L4), shown back in the list;
2. a **mandatory-attribute policy** over the DOORS attributes of the module's objects.

**Out of scope:** any modification of imported data (never — R1); server-side pagination;
requirement-level views; the Statistics and Req review siblings.

---

## 2. Saving model — applies to this view and every view after it

**There is no global save button and no staging layer.** Each dialog or editable table
owns its own commit. A user who presses Save has written to the graph by the time the
dialog closes; a user who navigates away without pressing Save has written nothing.

Consequences to honour throughout:

- No `AnnotationStore`, no pending-changes queue, no dirty badge in the toolbar. Dirty
  state is **local to the open dialog** and dies with it.
- No unsaved-changes route guard is needed, because nothing can be unsaved outside an open
  modal — and the modal cannot be navigated away from (`disableClose`).
- One write per user gesture, one server-side transaction per write. A dialog covering two
  tabs still saves once, atomically (§5.3).
- The **backend** keeps its single guarded meta write path (`CLAUDE.md` §5). Removing
  client-side staging changes nothing about that: every Tier-2 write still funnels through
  one place server-side.
- The toolbar therefore carries only the user menu.

---

## 3. Module list

```
┌─────────────────────────────────────────────────────────────────────┐
│  Modules                              [ 🔍 search                 ] │
├──────────────────┬────────────────┬───────────────┬────────────────┤
│ Module           │ Last modified  │ Path          │ Level          │
├──────────────────┼────────────────┼───────────────┼────────────────┤
│ SRD          [⚙] │ 12 March 2026  │ /XXX-/Level…  │ ⬤ L2 – Segment │
│ ICD          [⚙] │ …              │ …             │ —              │
└──────────────────┴────────────────┴───────────────┴────────────────┘
```

| Column | Source | Notes |
|---|---|---|
| Module | `DOORSModule.__name` | **the settings gear leads, the name follows** — trailing the name it sat wherever that row's text ended, a ragged column of buttons. Sized to its content (`autoSizeColumns`), so a name is never truncated; that needs `wrapText: false` **and** `autoHeight: false`, because ag-grid cannot auto-size an auto-height column and fails at it silently (`CLAUDE.md` §6) |
| Last modified | `DOORSModule.last_Modified_On` | **free text from DOORS, not ISO-8601.** Display verbatim, sort as a string. Never construct a `Date` from it. |
| Path | `DOORSModule.moduleFullPath` | |
| Word export title | `DOORSModule.wordDocTitle` | A module property, not an object attribute, so it is read by name and never appears in attribute discovery. Absent ⇒ empty cell, no wording |
| Word export number | `DOORSModule.wordDocNumber` | as above |
| Level | `(m)-[:__classifiedAs]->(:__Meta:__Classification {scheme:'systemLevel'})` | **Tier-2 data.** Absent ⇒ em dash. |

Behaviour:

- **Search bar** — filters live as the user types, no Enter, no button. Debounce 200 ms
  (Angular 22 `debounced`). Case- and accent-insensitive substring match over the
  **rendered** values of every column, so what the user sees is what gets searched.
  Normalise each row once (`toLocaleLowerCase()` + NFD accent strip) and memoise it on the
  row model — do not re-normalise on every keystroke.
- **Table** — **ag-grid Community** (`../adr/0006-ag-grid-community-for-tables.md`), compact
  density (`-2`), sortable and resizable on every column, `tabular-nums`. **Default sort: system
  level ascending, L0 first.** That is the order the modules are read in — a segment specification
  before the subsystem specifications that refine it — and it is what the level column exists to
  make legible; alphabetical-by-name put L0 and L4 side by side by accident of spelling.

  **One sorted column, not two.** Naming `Module` as an explicit tie-break works and makes ag-grid
  draw its multi-sort position badges in the headers — `MODULE 2 ↑`, which reads as a column called
  *Module 2*. It is not needed: the server returns modules ordered by `__name` and
  `Array.prototype.sort` is stable, so equal levels stay alphabetical on their own. The spec asserts
  the full rendered order over two same-level modules, so neither half of that can quietly stop
  being true. A module with **no level set sorts last, and stays last when the sort is
  reversed**: it is absent from the hierarchy rather than at the bottom of it. That takes an
  explicit comparator, because ag-grid negates a comparator's result for a descending sort — it is
  `compareSystemLevels` in `modules.ts`, exported and unit-tested rather than driven through the
  grid's DOM. This view does not need pinning or column virtualization; it is on the grid
  so that the application has **one** table system, and so a reviewer moving between here and Req
  review meets one set of column behaviours. `Last modified` sorts as the string DOORS gave us —
  it is free text, not ISO-8601, and is never parsed into a `Date`.
- **Level cell** — a filled chip, so a user can never mistake an application classification for
  something DOORS said. **It is also the control that changes it**, and it is coloured by level:

  - **Editable in place.** A real `<select>` inside a cell renderer, writing to the view's own
    `ref`-keyed buffer — *not* an ag-grid cell editor, which would be a second staging concept
    beside the buffer when R7 allows exactly one (`CLAUDE.md` §6). The same shape as the review
    table's comment box, and the settings dialog's dropdown still writes the identical
    `:__Meta:__Classification`; there is one stored shape, reachable from two places.
  - **Saved in a batch**, behind a save icon carrying the count of pending changes, exactly like
    the review table's comments: one gesture, one request, one transaction, and the server's
    response — not the request — clears the dirty marks, so the list is never refetched. On
    failure nothing is written, the edits stay on screen and the error shows inline.
    `POST /api/v1/modules/system-levels`, which is **not** module-scoped because the batch spans
    modules; that is the only structural difference from `/{ref}/comments`.
  - **The view guards its own exit** (`modules.guard.ts`), because a table with pending edits can
    be navigated away from — the amendment R7 already carries for the review table.
  - **Coloured by level**, `--sec-level-0` … `--sec-level-4`: green → teal → blue → purple →
    magenta, L0 at the top of the hierarchy to L4 at the bottom. Two of those stops are colours
    the semantic palette had already spent; `CLAUDE.md` §8 records why that reuse is bounded and
    why it must not be extended. *Not set* is a quiet outline rather than a colour, so an
    unclassified module does not compete with the classified ones.
  - A pending change is marked with a dashed Tier-2 ring, not a colour change — the fill is
    already carrying the level, and the level is what the user is reading.

  **Sorting is on the label, not the code** (R5: the code never reaches the user, so ordering by
  it would be ordering by something invisible). The labels are worded `L0 – …` through `L4 – …`,
  so the hierarchy sorts in order as a consequence of the wording rather than by accident.
- **Settings button** — `mat-icon-button`, `settings` icon, always visible (not
  hover-only), `aria-label="Settings for {{ row.name }}"`.
- **Empty state** — a titled invitation, not an apology: "No modules imported yet" plus one
  sentence on running the DOORS importer.
- **Loading / error** — `mat-progress-bar`; error state offers Retry. Never a bare spinner
  with no text.

The row model carries the base64url `ref` (R5), never `__id`.

---

## 4. Settings dialog

Opened by the gear icon. **Two tabs**, with the action buttons outside the tab group so
they persist across tab changes.

```
┌────────────────────────────────────────────────────────────┐
│  Name:  SRD                                     read-only  │
│  ┌──────────────────────┬───────────────────────┐          │
│  │ Module properties    │ Object attributes     │  ← tabs  │
│  ├──────────────────────┴───────────────────────┴────────┐ │
│  │  System level:  [ L2 – Segment                    ▾ ] │ │
│  │  ┌──────────────────────────────────────────────────┐ │ │
│  │  │ Attribute name        │ Attribute value        ▲ │ │ │  ← header fixed
│  │  ├───────────────────────┼──────────────────────────┤ │ │
│  │  │ Description           │ System requirements…   ║ │ │ │  ← panel scrolls
│  │  │ Object ID prefix      │ SRD-                   ║ │ │ │
│  │  │ Created by            │ …                      ▼ │ │ │
│  │  └──────────────────────────────────────────────────┘ │ │
│  └───────────────────────────────────────────────────────┘ │
│                                        [ Cancel ] [ Save ] │
└────────────────────────────────────────────────────────────┘
```

Dialog configuration:

```ts
this.dialog.open(ModuleSettingsDialogComponent, {
    data: { ref },
    disableClose: true,        // no ESC, no backdrop dismiss
    autoFocus: 'first-tabbable',
    restoreFocus: true,
    width: '760px',
    height: '620px',           // fixed, so both panels scroll identically
});
```

Modal only: **no dragging, no minimising, no resizing.** Material dialogs are none of
those by default — do not add `cdkDrag`. The only exits are Save and Cancel.

The module name sits above the tab group as read-only text (label from the alias map:
**Name**), so it stays visible on both tabs. It is not a disabled input.

### 4.1 Tab 1 — Module properties

- **System level** — `mat-select`, single choice:

  | Shown | Stored (`code`) |
    |---|---|
  | *Empty* | no `:__Classification` node at all |
  | L0 – Customer | `L0` |
  | L1 – System of Systems | `L1` |
  | L2 – Segment | `L2` |
  | L3 – Subsystem | `L3` |
  | L4 – Component | `L4` |

  Options come from `GET /api/v1/config/system-levels`, not a hardcoded frontend array —
  the label text is alias-map territory (R5) and belongs to the backend.

- **Properties table** — two columns, **Attribute name** and **Attribute value**, one row
  per property of the `DOORSModule` node. Read-only. Header row fixed at the top of the
  scrolling panel.

  Which properties: **every non-`__` property of the module node**, plus `__version`
  rendered under its alias **Baseline** because its value is content the user needs.
  Never render a `__`-prefixed *name* (R5). Concretely: `description`, `moduleFullPath`,
  `prefix`, `created_By`, `created_On`, `last_Modified_By`, `last_Modified_On`,
  `_ModuleType`, `wordDocBaseline`, `wordDocCaptionLevel`, `wordDocIssue`,
  `wordDocNumber`, `wordDocTitle`.

  Two exclusions, both deliberate:
    - `url` — it is the same value as `__id`. Do not render it as a text row. If you want it,
      render it as an *Open in DOORS* action on the `doors://` URI (works where the DOORS
      client has registered the protocol handler, i.e. Windows). Optional.
    - `__objectId` — internal; the user has no use for `000969a2`.

  **Every row label comes from `Aliases.kt`.** `created_By` renders as "Created by",
  `_ModuleType` as "Module type", `wordDocIssue` as "Word export issue". Do not
  string-manipulate property names into title case in the frontend — that is exactly what
  R5 forbids, and DOORS names are not reliably transformable.

  Empty string from DOORS means "attribute exists and is empty" — render the row with an
  empty value cell, do not hide the row and do not print "n/a".

### 4.2 Tab 2 — Object attributes

**This tab is `shared/attribute-settings/attribute-settings-list`, the same component the Req
review settings dialog is built from** (`REQ_REVIEW.md` §6) — search box, count, per-column
bulk **All** / **None**, and one row per discovered attribute. It was a bare two-column
`mat-table` before, which meant finding one attribute among 78 by scrolling.

- Two flag columns: **Mandatory** and **Verification attribute**.
- **Shown in table is deliberately absent.** It configures the Req review table's columns, and
  there is no such table in this view — offering it here would be offering a setting whose effect
  is nowhere on screen. The flag is still *carried* in the model and posted back unchanged, so
  opening this dialog cannot clear what the review dialog set. There is a spec for exactly that.
- No fixed-column rows either, for the same reason: those are the review table's own columns.
- The list is the **DOORS attributes of the objects inside this module** — the un-prefixed
  keys only. `__`-prefixed keys and the source-native metadata keys (`id`, `objectNumber`,
  `objectLevel`) are filtered out server-side (R5), never in the template.
- Ticked = a `mandatory` policy exists for that attribute; unticked = none. The checkboxes wear the
  Tier-2 accent `#0077C8`.
- One line of supporting text under the tab, sentence case: *"Mandatory attributes are
  checked on the requirements of this module — headings, information objects and tables are
  not checked. Verification attributes are the ones that show how a requirement will be met."*
  The scope is stored on the policy, not chosen here — see
  `docs/features/attribute-policy-checks.md`.
- Sort alphabetically. Roughly 78 rows for the reference module, which is why the search box is
  not optional.

**Save posts the absolute state of every attribute** (`attributeSettings`), the same payload the
review dialog posts, in the same transaction as the system level. It replaced a mandatory-only
*diff*, which sent only what changed and therefore left an untouched policy's `__updatedAt` alone.
That property is genuinely lost, and it was the cheaper thing to lose: two write shapes for one
stored rule, edited through one shared component, is how the two dialogs come to mean different
things by Save.

**Attribute discovery.** All objects in a module carry the same attribute set, so a sample
is enough — but sample a handful rather than exactly one, because the importer *omits*
`Absolute Number` when it is not parseable, so a single unlucky object can under-report.
Default sample size 25, configurable, union of keys. If the union is larger than the first
object's key set, that is worth a `flag`-kind report entry later; for now log it.

Cache the discovery result per module for the process lifetime — the imported zone only
changes when an importer runs.

### 4.3 Save and Cancel

- Live in `mat-dialog-actions`, **outside** `mat-tab-group`, so they are present on both
  tabs and a user can edit the level on tab 1, tick attributes on tab 2, and save once.
- **Save persists immediately** (§2). One `POST`, one server-side transaction covering both
  the classification and the policy diff (§5.3). No staging, no global save button.
- Save is disabled while the form is pristine or a request is in flight.
- **On success:** close, refresh the affected row, show a snackbar. **On failure:** keep the
  dialog open with the user's input intact and show the error inline above the actions.
  Never close a dialog on a failed write; never discard typed input to show an error.
- **Cancel** discards everything and closes, including changes made on the other tab. No
  confirmation prompt (§9.3).

---

## 5. Tier-2 model and Cypher

Both things this dialog writes are Tier 2 by the R1 test — no re-import could reproduce
them — so both are `:__Meta` nodes reached by a `__`-prefixed relationship, and **neither
becomes a property on the module node.**

### 5.1 System level → `classification` kind (new, Shape A)

```cypher
(:DOORSModule)-[:__classifiedAs]->(:__Meta:__Classification {
  __metaId: '01924f…',            // UUID v7
  __metaKind: 'classification',
  __schemaVersion: 1,
  __createdBy: 'a.user', __createdAt: '2026-08-04T09:12:03Z',
  __updatedBy: 'a.user', __updatedAt: '2026-08-04T09:12:03Z',
  scheme: 'systemLevel',           // payload, no __ prefix
  code:   'L2'
})
```

- `scheme` exists so the same kind carries future classification axes (criticality,
  domain, discipline) without a new label per axis. `(scheme, code)` are both validated
  against a closed enum at the API boundary; unknown values are `400`.
- **The display label is not stored.** "L2 – Segment" is resolved from the alias map
  server-side. Storing it would create two sources of truth for a word we may change.
- One classification per `(module, scheme)`. Enforced by the write query, not by the
  database — Community has no such constraint.
- Selecting *Empty* `DETACH DELETE`s the node. There is no `code: null` state.

### 5.2 Mandatory attributes → `policy` kind (already in the catalogue, Shape B)

```cypher
(:DOORSModule)-[:__policyFor]->(:__Meta:__Policy {
  __metaId: '01924f…', __metaKind: 'policy', __schemaVersion: 1,
  __createdBy: …, __createdAt: …, __updatedBy: …, __updatedAt: …,
  attributeName:   'Object Text',  // verbatim DOORS name, payload
  rule:            'mandatory',
  appliesToLabels: ['DOORSRequirement']
})
```

`appliesToLabels` scopes the check to requirements only — headings, information objects and
table structure are never checked. See `docs/features/attribute-policy-checks.md`.

One node per `(module, attributeName, rule)`. Ticked ⇒ the node exists; unticked ⇒ it does
not. There is no `rule: 'notMandatory'` — `rule` is the closed enum
`mandatory | forbidden | pattern`, and absence is the negative. `attributeName` is
validated against the module's discovered attribute set; a client may not invent one.

Shape B on purpose: one node governs every object in the module. Never one policy node per
object.

Both kinds are removed by the single `MATCH (m:__Meta) DETACH DELETE m` query, as required.

### 5.3 Queries

Every statement `CYPHER 25`-prefixed and parameterised. Reads carry a `LIMIT` and a
transaction `timeout` (§7 of `CLAUDE.md`).

**Module list**

```cypher
CYPHER 25
MATCH (m:DOORSModule)
OPTIONAL MATCH (m)-[:__classifiedAs]->(c:__Meta:__Classification {scheme: 'systemLevel'})
RETURN m.__id             AS id,
       m.__name           AS name,
       m.last_Modified_On AS lastModified,
       m.moduleFullPath   AS path,
       c.code             AS levelCode
ORDER BY m.__name
LIMIT $limit;
```

**Module detail** — return the node and build the DTO in Kotlin from `node.asMap()`,
filtering `__` there and mapping every surviving key through `Aliases.kt`. Do not build
labels in Cypher; the alias map must not be duplicated.

```cypher
CYPHER 25
MATCH (m:DOORSModule {__id: $moduleId})
OPTIONAL MATCH (m)-[:__classifiedAs]->(c:__Meta:__Classification {scheme: 'systemLevel'})
RETURN m AS module, c.code AS levelCode;
```

**Object attribute discovery** — uses the `doors_object_module` index on `__moduleUrl`,
which reaches objects at every level rather than only the module's level-1 children:

```cypher
CYPHER 25
MATCH (o:DOORSObject {__moduleUrl: $moduleUrl})
WITH o LIMIT $sampleSize
UNWIND keys(o) AS k
WITH DISTINCT k
WHERE NOT k STARTS WITH '__'
  AND NOT k IN ['id', 'objectNumber', 'objectLevel']
RETURN k AS name
ORDER BY k;
```

**Existing policies**

```cypher
CYPHER 25
MATCH (:DOORSModule {__id: $moduleId})-[:__policyFor]->(p:__Meta:__Policy)
WHERE p.rule = 'mandatory'
RETURN p.attributeName AS name;
```

**Save — one `executeWrite`, up to three statements**

```cypher
CYPHER 25
MATCH (m:DOORSModule {__id: $moduleId})
MERGE (m)-[:__classifiedAs]->(c:__Meta:__Classification {scheme: 'systemLevel'})
  ON CREATE SET c.__metaId        = $metaId,
                c.__metaKind      = 'classification',
                c.__schemaVersion = 1,
                c.__createdBy     = $user,
                c.__createdAt     = $now
SET c.code        = $code,
    c.__updatedBy = $user,
    c.__updatedAt = $now
RETURN c.__metaId AS metaId;
```

```cypher
CYPHER 25
-- when the user chose Empty
MATCH (:DOORSModule {__id: $moduleId})
      -[:__classifiedAs]->(c:__Meta:__Classification {scheme: 'systemLevel'})
DETACH DELETE c;
```

```cypher
CYPHER 25
MATCH (m:DOORSModule {__id: $moduleId})
UNWIND $add AS row
MERGE (m)-[:__policyFor]->(p:__Meta:__Policy {attributeName: row.attributeName,
                                              rule: 'mandatory'})
  ON CREATE SET p.__metaId        = row.metaId,
                p.__metaKind      = 'policy',
                p.__schemaVersion = 1,
                p.appliesToLabels = ['DOORSRequirement'],
                p.__createdBy     = $user,
                p.__createdAt     = $now
SET p.__updatedBy = $user,
    p.__updatedAt = $now;
```

```cypher
CYPHER 25
MATCH (:DOORSModule {__id: $moduleId})-[:__policyFor]->(p:__Meta:__Policy)
WHERE p.rule = 'mandatory' AND p.attributeName IN $remove
DETACH DELETE p;
```

Send only the diff in `$add` / `$remove`, not the whole checkbox list — the dialog knows
what changed and a diff keeps the audit timestamps meaningful.

---

## 6. API

`{ref}` is base64url of `__id`, decoded by the route parameter converter (R5).

```
GET  /api/v1/modules
→ { "rows": [ { "ref": "…", "name": "SRD", "lastModified": "12 March 2026",
                "path": "/XXX-/Level 1 - System/SRD",
                "systemLevel": { "code": "L2", "label": "L2 – Segment" } | null } ] }

GET  /api/v1/modules/{ref}
→ { "ref": "…", "name": "SRD",
    "systemLevel": "L2" | null,
    "properties": [ { "label": "Description", "value": "…" },
                    { "label": "Object ID prefix", "value": "SRD-" }, … ] }

GET  /api/v1/modules/{ref}/attributes            ← already in CLAUDE.md §5
→ { "attributes": [ { "name": "Object Text", "mandatory": true }, … ] }

POST /api/v1/modules/{ref}/settings
body { "systemLevel": "L2" | null,
       "mandatoryAttributes": { "add": ["Object Text"], "remove": ["Priority"] } }
→ 200 same shape as GET /modules/{ref}
→ 400 unknown level code, or an attribute name not present in this module
→ 404 unknown module
```

`GET /api/v1/config/system-levels` → the dropdown vocabulary, `Cache-Control: max-age=3600`.

`properties` is a **list of label/value pairs, already aliased and ordered by the backend** —
not a map keyed by DOORS names. That keeps R5 airtight (no `__` name and no raw DOORS name
reaches a template as a key) and lets the backend control row order.

`POST …/settings` is an addition to the API list in `CLAUDE.md` §5. It must route through
the same guarded meta writer as `POST /items/{ref}/annotations`, not around it — one meta
write path server-side, regardless of how many places in the UI can trigger a save.

---

## 7. Frontend notes

```
frontend/src/app/features/requirements/modules/
├── modules.component.ts | .html | .scss           ← ModulesComponent, the route
├── module-settings-dialog.component.ts | .html | .scss
├── modules-api.service.ts                          httpResource + POST
└── modules.model.ts                                ModuleRow, ModuleSettings, SystemLevel
```

- `modules-api.service.ts`: `readonly modules = httpResource<ModuleListResponse>(() => '/api/v1/modules')`.
  After a successful save call `modules.reload()` — do not hand-patch the row and hope it
  matches what the server stored.
- `ModulesComponent`: `search = signal('')`, `filtered = computed(...)`, bound straight to the
  grid's `[rowData]`. Filtering stays in the computed rather than moving to the grid's own quick
  filter — `normalize()` strips accents, and a user typing `hohenruder` must still find
  *Höhenruder*.
- Dialog form: **Signal Forms**. No `FormGroup`. Dirty state lives in the dialog component
  and nowhere else (§2) — no shared store, no service-level pending state.
- Row identity is `getRowId: row.ref`, which is what ag-grid tracks by — never the row's index,
  so filtering and sorting move rows without a cell following the wrong module.
- No `changeDetection` declaration (OnPush is the v22 default).

Two Material gotchas that will bite:

1. **Sticky headers inside tabs.** `mat-tab-group` measures lazily; a sticky header
   rendered while its tab was hidden gets wrong offsets. Set `[preserveContent]="true"` on
   the tab group **and** call `table.updateStickyHeaderRowStyles()` on
   `(selectedTabChange)` for the newly shown table. Without this the header row detaches or
   overlaps on the first switch to tab 2.
2. **Fixed dialog height.** The scroll container needs a bounded height for
   `position: sticky` to do anything. Give the panel a concrete `height` / `max-height` in
   SCSS, not `flex: 1` alone, and put `overflow: auto` on the wrapper containing the table —
   not on the table.

Styling per `CLAUDE.md` §8: Inter, sentence case labels ("System level", "Mandatory
attribute", "Attribute name", "Attribute value", "Module properties", "Object attributes"),
never italic, compact density on both tables, tabular numerals, M3 tokens only — no
`::ng-deep`.

---

## 8. Acceptance criteria

1. Menu item **Requirements → Modules** routes to `/requirements/modules`; the table lists
   every `DOORSModule`.
2. Typing in the search bar filters live, without Enter, matching every column.
3. Every column sorts; **Last modified** sorts as a string and displays exactly as
   stored. The table *opens* sorted by system level, L0 first, and a module with no level is
   last in both sort directions.
4. The gear icon opens a modal dialog that cannot be dismissed by ESC, backdrop click,
   dragging or minimising — only Save or Cancel.
5. The dialog has two tabs; **Save and Cancel remain visible and functional on both**, and a
   single Save commits edits made on either tab.
6. Tab 1 shows the read-only name, the system-level dropdown with the six options, and a
   two-column Attribute name / Attribute value table of the module's properties.
7. Tab 2 shows a two-column Attribute name / Mandatory attribute table built from the
   DOORS attributes of the module's objects.
8. **On both tabs the table header stays fixed while the panel scrolls**, including on the
   first switch to tab 2.
9. **Save writes to the graph before the dialog closes.** Reloading the browser
   immediately after the dialog closes shows the saved state. No second confirmation step
   exists anywhere in the UI.
10. A failed save leaves the dialog open with the user's input intact and an inline error.
11. **No `__`-prefixed string appears anywhere in the rendered DOM or in the URL** (R5).
    Assert this: query the dialog's text content for `__`.
12. Save persists a `:__Meta:__Classification` and the `:__Meta:__Policy` diff, with
    `__metaId`, `__metaKind`, `__schemaVersion` and all four audit fields set.
13. Cancel persists nothing.
14. **The `DOORSModule` node's property map is byte-identical before and after a save.**
    This is the R1/R2 regression guard and it is not optional.
15. Selecting *Empty* removes the classification node; the Level column shows an em dash.
16. `MATCH (m:__Meta) DETACH DELETE m` removes everything this feature wrote and nothing
    else.
17. Re-running the DOORS importer leaves every node written by this feature intact.
18. Keyboard-only operation works end to end: tab order, tab-group arrow keys, dialog focus
    trap, focus restored to the gear button on close.

---

## 9. Decide before coding

### 9.1 `CLAUDE.md` — remove the global save

The saving model in §2 contradicts `CLAUDE.md` §9 as currently written. Edit it in the same
commit:

- **Delete the toolbar save-icon bullet** in §9 (the `mat-badge` pending count, the
  `AnnotationStore` dirty signal, and the instruction to wire it now). The toolbar keeps
  only the user menu; update the ASCII sketch to `[👤]`.
- **Update the state table** in §2 ("Where a given piece of state lives"): the Tier-2 row's
  *Written by* cell becomes "the API, on an explicit save in the dialog or table that owns
  the data".
- Add one line making the new rule explicit and reusable, since every future view will ask:
  *"Saving is always local to the dialog or table that owns the edit. There is no global
  save, no staging and no cross-view dirty state. One user gesture, one request, one
  transaction."*
- The **backend** guarantee is unchanged: one guarded meta write path (§5). Do not let the
  removal of client-side staging leak into a second server-side write path.

### 9.2 `CLAUDE.md` — add the `classification` kind

The `__metaKind` catalogue is described as closed, so using an unlisted kind is a rule
violation rather than an omission. Add to the Shape A table in R2:

| `__metaKind` | Label | Relationship | Payload |
|---|---|---|---|
| `classification` | `:__Classification` | `__classifiedAs` | `scheme` (`systemLevel`, …), `code` |

And extend the R5 alias map with: `:__Classification` + `scheme: systemLevel` → **System
level**; the five level codes and labels; `:__Policy` + `rule: mandatory` → **Mandatory
attribute**; and the `DOORSModule` property labels used in tab 1 (`description` →
Description, `moduleFullPath` → Path, `prefix` → Object ID prefix, `created_By` → Created
by, `created_On` → Created on, `last_Modified_By` → Last modified by, `last_Modified_On` →
Last modified, `_ModuleType` → Module type, `wordDoc*` → Word export …).

### 9.3 Discard confirmation on Cancel

Recommended: **none.** The dialog is short and fully reversible, and a confirmation on top
of a modal is friction without safety. If you want one, add it uniformly for every dialog
in the app, not just this one — inconsistent confirmation is worse than none.

---

## 10. Build order

1. Backend: `classification` kind in the meta model + alias map entries + the closed
   system-level enum, with unit tests.
2. `GET /api/v1/modules` + DTO + repository. Testcontainers test against Neo4j
   **Community**.
3. Angular: route, table, search, sort, empty/loading/error states. **Stop and review the
   UI against §8 of `CLAUDE.md` here.**
4. `GET /api/v1/modules/{ref}` and `/attributes`, including the `__`-filter and sampling.
5. `POST /api/v1/modules/{ref}/settings` through the guarded meta writer, with acceptance
   criteria 12 and 14 as tests.
6. Dialog: tab group, both tables, sticky headers, Signal Forms, wired to the endpoints.
7. A11y pass, the "no `__` in the DOM" test, an e2e test covering open → edit both tabs →
   save → reload → verify.