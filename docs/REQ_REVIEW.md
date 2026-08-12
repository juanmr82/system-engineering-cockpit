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

### 4.1 Filter checkboxes

Four, in the same bar, all narrowing the loaded rows and none re-querying. They combine with each
other and with the search, and none of them touches the "n in module" readout — filtering is not a
claim about how many objects there are.

| Checkbox | Keeps | Notes |
|---|---|---|
| **Requirements only** | `requirementLike` | headings, information objects and table structure are context, not requirements (§11 O4) |
| **Objects with issues** | a non-empty Issues list | unconditional — see §5.3 |
| **Requirements without parents** | `requirementLike` **and** no outgoing `refersTo` | below |
| **Links to unresolved objects** | a reference, in **either** direction, whose target DOORS deleted **or** whose target has not been imported | unconditional; below |

**Links to unresolved objects.** Two stored states, one checkbox, and the merge is deliberate. A
link can point at an object DOORS deleted — it keeps the link when it deletes the object, so a
requirement goes on refining something that no longer exists (ADR 0012) — or at one whose module no
import has brought in, which the graph holds as a `:__UNDEFINED` placeholder. The model keeps those
strictly apart, because they ask for opposite fixes: one is repaired in DOORS, the other by
importing a module.

**A reviewer sweeping a module is not making that distinction yet.** The question being asked is
*which of these links do not go anywhere I can see*, and the row itself says which kind it is once
the list is on screen. So the filter is the union and the wording is the user's rather than the
model's — *unresolved*, not `__UNDEFINED` and not "deleted" (R5). It was called **Links to deleted
objects** and matched only the first kind, which left the far commoner case — 376 of the reference
module's objects point at modules nobody has imported — findable only by scrolling.

The deleted half is still the one finding in the table a reviewer cannot act on from inside the
table, since the stale link exists only in DOORS. The working pattern is unchanged: collect every
row carrying one and take the list there. A filter is what makes that list, which is why it is not
left to a search of the Issues column.

**Both directions count.** An outgoing one says this requirement refines something that is gone or
unseen; an incoming one says something gone or unseen claims to refine this. They are the same defect seen from
two sides and are fixed in the same place, and a filter that looked only at outgoing references
would report a module as clean while it is the module the stale links land in.

Unconditional, like *Objects with issues* and for the same reason: an empty result honestly means
the module has none, and hiding the control when it does would leave a reviewer unable to tell that
from not having looked.

**Requirements without parents.** An outgoing `refersTo` reads as *refines* — `A -[:refersTo]-> B`
means A refines B, the display convention the Breakdown tab states in words (CLAUDE.md R5) — so a
requirement with none decomposes nothing above it. That is either a genuine top-level requirement or
one whose link was never drawn, and telling those two apart is the review this filter exists for.
Two parts of the rule are not negotiable:

- **Requirement-like only**, whatever *Requirements only* is set to. Headings, information objects
  and table structure never carry a `refersTo`, so without the restriction the filter returns most
  of the module and says nothing.
- **An unresolved target still counts as a parent.** The link *was* drawn; the module it points into
  simply has not been imported yet. Reporting those as parentless would be a finding about the
  import queue dressed up as a finding about the requirement — the same trap §5.1 guards against
  for incoming links.

---

## 5. Table (4)

### Column order

```
ID │ Type │ Description │ <visible attributes, module attribute order> │ References │ Issues │ Comment
```

**References, Issues and Comment are always the last three, in that order**, regardless of the
settings dialog. The finding and the box where a reviewer responds to it belong side by side, and
that is what the *order* is for — neither is pinned. See the note under Behaviour.

### Fixed columns

`ID`, `Type`, `Description`, `References`, `Issues`, `Comment` are always shown. In the settings
dialog the *Visible* checkbox of the attributes they consume is checked and disabled.

| Column | Source | Notes |
|---|---|---|
| ID | `id` | display only — module-local, never a key (R6). Tabular figures. |
| Type | `__typeRaw` when present, else the type label mapped through `Aliases.kt` | `DOORSTBD` → **TBD**, `__UNDEFINED` → **Not yet imported**. Raw labels never reach the template (R5). |
| **Description** | a heading: `objectNumber` + `" "` + `Object Heading`. Anything else: `Object Text` | see below |
| Attr₁…ₙ | dynamic DOORS attributes marked *Visible* for this module | `""` renders empty, not as missing (CLAUDE.md §11) |
| References | `refersTo`, both directions | §5.1 |
| **Issues** | the module's mandatory-attribute policies, evaluated against this object | §5.3 — error red `#E4002B`, because this is a finding *about imported data*, not something the app wrote |
| Comment | `:__Meta:__Note`, one per object | §5.2 — Tier-2 accent `#0077C8`, so an app comment is never mistaken for imported truth |

**Description replaced a `Name` column showing `__name`.** `__name` is a derived convenience — the
importer fills it from `Object Heading` or `Object Text` depending on the object — and showing it
meant the table's widest column held a value whose provenance changed row by row. Reading a
specification means reading outline numbers down the headings and statements down the
requirements, which is what this column now is. `__name` is no longer displayed anywhere in this
view.

Consequences that are part of the contract:

- **`Object Heading` and `Object Text` are `fixed` attributes.** The API marks them so, the
  settings dialog shows them checked and disabled, and the table drops them from the attribute
  columns. Without that a module could show the same sentence twice — in the table whose original
  problem was already too many columns.
- The `Object Text` fallback to `__name` covers objects carrying no `Object Text` key at all (203
  of SRD's 977), which would otherwise be blank rows. A key that is present but `""` renders
  empty: from DOORS that means "exists and is empty" (CLAUDE.md §11).

### Behaviour

- **The table is ag-grid Community** (`../adr/0006-ag-grid-community-for-tables.md`). It was a CSS
  grid inside a CDK viewport until two real modules arrived carrying 78 and 53 attributes; at
  fourteen columns the identity of a row and the comment box had both left the screen, and there
  was no resize and no sort. What that decision buys, and what this section now requires:
  - **ID is pinned left. Nothing is pinned right.** The identity of a row may never leave the
    screen, however far the attributes run — that pin is the whole reason for the change and is
    not to be given up to make room.

    Issues and Comment *were* pinned right, and are not any more. Two pinned columns take their
    470px out of the scrollable area permanently, and on a module with 78 attributes that squeezed
    Description — the one column holding the prose — between two fixed blocks. They keep their
    place as the last two columns instead, which is what the order above is for. **Their content
    is what makes this affordable:** an Issues cell is empty on a clean object, and a Comment is
    empty until someone writes one, so unlike ID neither is something a reviewer is reading *from*
    while scrolled elsewhere.
  - **Columns resize**, so `Object Text` — a full requirement statement — can be opened up.
  - **Headers wrap** (`wrapHeaderText` + `autoHeaderHeight`, in `SEC_GRID_DEFAULT_COL_DEF`, so
    every table in the application gets it). A DOORS attribute name is a phrase — "AR-BS Required
    Verification", "SYS. Rationale for Allocation" — and several of a module's differ only past
    the point a one-line header clips them. The header row grows once, at the top, rather than per
    row. The pair works exactly as `wrapText` and `autoHeight` do: either alone does nothing.
  - **Rows and columns are both virtualized.** Row virtualization was always needed at ~1 000
    objects; column virtualization is what makes ticking forty attributes survivable.
  - **Exactly one column has `flex`, and it is Description.** Every other column carries an
    explicit width. This is the settled position after two failures, and both are worth keeping
    written down because each looks like the obvious fix for the other:
    - *All columns flex* — dragging one column's edge recomputed every flex column beside it.
      Widening one visibly shrank its neighbours, and shrinking the column before Comment pulled
      a further column into view.
    - *No column flexes* — a module with **no visible attributes** (SRD) totals 1 200px of columns
      in a 1 588px grid, and the 388px left over sits between the last column and the right-hand
      edge, bounded by a rule on each side and carrying the row background. It reads as a real,
      empty, unnamed column. A module with eight attribute columns overflows instead and never shows it,
      which is why this only appears on some modules.
    - *One flex column* is self-limiting rather than a compromise, because flex only distributes
      **leftover** space. When the table overflows there is none, so Description sits at its
      `minWidth` and resizing reflows nothing — which is the case the first failure came from.
      When there is slack, the column holding the prose absorbing it is what should happen.

    The same rule applies to the Modules table, where `Path` is the flex column.
  - **Cells wrap and rows grow to fit** (`wrapText` + `autoHeight`, both in the shared column
    defaults). A requirement statement is a paragraph; truncating it to one line and hiding the
    rest in a tooltip means a reviewer cannot read down the column at all. The two properties are
    one setting — `wrapText` alone clips at the fixed row height, `autoHeight` alone has nothing
    to grow for.
  - **Vertical rules between columns.** Once cells wrap, the horizontal gap alone stops saying
    where one column ends and a wrapped statement runs into the attribute beside it.
- Default order is document order, which is the order rows are loaded in (`__sortKey`). Column
  sort is allowed, and the **Document order** control clears every column's sort to get back —
  it appears only while a sort is applied.
- **Sorting on Description is sorting on the outline number, numerically** — `4.3.1`, `4.3.2`,
  `4.3.2-0`, `4.3.2-1`. It is implemented as a comparator on each row's *position in the order the
  server sent*, which is `__sortKey` order: the zero-padded segment-wise expansion of
  `objectNumber`. Comparing outline numbers as strings is the exact mistake `__sortKey` exists to
  prevent (CLAUDE.md §11, R3), and re-deriving the numeric comparison in the client would be a
  second implementation of it, free to drift from the first.
- Bounded scroll container, compact density (`-2`).
- Column definitions are built at runtime from `GET /api/v1/modules/{ref}/attributes`, which is
  already namespace-filtered server-side (R5). **Never hardcode a column list** — attribute sets
  differ per module.
- DOORS attribute names are display labels only, carried in `headerName` and nowhere else. Every
  column uses a synthetic `colId` (`attr-0`, `attr-1`, …) and a `valueGetter`. **Never `field`:**
  ag-grid reads a dot in `field` as a property path, so `field: 'REQ. Priorität'` looks up
  `row['REQ']['Priorität']` and renders blank with no error at all.
- **The table is a flat list. There is no tree and no indent.** An earlier version indented by
  `objectLevel` to suggest the outline; it was drawing a tree in something that is not one, and it
  survived neither sorting nor filtering — both reorder the very rows the depth was measured
  against, leaving an indent that means nothing.
- **A heading is styled as a heading instead, across the whole row**, at a weight and size set by
  its `objectLevel` — level 1 reads as an H1, level 2 as an H2, down to level 6 — on a light blue
  ground that deepens towards level 1 (`--sec-heading-1` … `--sec-heading-6`). A style says how
  deep a heading is and keeps saying it in any order. This is the third exception to "colour is a
  rail or a rule, never a background"; see CLAUDE.md §8, where the amendment is recorded.
- `DOORSInformation` stays muted, for the separate reason that it is context and must not be read
  as a requirement.
- **An embedded table is drawn in the Description column, on the row of its `DOORSTable` object.**
  That is where DOORS itself draws it: inside the main text column, at that column's full width,
  with the surrounding display columns continuing to the left and right. The geometry is
  reconstructed server-side and fetched from `GET /api/v1/modules/{ref}/tables`; the whole feature
  is specified in `docs/DOORS_TABLES.md`.
- **`DOORSTableRow` and `DOORSTableCell` stay hidden** — 273 of Segment's 903 objects. Each is a
  fragment that only means anything laid out as a table, and the table they belong to is already
  drawn on its container's row, so listing them as well would print every cell twice. This is a
  **view filter, not a data decision**: they are still imported, still in the graph, still
  reachable, and the "n in module" readout still counts them.
- **The ID and Type columns are blank on a table's row**, as they are in DOORS. A table object
  carries an `Object Type` — usually TBD, because DOORS does not type the parts of an embedded
  table — and printing it says nothing about the figure on the row. Both are blanked in the
  column's *value*, so a copy agrees with the screen. The DOORS id of each cell stays on its
  tooltip.
- **A table shows its cells' `Object Text` and nothing else.** The attribute columns are empty on
  its row: an attribute value that happens to sit on a cell or a row object is not carried out
  beside the table (`DOORS_TABLES.md` §6.3 is deliberately not implemented), and the first row is
  not styled differently from the rest.
- **Links on cell objects are not surfaced yet.** A `DOORSTableCell` can carry `refersTo` in either
  direction and the graph has them; showing and following them from inside a drawn table is a
  feature of its own and is **to be done**.

### 5.1 References column (4.1)

Groups the linked objects' `id`s, **one per line** — a vertical list under a small direction
label, not a comma-separated run:

```
OUT
  SRD-4
  SRD-5
IN
  SRD-35
  2 not yet imported
```

Laid out horizontally they wrapped into a run of ids a reviewer had to parse rather than read.
Rows grow to their content now (§5), so the height is there to spend.

- Each id opens the detail panel (§7) for that object.
- A target carrying `__UNDEFINED` renders muted and non-clickable as **Not yet imported
  (module <name>)** — accent `#FE5000`, wording from `Aliases.kt`, never the label string (R5).
- **Incoming links are complete as of the module's own export** (amended; ADR 0012). This section
  used to say the opposite, and the reversal matters more than the wording: importers ingested
  out-links only, so an incoming link existed just where the *referencing* module had itself been
  imported, and an empty list meant nothing. The importer now reads `__inputLinks` as well, and a
  module's export states every link pointing at it — so the list is complete, `incomingComplete`
  is `true`, and an empty incoming list really does mean nothing refers to this object.

  A source whose module has not been imported is **in** the list, as *Not yet imported*, because
  the importer creates the placeholder from the target side. That is the case this rule was
  written to protect against, and it is now visible rather than merely warned about.

  The column's `headerTooltip` survives and says what the two directions are; it no longer carries
  a caveat, because there is none left to carry.
- All `refersTo` edges are untyped. Do not display or imply satisfies/verifies/refines here —
  that semantics belongs to `:__Meta:__Link` (R2, Shape C) and is a different feature.

### 5.2 Comment column (4.2) and the save icon

A reviewer's comment on a requirement. It is **not DOORS data**: `:__Meta:__Note`, kind `note`,
reached by `__noteOn`, payload `text` (R2, Shape A). **Exactly one comment per object** — the
cell is a single editable text field, not a thread.

**The editor fills the cell, spreadsheet-style** — a wrapping textarea with a small inset, no
border of its own, and a transparent ground; the cell contributes no padding, so the text starts
where the cell starts. It is not a form field sitting inside a much larger cell: at 900 rows a
column of boxed outlines is chrome, not information, so the edit affordance appears on hover and
focus instead of being drawn permanently. A textarea rather than an input because every cell
beside it wraps now (§5) — a comment that could not would be the only truncated thing on the row.

**The box grows to its text, and the row grows with it.** A textarea has a fixed height whatever it
contains, so a long comment used to scroll inside a cell with no sign there was more of it and no
way to see the bottom without clicking in. The renderer measures its content and states the height;
the column carries `autoHeight`, so ag-grid takes that into the row height alongside the wrapped
Description beside it. Two things this depends on, both easy to undo by accident:

- **The editor is in flow** (`display: block` on the cell, `inline-size: 100%` on the renderer's
  host, `styles/_grid.scss`). It was `position: absolute; inset: 0` while the row height was fixed,
  and an out-of-flow element contributes no height at all — `autoHeight` would collapse the row.
- **The cell is not a flex box.** Under `autoHeight` ag-grid nests cell content in wrappers sized to
  that content, and a textarea's intrinsic width is its `cols` — 20 characters — so an in-flow
  editor in a flex cell shrinks to a fraction of its column whatever `width: 100%` says. This is the
  same pairing `docs/DOORS_TABLES.md` §6.6 already paid for on the table cell.

Editing marks the row dirty. A dirty comment wears the Tier-2 accent as a **left rule and a wash**
rather than a full border — there is no border to thicken, and a rule reads down a column of them.
Dirty rows are counted next to the save icon.

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

### 5.3 Issues column

What the consistency checks found wrong with this object. **Two kinds of check feed one list**,
and the difference between them is the important part:

| | **Fixed checks** | **Configured checks** |
|---|---|---|
| Example | *Object Type shall not be TBD* | a mandatory attribute with no value |
| Turned on by | nothing — they always run | the settings dialog, per module |
| Runs on a module nobody configured | **yes** | no |
| Rendered as | a sentence | the bare attribute name |

Fixed findings come first: an object that was never classified at all is a more fundamental
problem than a typed object with an unfilled field.

**The fixed rule, in full.** An object carrying the TBD type label is reported as
**"Object Type shall not be TBD"** — *unless* it is table structure (`DOORSTable`,
`DOORSTableRow`, `DOORSTableCell`) or an unresolved placeholder (`__UNDEFINED`). DOORS genuinely
does not type the cells and rows of an embedded table, and a placeholder for an object no import
has reached has no `Object Type` to be wrong; reporting either would be reporting on the
importer's own bookkeeping rather than on the requirements. The wording is displayable under R5 —
"Object Type" is the DOORS attribute the label came from and "TBD" is that label's alias, so no
raw label reaches the user.

Configured checks are the mandatory-attribute policies; `attribute-policy-checks.md` defines what
is checked and on what.

- **The attribute names are listed, one per line, in error red** — not counted. "2 issues" tells a
  reviewer to go and find out what they are, which is the click this column exists to save.
- The names are raw DOORS attribute names, and that is correct under R5: they are *content* — the
  names the user chose in DOORS and ticked in the dialog — not internal identifiers.
- An object with nothing wrong renders an **empty cell**. No tick, no "OK": nine hundred rows of
  reassurance is noise competing with the rows that need attention.
- Scope and the definition of "missing" are `attribute-policy-checks.md` §1, unchanged. Blank
  counts as missing even though the table renders `""` as an empty cell elsewhere (CLAUDE.md §11)
  — the two are the same violation and the distinction is not surfaced.

#### Where the check runs, and why there is nothing to run for existing data

**In the backend, on read, in the request that already loads the rows.** Not at import, not stored
on the graph, and not in a separate pass over the module.

The tempting argument is that DOORS fields are read-only and only change on import, so the verdict
could be computed once and cached. **That argument is false, and the reason is the decisive one:**
a violation is a function of *(imported data × policy)*, and the policy is Tier-2 configuration a
user edits from the settings dialog at any moment. Ticking *Mandatory* on an attribute changes
every object's verdict with no import involved. A value computed at import time is stale on the
next checkbox — which is precisely R2's "stored derivations go stale silently".

What follows from that, and is the practical payoff:

- **There is no backfill.** Data already in the graph needs no migration, no recompute command and
  no maintenance job. Opening the table computes it. A module imported a year ago and a module
  imported a minute ago answer the same way.
- **It cannot drift.** There is no state to be wrong.
- **It is free.** The rows query already returns every property of every object, so the check is a
  map lookup per *(object × mandatory attribute)* — 437 × 8 on the reference module — plus one
  small query for the policies, once per page rather than once per row.

If it ever does get slow, `attribute-policy-checks.md` §5 is the order to work in: check the query
plan first, then an **in-process** cache keyed by `(moduleId, lastPolicyWrite, lastImportRun)`.
Persistence is the last resort and needs its own ADR.

#### Module-level warning and the filter

- An **alert icon** appears in the action bar, beside the gear, **only when the loaded module has
  violations**, badged with the count and clicking through to the filter. A permanent "0 issues"
  badge is a claim this view cannot always honestly make — see below.
- An **Objects with issues** checkbox narrows the table. **Unconditional**, because a fixed check
  runs on every module whatever its configuration, so an empty result honestly means "none found".
  It was once gated on the module having a mandatory policy — with only configured checks, a
  filter on an unconfigured module could do nothing but empty the table, and an empty table reads
  as "all clear" when the truth was "nothing was looked for". Fixed checks removed that trap.
- **A module with no mandatory policy says "No mandatory attributes"** in the action bar, and the
  label opens the settings dialog. It deliberately no longer says *nothing checked* — that became
  untrue the moment a fixed check existed. What it still tells the reviewer is that the
  *configurable* half is unconfigured, so an absence of attribute findings means nobody asked for
  any, not that every field is filled. Quiet styling, not the error red: this is a statement about
  configuration, not a finding.
- The count is over every loaded object, never over the filtered view: filtering a finding out of
  sight must not read as having fixed it.

> **Zero is three different facts, and the UI must not conflate them:** every value is filled;
> nobody has marked anything mandatory; or every object fell outside the policy's scope. The third
> is the normal state for a **sanitised export**, which blanks `Object Type` so every object
> imports as `DOORSTBD` and none is in scope (CLAUDE.md §10). This is also why the reference
> modules cannot exercise the violation path at all — Segment reports 0 over 437 requirements
> because the redacted export fills every attribute — and why the behaviour is held up by the
> container tests in `ReviewFeatureTest` rather than by looking at it.

---

## 6. Settings dialog (from ⚙)

Scrollable list of every attribute of the selected module — one row per attribute, three checkbox
columns:

| Attribute | Mandatory | Visible | Verification |
|---|---|---|---|

**The list itself lives in `shared/attribute-settings/attribute-settings-list`, not in this
dialog.** The Modules settings dialog's *Object attributes* tab renders the same component with
`flags = ['mandatory', 'verification']` and no fixed columns
(`features/requirements-modules.md` §4.2) — *Shown in table* configures **this** view's table, and
the Modules view has none, so offering it there would be offering a setting with no visible effect.

What the shared component owns: the search box, the `n of m` count, the per-column **All** /
**None** bulk actions, the fixed-column rows, and the row grid. What each dialog owns: its heading,
its one line of supporting text, and what Save posts. The wording of the three flags is stated once,
in that component, from the server's alias map (`Aliases.attributeSettingLabels`) — which is how the
two dialogs came to describe one stored flag with one set of words.

- Attribute list from `GET /api/v1/modules/{ref}/attributes`, discovered at runtime, namespace
  filtered.
- **`Object Heading` and `Object Text` come back `fixed`** and render with *Visible* checked and
  disabled: the Description column is built out of them (§5), so they are always shown and can
  never be shown twice. They stay listed rather than being hidden, so a reviewer can see *why*
  they cannot be turned off — and their **Mandatory** and **Verification** boxes still work.
- **All three settings are per module and shared by every user** — they are decisions about the
  module, not personal preferences. Storage in §9.2.
- **Mandatory** — feeds the attribute-policy check. This is the *same stored value* the Modules
  settings dialog writes (`attribute-policy-checks.md`): one `:__Meta:__Policy` per
  `(module, attributeName)`, `rule: mandatory`, `appliesToLabels: ['DOORSRequirement']`.
  **Do not create a second policy shape or a second write path.** Opening this dialog after
  changing the setting in Modules must show the change. Both dialogs now also post the same
  *payload* — the absolute `attributeSettings` list — so there is one write shape as well as one
  stored shape.
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
- Shows the object's own attributes (namespace filtered, aliased) plus the module it belongs to —
  `__moduleUrl` rendered as the module's `__name`, as a link (R5 alias map).
- **An attribute with no value reads `Empty`**, in the non-content ink, upright — never italic
  (CLAUDE.md §8; the same substitution the `absent-text` mixin already makes, and the reason
  `styles.scss` carries a global `font-style: normal`).

  `""` from DOORS means "the attribute exists and is empty", which is not the same as absent
  (CLAUDE.md §11) — so the row was always in the list. What did not work was rendering the value as
  an empty `<dd>`: a label with nothing beside it reads as the panel having failed to show
  something, not as an empty attribute.

  > **The panel lists the object's attributes, not the module's, and that is a decision.** Listing
  > the module's whole discovered set was built and reverted: it made the panel read identically on
  > every object, at the cost of a module-wide scan on every open — **8ms → 26ms, measured against
  > the running service** — to name attributes the object does not have. An absent attribute is not
  > a finding a reviewer acts on; an empty one they were already looking at is.
- **The panel is resizable**, by a `separator` between it and the table: pointer-drag or arrow keys,
  280–900px, default 380. A fixed width was too narrow for an object carrying a long `Object Text`
  and too wide for one carrying almost nothing, and which of those is on screen changes with every
  click. The width is **component state** — it outlives opening and closing the panel and dies with
  the view. Persisting it would mean browser storage, which CLAUDE.md §2 does sanction for exactly
  this kind of preference, but no view writes there yet and starting is a decision of its own.
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
GET  /api/v1/items/{ref}                      ← detail panel — one read, ~8ms; keep it that way
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

`GET /items/{ref}` is **one** indexed read and must stay one. Its `attributes` bag is what this
object carries — `""` included, so an empty value stays distinguishable from a key that is not
there, and the panel renders it as *Empty* (§7).

Do not add the module's attribute set to it. That was built and reverted: the discovery query scans
every object of the module, which took the endpoint from **8ms to 26ms** on every panel open. If a
future feature genuinely needs that list beside an item, `GET /modules/{ref}/attributes` already
serves it and can be asked for separately.

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
    run via `mvn -Pdocker test`.

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

- **O5 — closed, and not the way it was expected to close.** `incomingComplete` is now `true`.
  It was hard-coded `false` on the reasoning that incoming links stay incomplete until every
  referencing module has been imported, and that making it real would need import-coverage
  tracking. It needed no such thing: the importer reads `__inputLinks`, so a module's own export
  states every link pointing at it, and the list is as complete as that export (ADR 0012). A
  source whose module has not been imported is *in* the list, as *Not yet imported*, rather than
  absent from it.

  The field stays on the wire. Whether an empty incoming list means anything is not something a
  consumer may assume for itself, and a future source that cannot report its inbound links will
  say so here rather than in UI copy. The caveat it used to carry has been removed from the review
  table's header tooltip and from the dependency graph, where it was a standing sentence.
