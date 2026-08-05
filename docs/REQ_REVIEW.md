# Requirements → Req review

**Status:** backend implemented (API, meta model, schema, Testcontainers tests); the Angular view
is not yet built. §11's open questions are answered below. See `../adr/0005-req-review-backend.md`.
**Route:** `/requirements/review` · **Component:** `features/requirements/review/`
**Source:** handwritten notes, pages 1–6, plus the decisions in §9.
**Read first:** `../SE_ITEM_SCHEMA.md`, `CLAUDE.md` §2 (R1–R7), `attribute-policy-checks.md`.

This view is the second dynamic-content view. It loads one DOORS module into a table, lets a
reviewer see traceability and attributes side by side, comment on requirements, and choose which
attributes are shown for that module and which are checked.

§9 records three decisions that touch existing rules — **one of them requires a small amendment
to R7 in CLAUDE.md.** Read it before implementing §5.2.

---

## 1. Layout

```
┌────────────────────────────────────────────────────────────────────────────┐
│ Module [ ▼ ] (1)   [⚙] (2)   [💾] (5.2)   [ search…                  ] (3) │
├────────────────────────────────────────────────────────────────────────────┤
│ (4)  ID │ Type │ Name │ Attr₁ … Attrₙ │ References (4.1) │ Comment (4.2)    │
│      …                                                                     │
└────────────────────────────────────────────────────────────────────────────┘
                                              ┌───────────────────────────┐
                                              │ detail panel (right) (7)  │
                                              └───────────────────────────┘
```

Everything sits on one sheet (`sec.page-shell`), the table in a bounded scroll panel
(`sec.scroll-panel` — sticky headers need a concrete height, CLAUDE.md §6). The view's own
action bar holds the gear, the save icon and the search field; **none of them go in the
application toolbar**, which stays free of actions (CLAUDE.md §9).

---

## 2. (1) Module selector

- `mat-select` over `GET /api/v1/modules`, label `__name`, value the module's `:ref`.
  Show `moduleFullPath` (aliased **Path**) as secondary text — module names repeat across folders.
- Selecting a module loads table (4) in document order (`__sortKey`, never `objectNumber`) **and**
  its saved attribute settings (§6), so the previously chosen columns come back with it.
- The selected module goes in the URL (`/requirements/review?module=:ref`) so a view is shareable
  and survives reload. `:ref` is base64url `__id`; a raw `__id` in the address bar violates R5.
- Changing module clears the search box and closes the detail panel (§7). If comments are
  pending, confirm first (§9.1).

## 3. (2) Settings dialog

`⚙` opens the attribute settings dialog, §6. Static `open()`, `SEC_MODAL_DIALOG`, explicit
Save/Cancel, sized in the dialog itself.

## 4. (3) Search

- Filters the rows **already loaded** in table (4); it does not re-query the graph.
- Case-insensitive substring over every visible column, including `id`, type, `__name`, visible
  attribute values and comment text. Debounced with `debounced` (~250 ms).
- Shows `n of m` (computed on read — never stored, CLAUDE.md §2) and a clear button.
- Filtering must never drop a pending comment edit: filter the *view*, keep the edit buffer keyed
  by `ref` (§5.2).
- If a module ever exceeds a few thousand objects this moves server-side; the API shape in §8
  already allows it (`q` parameter), the frontend simply does not use it yet.

---

## 5. Table (4)

### Column order

```
ID │ Type │ Name │ <visible attributes, module attribute order> │ References │ Comment
```

**References is second-to-last and Comment is always last**, regardless of the settings dialog.

### Fixed columns

`ID`, `Type`, `Name`, `References`, `Comment` are always shown. In the settings dialog their
*Visible* checkbox is checked and disabled.

| Column | Source | Notes |
|---|---|---|
| ID | `id` | display only — module-local, never a key (R6). Tabular figures. |
| Type | `__typeRaw` when present, else the type label mapped through `Aliases.kt` | `DOORSTBD` → **TBD**, `__UNDEFINED` → **Not yet imported**. Raw labels never reach the template (R5). |
| Name | `__name` | when it came from the `Object Text` fallback, show `Object Text` as tooltip |
| Attr₁…ₙ | dynamic DOORS attributes marked *Visible* for this module | `""` renders empty, not as missing (CLAUDE.md §11) |
| References | `refersTo`, both directions | §5.1 |
| Comment | `:__Meta:__Note`, one per object | §5.2 — Tier-2 accent `#0077C8`, so an app comment is never mistaken for imported truth |

### Behaviour

- Default order is document order. Column sort is allowed; a control resets to document order.
- ~1 000 rows per module: CDK virtual scroll, bounded scroll container, compact density (`-2`).
- Column definitions are built at runtime from `GET /api/v1/modules/{ref}/attributes`, which is
  already namespace-filtered server-side (R5). **Never hardcode a column list** — attribute sets
  differ per module.
- DOORS attribute names are display labels only. Use a synthetic column key for `columnDef` and
  `track`; never a name containing spaces, dots, slashes or umlauts as a key, class or URL part.
- Indent by `objectLevel`, and render `DOORSInformation` muted, so context text is not read as a
  requirement. Table cells/rows (`DOORSTableCell`, `DOORSTableRow`) appear like any other object.

### 5.1 References column (4.1)

Groups the linked objects' `id`s:

```
Outgoing:  SRD-4, SRD-5
Incoming:  SRD-35, SRD-36
```

- Each id opens the detail panel (§7) for that object.
- A target carrying `__UNDEFINED` renders muted and non-clickable as **Not yet imported
  (module <name>)** — accent `#FE5000`, wording from `Aliases.kt`, never the label string (R5).
- **Incoming links are incomplete by design.** Importers ingest out-links only, so an incoming
  link exists only if the *referencing* module has itself been imported. The column header
  carries an info affordance saying so in plain language. Never present an empty incoming list as
  "orphan requirement" (`SE_ITEM_SCHEMA.md` §8.2).
- All `refersTo` edges are untyped. Do not display or imply satisfies/verifies/refines here —
  that semantics belongs to `:__Meta:__Link` (R2, Shape C) and is a different feature.

### 5.2 Comment column (4.2) and the save icon

A reviewer's comment on a requirement. It is **not DOORS data**: `:__Meta:__Note`, kind `note`,
reached by `__noteOn`, payload `text` (R2, Shape A). **Exactly one comment per object** — the
cell is a single editable text field, not a thread.

Editing marks the row dirty. Dirty rows are marked in the Comment cell (Tier-2 accent) and
counted next to the save icon.

**Save icon** — `save` icon button in the view's own action bar, tooltip *Save comments*:

- Disabled when nothing is dirty; shows the dirty count as a badge when something is.
- One click → **one request, one transaction**, writing every dirty comment for the loaded module
  (§9.1). Partial success is impossible: the transaction either commits or nothing is written and
  the edits stay on screen with the error shown inline.
- On success the rows clear their dirty marks; the table does not reload.
- Dirty state lives in this view's component and dies with it. It is never shared, never global,
  never carried across a route change.
- Clearing a comment to empty **deletes** the `:__Note` node rather than storing an empty string,
  so `MATCH (m:__Meta)` stays a true inventory of what the app knows.

```cypher
CYPHER 25
UNWIND $comments AS c
MATCH (i:SEItem {__id: c.itemId})
MERGE (i)-[:__noteOn]->(n:__Meta:__Note {__metaId: c.metaId})
SET n.__metaKind = 'note', n.__schemaVersion = 1, n.text = c.text,
    n.__updatedBy = $user, n.__updatedAt = $now,
    n.__createdBy = coalesce(n.__createdBy, $user),
    n.__createdAt = coalesce(n.__createdAt, $now)
```

`__metaId` is generated server-side (UUID v7) for a new comment and echoed back by the read; the
client never invents one. One-comment-per-object cannot be constrained on Community, so the write
path enforces it: if the item already has a `__noteOn`, update that node instead of creating a
second. Deletion of emptied comments runs in the same transaction. Header alias: **Comment**.

---

## 6. Settings dialog (from ⚙)

Scrollable list of every attribute of the selected module — one row per attribute, three checkbox
columns:

| Attribute | Mandatory | Visible | Verification |
|---|---|---|---|

- Attribute list from `GET /api/v1/modules/{ref}/attributes`, discovered at runtime, namespace
  filtered.
- **All three settings are per module and shared by every user** — they are decisions about the
  module, not personal preferences. Storage in §9.2.
- **Mandatory** — feeds the attribute-policy check. This is the *same stored value* the Modules
  settings dialog writes (`attribute-policy-checks.md`): one `:__Meta:__Policy` per
  `(module, attributeName)`, `rule: mandatory`, `appliesToLabels: ['DOORSRequirement']`.
  **Do not create a second policy shape or a second write path.** Opening this dialog after
  changing the setting in Modules must show the change.
- **Visible** — the attribute becomes a column in table (4) for this module, for everyone, and
  comes back the next time the module is selected (§2).
- **Verification** — marks the attribute as verification-relevant. Consumed later by analytics,
  consistency checks and object summaries in other views (§9.3). Inert in this view beyond being
  set and shown.
- Fixed columns appear with *Visible* checked and disabled. `ID` is additionally always mandatory
  and cannot be unchecked. Every other attribute may be non-mandatory.
- **Save** commits everything in the dialog in one request and one transaction (R7) — both meta
  kinds together; **Cancel** discards. On failure the dialog stays open with input intact and the
  error inline.
- Table (4) re-renders its columns when the dialog closes successfully. Hiding a column with a
  pending comment does not discard the comment — the edit buffer is keyed by object, not by row
  position.

---

## 7. Detail panel (right)

- Opens on click of a row's `ID` or of any id in the References column.
- Shows the object's attributes (namespace filtered, aliased) plus the module it belongs to —
  `__moduleUrl` rendered as the module's `__name`, as a link (R5 alias map).
- Closed manually by the user; also closes when another view is selected or another module is
  loaded. Opening it changes neither table selection nor scroll position.
- Clicking an unresolved (`__UNDEFINED`) target does not open the panel — show a short message
  naming the module that has to be imported.

---

## 8. API

Reuses the existing surface; three additions.

```
GET  /api/v1/modules                          ← selector
GET  /api/v1/modules/{ref}/attributes         ← attribute list + mandatory/visible/verification
GET  /api/v1/modules/{ref}/objects            ← NEW: rows, document order, paged, optional q
GET  /api/v1/items/{ref}                      ← detail panel
GET  /api/v1/items/{ref}/traces               ← outgoing refersTo
GET  /api/v1/items/{ref}/traces?direction=in  ← NEW: incoming, with the incompleteness caveat
POST /api/v1/modules/{ref}/comments           ← NEW: save icon — all dirty comments, one txn
POST /api/v1/modules/{ref}/settings           ← dialog Save — all three flags, one txn
PATCH/DELETE /api/v1/annotations/{ref}        ← single-comment edit/removal, if ever needed
```

Both `POST` endpoints are feature-shaped for exactly the reason CLAUDE.md §5 allows: a dialog and
a table save are one transaction, not N annotation calls. **They route through the same guarded
meta writer** as `POST /items/{ref}/annotations`. One meta write path, several endpoints.

`POST /modules/{ref}/comments` body:

```json
{ "comments": [ { "ref": "…", "text": "…" }, { "ref": "…", "text": "" } ] }
```

An empty `text` means delete. The response returns the saved comments with their `metaId`,
`updatedBy` and `updatedAt` so the table can clear dirty state without a reload.

Row payload — the attribute bag is a `Map<String, JsonElement>`, never a per-module DTO:

```json
{
  "ref": "ZG9vcnM6…",
  "id": "SRD-4",
  "name": "…",
  "type": "Requirement",
  "labels": ["SEItem", "DOORSObject", "DOORSRequirement"],
  "level": 3,
  "attributes": { "Object Text": "…", "REQ. Priorität": "" },
  "references": { "outgoing": [{ "id": "SRD-5", "ref": "…", "resolved": true }], "incoming": [] },
  "comment": { "ref": "…", "text": "…", "updatedBy": "…", "updatedAt": "…" }
}
```

`labels` is the one place raw label strings cross the wire, as a state channel (CLAUDE.md §5).

---

## 9. Decisions

### 9.1 The save icon is a table save, not a global save — R7 needs one amendment

R7 already says *"every dialog and **every editable table** commits its own changes"*, and bans
the global save button, the staging layer and cross-view dirty state. The save icon specified in
§5.2 is the editable-table case: it lives in this view's action bar, it saves only this table's
comments, it is one request and one transaction, and its dirty state dies with the view. The
application toolbar keeps no actions (CLAUDE.md §9).

What no longer holds is R7's stated *consequence* that "no unsaved-changes route guard is needed,
because nothing can be unsaved outside a modal that cannot be navigated away from." A table with
pending comments **can** be navigated away from. Amend that bullet in CLAUDE.md to:

> - Dirty state is local to the open dialog, or to one editable table inside one view, and dies
    >   with it. No shared store, no cross-view state.
> - A view that owns an editable table guards its own exit: changing module, changing route or
    >   closing the tab with pending edits asks first. This is the only place a guard exists, and it
    >   is scoped to the view that owns the buffer — never a router-wide guard reading a global store.

Confirming beats silent discard here: a reviewer who has typed twelve comments and clicks a
different module has done real work, and a batch save is the one shape where losing it is cheap
to prevent and expensive to hit.

### 9.2 Visible and Verification are Tier 2, per module — a new Shape-B kind

Both are per-module decisions shared by all users, so both are `:__Meta` (R2), anchored on the
`:DOORSModule` and naming an attribute in the payload — Shape B, like `:__Policy`.

They do **not** belong in `:__Policy`. That node models a *rule about a value* (`mandatory` /
`forbidden` / `pattern`) with an `appliesToLabels` scope; visible and verification are *roles for
an attribute* with no value semantics and no label scope. Widening `rule` to hold them would make
`attribute-policy-checks.md` mean two things at once.

Add one kind to the catalogue in CLAUDE.md §2:

| `__metaKind` | Label | Relationship | Payload |
|---|---|---|---|
| `attributeSetting` | `:__AttributeSetting` | `__attributeSettingFor` | `attributeName`, `visible` (bool), `verification` (bool) |

- One node per `(module, attributeName)`, enforced by the write query — Community cannot
  constrain it.
- Alias map (R5): `:__AttributeSetting` → **Attribute setting**, `visible` → **Shown in table**,
  `verification` → **Verification attribute**.
- Backend meta-schema index, mirroring `meta_policy_attribute`, for the inverse question the
  summary views in §9.3 will ask:

  ```cypher
  CYPHER 25
  CREATE INDEX meta_attribute_setting IF NOT EXISTS
  FOR (s:__AttributeSetting) ON (s.attributeName);
  ```

- `POST /modules/{ref}/settings` writes `:__Policy` and `:__AttributeSetting` in the same
  transaction. Reading `GET /modules/{ref}/attributes` merges both onto the discovered attribute
  list, defaulting to all-false for attributes never configured.
- Mandatory stays exactly as `attribute-policy-checks.md` already specifies it. Nothing about
  that spec changes.

### 9.3 Verification is a forward commitment, and that is accepted

The flag has no consumer yet; it is being stored now because analytics, consistency checks and
the object summaries in other views will all read it, and re-tagging attributes module by module
later is worse than carrying an inert boolean. `__schemaVersion` is what makes that safe — the
payload can grow (a `verificationMethod`, a scope) without ambiguity about which generation a
node belongs to.

Two things keep it honest: it is set in the same dialog and transaction as the other two flags,
so it costs no extra write path; and it appears in the alias map from day one, so the first view
that consumes it does not invent its own wording.

---

## 10. Acceptance criteria

1. Selecting a module loads its objects in DOORS document order **and** restores its saved
   *Visible* columns; a 984-object module scrolls smoothly with a working sticky header.
2. Columns derive from the selected module at runtime; switching to a module with a different
   attribute set changes the columns with no code change.
3. Fixed columns cannot be hidden; `ID` cannot be made non-mandatory; References and Comment are
   always the last two columns in that order.
4. *Mandatory* set here is visible in the Modules settings dialog and vice versa — one
   `:__Policy` per `(module, attribute)`, verified by test.
5. The save icon writes every dirty comment in one transaction; a forced failure mid-save leaves
   the graph unchanged and every edit still on screen.
6. Comments survive reload and **survive a second import run of the same module** (the mandatory
   meta-survives-reimport test, R2).
7. Every comment and settings write passes the byte-identical-anchor test: the anchor node's
   property map is unchanged before and after (R1/R2).
8. Clearing a comment removes its `:__Note` node; `MATCH (m:__Meta) DETACH DELETE m` removes all
   comments and all attribute settings and leaves imported data intact.
9. Leaving the view or changing module with pending comments asks first (§9.1).
10. No `__`-prefixed string appears in any template, label, header or URL —
    `sec/no-internal-namespace` passes (R5).
11. Unresolved references render as *Not yet imported* with the owning module named, and the
    incoming-links caveat is present.
12. Graph behaviour covered by a Testcontainers test against Neo4j **Community**, tagged `docker`,
    run via `./gradlew :backend:integrationTest`.

---

## 11. Open questions — answered 2026-08-05

- **O1 — confirmed, no change.** The check applies to `appliesToLabels: ['DOORSRequirement']`, and
  this view writes exactly that default via the same `:__Policy` shape
  (`attribute-policy-checks.md` §1, §2). Headings, information objects and anything also labelled
  `DOORSTable` / `DOORSTableRow` / `DOORSTableCell` are never flagged. This view only sets the
  flag; it does not run the check.
- **O2 — no author is shown, anywhere.** Not in the cell and not in the detail panel. `__createdBy`
  and `__updatedBy` are still written, because R2 requires the audit fields on every meta node —
  they are simply not carried on the wire. `CommentDto` has no author field to leak one by
  accident. Revisit when real auth lands and a second reviewer actually exists.
- **O3 — any reviewer may edit any comment.** There is no auth layer yet, so every write is
  `CurrentUser.PLACEHOLDER` and an ownership rule would be unenforceable and untestable today. The
  audit fields are what make the rule addable later without a migration.
- **O4 — in scope, built.** The row payload carries `requirementLike`, computed server-side from
  the same scope the mandatory check uses, so the two views agree on what a requirement is. It
  counts `DOORSTBD` as requirement-like on purpose: a sanitised export blanks `Object Type`, so
  every object imports as TBD and excluding it would empty the table on exactly the fixtures that
  get shared (CLAUDE.md §10). The filter itself is client-side over loaded rows — no new API.

### Still open

- **O5 — `incomingComplete` is hard-coded `false`.** Incoming links stay incomplete until every
  referencing module has been imported, and nothing tracks which those are. The field exists so the
  caveat travels with the data instead of living in UI copy; making it real needs import-coverage
  tracking and no wire change.
