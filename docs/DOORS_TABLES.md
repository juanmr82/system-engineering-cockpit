# DOORS Table Rendering — Implementation Spec

**Audience:** Claude Code, working in the *System Engineering Cockpit* monorepo
(Angular 22 + Material frontend, Ktor backend, Neo4j 2026.x Community, Python/batch importers).

**Scope:** how to reconstruct and render an IBM DOORS **table** from the graph, both as a
standalone widget and inline inside the module document view.

**Related documents (authoritative, do not contradict):**
- `DOORS_TO_NEO4J_IMPORTER_SPEC.md` — how the data got into the graph.
- `SEItem Data Schema` — the data contract this document consumes.

**Prime directive from the project rules:** *the imported data is read-only and shall not be
enriched with new properties.* Everything in this document is **derivation at read time**.
Nothing here writes back to a `DOORSObject` node. If derived geometry ever needs to be
persisted for performance, it goes into a separate meta node/relationship with a `__` type
prefix — never as a property on an imported node.

---

## 1. What a DOORS table actually is (from the screenshots)

The screenshots show the standard DOORS module view: a grid whose columns are
`ID | <main text column> | AR-BS Method | AR-BS Required Verification | …`.

Observed facts, which are the requirements for this feature:

1. **A table is not a separate widget in DOORS.** It is drawn *inside the main text
   column*, occupying that column's full width, with visible cell borders. The surrounding
   display columns continue to the left and right of it.
2. **The `ID` column is blank** for the table container, for every row and for every cell.
   (Headings above it — `1 Einleitung`, `2 Dokumente`, `2.1 Anwendbare Dokumente` — do show
   their outline number in the main column, and normal objects show their `id` in the ID
   column. Table participants show nothing.)
3. **Each visual cell is one DOORS object** (`DOORSTableCell`).
4. **A row object owns its cells** — the cells are its `__child` objects.
5. **Which column a cell is in comes from the cell's `objectNumber` / `__sortKey`**,
   1-based (column 1 is the leftmost).
6. **Which row a row is comes from the row object's `objectNumber` / `__sortKey`**, 1-based.
7. **The first row is bold** — it is the header row.
8. **Cell text is the `Object Text` attribute.**
9. **If the view shows further attribute columns and a cell object happens to have a value
   in one of them, that value is displayed in that outer column too**, on the band of the
   table row it belongs to.

Point 9 is the awkward one. Handle it as specified in §6.3 — do not silently drop those
values, and do not silently merge them.

---

## 2. What the graph gives you

Per `SEItem Data Schema` §6.7, the importer already classified the three structural roles as
**additive labels** stacked on top of the normal type label:

| Label | Meaning | How the importer detected it |
|---|---|---|
| `DOORSTable` | the table container object | its `id` appears as `__tableID` of ≥1 cell in the same module |
| `DOORSTableRow` | one row | `__child` of a `DOORSTable` **and** `__child`-parent of a `DOORSTableCell` |
| `DOORSTableCell` | one cell | `__tableObject == true` in the export |

Typical label stack: `SEItem:DOORSObject:DOORSTBD:DOORSTableCell` — cells usually carry no
`Object Type`, so they land on `DOORSTBD`. **Never treat a `DOORSTBD` node as "unclassified
by the author" without first checking for a table label.**

The ordinary `__child` hierarchy already models the structure:

```
2.1.0-1          SRD-998    DOORSTable       __tableObject = false
└─ 2.1.0-1.0-1   SRD-1171   DOORSTableRow    __tableObject = false
   ├─ …-1.0-1    SRD-1172   DOORSTableCell   __tableObject = true, rowIdx 0, colIdx 0
   ├─ …-1.0-2    SRD-1173   DOORSTableCell   __tableObject = true, rowIdx 0, colIdx 1
   └─ …
```

### 2.1 Why you must derive geometry structurally, not from `__tableRowIndex` / `__tableColumnIndex`

Those two properties exist (0-based) but are **not trustworthy as the primary source**:

- The export has a known corrupt-key defect (`__taSbleRowIndex` on `SRD-1023`), so the
  correct key can simply be absent on individual objects.
- The importer coerces them to integer *only where parseable* and **omits them otherwise** —
  so `null` is a normal, expected value.
- The reference module is scrubbed; a lot of attribute-derived data is empty.

**Rule:** derive row and column from `objectNumber` / `__sortKey` + `__child` structure.
Use `__tableRowIndex` / `__tableColumnIndex` as a **cross-check only**, and report
disagreements as anomalies (§7). Never let a missing index produce a missing cell.

---

## 3. Geometry derivation — pure, unit-tested functions

Put these in the backend, in a package with **no Neo4j driver and no Ktor imports**, e.g.
`se.cockpit.doors.table` — same discipline the importer spec applies to its own derivation
rules. These functions are where every subtle rule lives.

### 3.1 Outline ordinal

`objectNumber` is dot-separated. Each segment is either `\d+` (heading) or `\d+-\d+`
(non-heading). The **last segment's trailing number is the object's ordinal among its
siblings**, 1-based.

```kotlin
/**
 * 1-based ordinal of an object among its siblings, taken from the last dot-segment.
 *   "2.1.0-1"        -> 1
 *   "2.1.0-1.0-3"    -> 3
 *   "2.1.0-1.0-12"   -> 12
 *   "7.2"            -> 2
 */
fun outlineOrdinal(objectNumber: String): Int {
    val lastSegment = objectNumber.substringAfterLast('.')
    val trailing = lastSegment.substringAfterLast('-')
    return trailing.toIntOrNull()
        ?: throw MalformedObjectNumberException(objectNumber)
}
```

Then:

```kotlin
fun rowNumber(row: SeItem): Int    = outlineOrdinal(row.objectNumber)     // 1-based
fun columnNumber(cell: SeItem): Int = outlineOrdinal(cell.objectNumber)   // 1-based
```

**Do not** parse the objectNumber any other way, and **never** use `startsWith` /
prefix matching to relate objects — importer rule R7 exists because 457 of 984 reference
objectNumbers are ambiguous under prefix matching. Splitting on `.` is mandatory.

### 3.2 Ordering vs. indexing — they are different things

- **Order** (which cell comes before which on screen): sort by `__sortKey`, plain string
  sort. `__sortKey` is the zero-padded form and is guaranteed to reproduce DOORS document
  order. Never `ORDER BY objectNumber`.
- **Index** (which column a cell occupies): `outlineOrdinal`, 1-based.

Use the ordinal for placement, so that a missing sibling leaves a **gap** instead of
shifting every subsequent cell one column to the left. Cross-check that ordinal order and
`__sortKey` order agree; if they don't, keep `__sortKey` order for rendering and raise a
`SORTKEY_ORDINAL_DISAGREEMENT` anomaly.

### 3.3 Table dimensions

```
columnCount = max(columnNumber(cell)) over all cells of the table   // 0 if no cells
rowCount    = max(rowNumber(row))     over all rows of the table
```

Tables are **not guaranteed rectangular**. Build a dense `rowCount × columnCount` matrix and
fill gaps with an explicit *absent* cell placeholder (`present = false`), rather than a
ragged structure. Renderers should not have to think about holes.

**Do not infer `colspan` / `rowspan` from missing cells.** A short row renders as trailing
empty cells plus a `NON_RECTANGULAR` anomaly. Guessing spans would silently misrepresent
requirements data.

### 3.4 Header row

`rowNumber == 1` is the header row (bold in DOORS). Expose it as `headerRowCount: Int = 1`
on the DTO rather than hardcoding it in the template, so a future "this table has no header"
toggle is a data change, not a component change.

### 3.5 Cell text

Cell text is **`Object Text`, verbatim**. Notes:

- `""` is a legitimate value meaning "attribute present, no value". Render an empty cell.
- The DXL escapes `\`, `\n`, `"`, `\t` — so multi-line cell text arrives as real newlines
  in the string. Render with `white-space: pre-wrap`.
- **Never fall back to `__name` for cell text.** `__name` for a cell is derived
  (`Object Short Text` → `Object Text` truncated to 120 chars → `id`), so a fallback would
  print `SRD-1172` into a table cell or silently truncate a long cell. `__name` is for
  search results and list views, not for table content.
- The text is **plain text, not HTML**. Render with Angular interpolation `{{ }}`. Never
  `[innerHTML]`, never `bypassSecurityTrust*`.

### 3.6 Nested tables and unexpected children

A `__child` of a `DOORSTable` that is **not** labelled `DOORSTableRow`, or a `__child` of a
`DOORSTableRow` that is **not** labelled `DOORSTableCell`, is possible (deleted cell leaving
an orphan row, caption objects, a nested table). Behaviour:

- Render it as a **full-width band** inside the table group, in `__sortKey` position, styled
  like a normal document row.
- Raise `UNEXPECTED_TABLE_CHILD`.
- If it is itself a `DOORSTable`, recurse — but cap recursion depth at 3 and raise
  `NESTED_TABLE` so it is visible rather than silently flattened. Guard against cycles by
  tracking visited `__id`s.

---

## 4. Backend — Ktor + Neo4j

### 4.1 Query

Fetch a whole module's tables in one round trip. Use the labels, not the raw `__table*`
fields, for selection.

```cypher
CYPHER 25
MATCH (t:DOORSTable {__moduleUrl: $moduleUrl})
OPTIONAL MATCH (t)-[:__child]->(r)
OPTIONAL MATCH (r)-[:__child]->(c)
RETURN t.__id            AS tableItemId,
       t.id              AS tableDoorsId,
       t.objectNumber    AS tableObjectNumber,
       t.__sortKey       AS tableSortKey,
       r.__id            AS rowItemId,
       r.objectNumber    AS rowObjectNumber,
       r.__sortKey       AS rowSortKey,
       labels(r)         AS rowLabels,
       c.__id            AS cellItemId,
       c.id              AS cellDoorsId,
       c.objectNumber    AS cellObjectNumber,
       c.__sortKey       AS cellSortKey,
       labels(c)         AS cellLabels,
       c['Object Text']  AS cellText,
       c.__tableRowIndex    AS exportedRowIndex,
       c.__tableColumnIndex AS exportedColumnIndex,
       [k IN $displayAttributes | c[k]] AS cellDisplayValues,
       [k IN $displayAttributes | r[k]] AS rowDisplayValues
ORDER BY tableSortKey, rowSortKey, cellSortKey
```

Notes:

- **Bracket access `c['Object Text']` and `c[k]`, never backticks.** DOORS attribute names
  contain spaces, dots, slashes, parentheses and umlauts (`REQ. Priorität`, `RFD/RFW`,
  `DXL for Out-links (AKA)`). The importer spec §7.4 forbids building Cypher by string
  concatenation from DOORS data; the same rule applies here. `$displayAttributes` is a
  **parameter**, so attribute names never touch the query text.
- `$displayAttributes` is the list of extra columns the user has chosen in the view. Get the
  available set from the `UNWIND keys(n)` query in the schema doc §5.1 — **never hardcode
  an attribute list**; it differs per module.
- Do not use `properties(c)` — object rows are 88+ properties wide and a module can have 399
  cells; fetch only what the view shows.
- Read-only session:
  `driver.session(SessionConfig.builder().withDefaultAccessMode(AccessMode.READ).build())`,
  and `session.executeRead { … }`. Same posture as the ad-hoc Cypher endpoint.
- Run driver calls off the event loop (`withContext(Dispatchers.IO)`) — the Neo4j Java
  driver's sync API is blocking.

### 4.2 DTOs (kotlinx.serialization)

Assemble the matrix **server-side**. The frontend should receive a rendering-ready view
model, not a bag of nodes; that keeps the geometry rules in one testable place and out of
TypeScript.

```kotlin
@Serializable
data class DoorsTableView(
    val tableItemId: String,          // __id of the DOORSTable node
    val tableDoorsId: String,         // e.g. "SRD-998" — for inspector/tooltip only
    val objectNumber: String,
    val sortKey: String,
    val rowCount: Int,
    val columnCount: Int,
    val headerRowCount: Int = 1,
    /** relative track weights for fluid column widths, size == columnCount, see §6.6 */
    val columnWeights: List<Double> = emptyList(),
    val rows: List<DoorsTableRow>,
    val extraBands: List<DoorsTableBand> = emptyList(),  // §3.6 unexpected children
    val anomalies: List<TableAnomaly> = emptyList()
)

@Serializable
data class DoorsTableRow(
    val itemId: String,
    val rowNumber: Int,               // 1-based
    val sortKey: String,
    val isHeader: Boolean,
    val cells: List<DoorsTableCell>,  // dense: size == columnCount, index 0 == column 1
    /** attributeName -> values to show in the outer display columns for this band, §6.3 */
    val outerColumnValues: Map<String, List<OuterColumnValue>> = emptyMap()
)

@Serializable
data class DoorsTableCell(
    val present: Boolean,             // false == structural gap, no object exists
    val itemId: String? = null,       // __id, for selection / deep link / trace panel
    val doorsId: String? = null,      // e.g. "SRD-1172"
    val columnNumber: Int,            // 1-based
    val text: String = ""             // Object Text, verbatim, may be ""
)

@Serializable
data class OuterColumnValue(
    val value: String,
    val sourceItemId: String,
    val sourceColumnNumber: Int?,     // null when the value came from the row object itself
    val sourceKind: SourceKind        // CELL | ROW | TABLE
)

@Serializable
data class TableAnomaly(
    val kind: TableAnomalyKind,
    val itemId: String?,
    val doorsId: String?,
    val objectNumber: String?,
    val detail: String
)
```

Anomalies are part of the payload, not a log line — the UI surfaces them (§7).

### 4.3 Endpoints

```
GET /api/modules/{moduleId}/tables?attrs=Object%20Text,AR-BS%20Method
      -> List<DoorsTableView>

GET /api/items/{itemId}/table?attrs=…
      -> DoorsTableView          # itemId may be the table, a row, or any cell;
                                 # resolve upward to the owning DOORSTable
```

`{moduleId}` / `{itemId}` are `__id` values (DOORS URLs) — URL-encode them, or accept a
base64url form. **Never route on the DOORS `id`**: it is unique only within a module.

Resolving upward from a cell: prefer the graph (`(t:DOORSTable)-[:__child]->()-[:__child]->(c)`),
fall back to the `__tableURL` property, and if both are absent raise `ORPHAN_TABLE_MEMBER`.

The module document-view endpoint should embed the table view inline, so the frontend never
has to make a second call while scrolling:

```json
{ "kind": "TABLE", "sortKey": "000002.000001.000000-000001", "table": { … } }
```

---

## 5. Frontend — Angular 22 + Material

### 5.1 Components

```
libs/doors-view/
  doors-document-view.component.ts   # the outer grid: ID | content | attribute columns
  doors-table.component.ts           # one DOORSTable, rendered inline in the content column
  doors-table-cell.component.ts      # optional; only if cell interaction gets rich
```

All standalone, `ChangeDetectionStrategy.OnPush`, `input.required<DoorsTableView>()` signal
inputs, new control flow (`@for` / `@if`), `track` on `itemId`.

```ts
@Component({
  selector: 'sec-doors-table',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="doors-table" role="table" [attr.aria-rowcount]="table().rowCount">
      @for (row of table().rows; track row.itemId) {
        <div class="doors-table__row" role="row"
             [class.doors-table__row--header]="row.isHeader"
             [attr.aria-rowindex]="row.rowNumber">
          @for (cell of row.cells; track cell.columnNumber) {
            <div class="doors-table__cell"
                 [attr.role]="row.isHeader ? 'columnheader' : 'cell'"
                 [attr.aria-colindex]="cell.columnNumber"
                 [attr.data-item-id]="cell.itemId"
                 [class.doors-table__cell--absent]="!cell.present">{{ cell.text }}</div>
          }
        </div>
      }
    </div>
  `,
})
export class DoorsTableComponent {
  readonly table = input.required<DoorsTableView>();
}
```

### 5.2 Do **not** use `mat-table` for this

`MatTable` is a flat, column-definition-driven data table. It cannot express "a nested grid
inside one column of the outer grid whose row bands align with the outer rows". Hand-roll
the markup and theme it with Material's system tokens so it stays visually consistent:

```scss
@use '@angular/material' as mat;

.doors-table {
  display: grid;
  // Fluid: the track list is a fraction list, never pixels. See §6.6.
  grid-template-columns: var(--doors-table-tracks);
  inline-size: 100%;
  container-type: inline-size;
  border: 1px solid var(--mat-sys-outline-variant);

  &__cell {
    min-inline-size: 0;         // MANDATORY — grid items default to min-width:auto and
                                // will refuse to shrink below their longest word
    padding: 4px 8px;
    border-right: 1px solid var(--mat-sys-outline-variant);
    border-bottom: 1px solid var(--mat-sys-outline-variant);
    white-space: pre-wrap;      // Object Text contains real newlines, and wraps
    overflow-wrap: anywhere;    // long IDs / URLs / German compounds must not blow out
    word-break: normal;         // do not break inside ordinary words
    text-wrap: pretty;          // avoid one-word last lines in narrow columns
    hyphens: none;              // never hyphenate normative requirement text
    @include mat.m2-typography-level(body-2);   // or the M3 token equivalent in use
  }

  &__row--header &__cell {
    font-weight: 700;           // "first row is bold"
    background: var(--mat-sys-surface-container);
  }

  &__cell--absent { background: repeating-linear-gradient(…); }  // subtle, not alarming
}
```

Set `--doors-table-tracks` from the column model (§6.6) via a host binding. Keep `MatTable`
for the flat requirement lists elsewhere in the app — this is the exception, not a new
house style.

### 5.3 `role` attributes are mandatory

CSS Grid `<div>`s lose all table semantics. Set `role="table" / "row" / "columnheader" /
"cell"` plus `aria-rowindex` / `aria-colindex` as shown, or screen readers see an
undifferentiated wall of text. This is a requirements tool; assistive-tech users are reading
normative content.

---

## 6. Rendering the table inside the document view

### 6.1 Outer grid

The document view is itself a grid:

```
grid-template-columns: [id] auto [content] 1fr [attr1] auto [attr2] auto …;
```

Walk the module tree via `__child` from the `DOORSModule`, children ordered by `__sortKey`.
On encountering a node labelled `DOORSTable`, **stop descending normally** and emit a table
group; its rows and cells must not also appear as ordinary document rows.

### 6.2 Aligning the nested table with the outer columns

Requirement 9 (§1) means the outer attribute columns must line up **per table row band**.
Use CSS **subgrid**: the table group spans `rowCount` outer grid rows, and the nested table
inherits the outer row tracks.

```scss
.doc-view { display: grid; grid-template-columns: /* as above */; }

.doc-view__table-group {
  grid-column: 1 / -1;
  display: grid;
  grid-template-columns: subgrid;              // align with ID / content / attr columns
  grid-row: span var(--doors-table-rows);
}

.doc-view__table-group .doors-table {
  grid-column: content;
  display: grid;
  grid-template-rows: subgrid;                 // one nested row per outer band
  grid-row: 1 / -1;
}
```

Subgrid is baseline-available in all evergreen browsers; this is fine for a 2026 internal
tool. **Fallback if subgrid is ruled out** (e.g. a locked-down browser in the RHEL image):
emit **one outer grid row per table row** and place that row's cells into the content column
as an inner `display: grid` with `grid-template-columns: repeat(N, 1fr)` and
`table-layout`-equivalent fixed proportions, so the vertical borders still line up between
bands. Suppress the top border on non-first bands to keep the table looking continuous.
Decide this once and write it down — do not mix the two.

### 6.3 The ID column and the extra attribute columns

**ID column:** empty for the table container, every row and every cell. Do not print
`SRD-998` / `SRD-1171` / `SRD-1172`. Keep the `__id` in `data-item-id` so selection, deep
links and the trace panel still work, and show the DOORS `id` in the hover tooltip / the
detail inspector. Offer a developer toggle *"Show IDs for table objects"* — off by default —
because debugging an import without it is miserable.

**Extra attribute columns**, per band, for each displayed attribute `A`:

1. Collect non-empty `A` values from every cell in that row (in column order), plus the row
   object's own `A`, plus — on the **first** band only — the table object's own `A`.
2. **0 values** → empty cell.
3. **1 value** → render it plainly. This is the overwhelmingly common case and must look
   identical to a normal document row.
4. **>1 value** → stack them vertically, each prefixed with a small chip showing its source
   column number (`c3`) or `row` / `table`. Mark the band with a
   `MULTIPLE_OUTER_COLUMN_VALUES` anomaly indicator.

> **Design note to confirm with the user:** DOORS itself gives each cell object its own
> physical line, so it can show two cells' `AR-BS Method` values without collision; our
> band-per-row model cannot. Stacking with a source chip is the lossless choice — nothing is
> hidden, and the origin stays visible. The alternative (one band per *cell* object, cells
> vertically stacked) is faithful to DOORS but destroys the visual table. Ship the stacking
> behaviour and flag it; switch only if the user says the DOORS layout must be reproduced
> exactly.

Empty string vs. absent: `""` means the attribute exists with no value; `null` means absent.
Both render as empty, but only non-empty values count for the rules above.

### 6.4 Heading numbers in the content column

Matching the screenshots: the content column prints the outline number **only for
headings** (`1 Einleitung`, `2 Dokumente`, `2.1 Anwendbare Dokumente`). A heading is an
object whose `objectNumber` segments are all plain `\d+`; non-heading objects
(segments containing `-`) print no number. Table participants print no number either.
Indentation comes from `objectLevel`.

### 6.5 Virtual scrolling

Modules can hold up to 12 000 objects. Use `cdk-virtual-scroll-viewport` with the
**autosize** strategy — fixed `itemSize` is impossible here, because cell text wraps and
every band's height depends on the current content-column width (§6.6). Treat a whole table
group as a **single virtual item**: never let a table be split across a viewport boundary,
or subgrid alignment and border continuity break.

Height caches must be invalidated on resize. Observe the viewport with a `ResizeObserver`,
debounce (~100 ms, trailing), and call `viewport.checkViewportSize()`. Measure after
`document.fonts.ready` resolves — measuring against the fallback font produces wrong band
heights that persist until the next scroll. Guard against `ResizeObserver loop` errors by
never writing layout-affecting styles synchronously inside the observer callback; set a
signal and let Angular render on the next tick.

The reference module is well within reach either way (6 tables, 399 cells total).

### 6.6 Fluid width, column widths and text wrapping

**Requirement:** cell text wraps, and the table's total width follows the content column —
resizing that column reflows the table and re-wraps every cell.

**Do this entirely in CSS.** The nested table is a grid inside a grid track; when the outer
`[content]` track changes width, the fraction-based inner tracks recompute and text re-wraps
with zero JavaScript. Never compute pixel widths in TypeScript and never set an explicit
`width` on `.doors-table` or on a cell — that is the one change that turns a resize from a
free browser reflow into a measure/write cycle that will fight the virtual scroller.

**Column width policy.** DOORS stores per-column widths on the table object, but the DXL
exporter does not emit them, so *the widths are not in the graph* and cannot be reproduced
faithfully. Pick a policy and state it in the UI:

1. **Default — content-weighted fractions.** Compute one weight per column, server-side, in
   the same pass that builds the matrix: e.g. the 90th-percentile character count of that
   column's `Object Text` values, clamped to `[1, 6]`, emitted as
   `columnWeights: List<Double>` on `DoorsTableView`. Render as
   `grid-template-columns: 2fr 1fr 1fr 4fr`. This approximates the screenshots (narrow
   `Version` column, wide description column) without inventing data.
2. **Equal fractions** (`repeat(N, minmax(0, 1fr))`) as the fallback when a table is empty
   or the weights are degenerate.
3. **Never `auto` or `max-content` tracks.** They size to the longest word and defeat
   wrapping, which is exactly the bug this section exists to prevent.

Build the track string once, in a `computed()`, and expose it as `--doors-table-tracks`:

```ts
readonly tracks = computed(() => {
  const w = this.table().columnWeights;
  const list = w?.length ? w : Array(this.table().columnCount).fill(1);
  return list.map(x => `minmax(0, ${x}fr)`).join(' ');
});
```

`minmax(0, …)` on every track plus `min-inline-size: 0` on every cell is the pair that makes
shrinking actually work. Omitting either gives a table that grows past its container and
never shrinks back — the classic CSS Grid overflow trap.

**User column resizing (optional, phase 2).** If you add drag handles between columns, they
mutate the weight list only. Persist the result **per user, per table** — and per the project
rule, *not* as a property on the imported `DOORSTable` node: a separate settings node linked
by a `__`-prefixed meta relationship, or user-scoped storage in the API layer. Re-render by
updating `--doors-table-tracks`; nothing else changes.

**Minimum usable width.** With N columns and a narrow content column, cells eventually become
unreadable. Set a floor with a container query on `.doors-table`: below roughly
`N × 4.5rem`, switch the group to a horizontally scrollable wrapper
(`overflow-inline: auto` on a parent, table `inline-size: max(100%, N × 4.5rem)`). Scroll the
**table only**, never the whole document view — the ID and attribute columns must stay put.

**Row height under wrapping.** Because subgrid row tracks size to the tallest content in the
band, a cell that wraps to four lines automatically stretches its outer band, and the
attribute-column values in §6.3 stay aligned. That is the main reason subgrid is the
recommended technique rather than a nested `<table>`. Cells should be
`align-content: start` so short cells sit at the top of a tall band, matching DOORS.

---

## 7. Anomalies to detect, report, and show

Every one carries the offending `__id`, DOORS `id` and `objectNumber`.

| Kind | Condition | Severity |
|---|---|---|
| `MISSING_CELL` | no object at (row, column) inside the bounding box | WARN |
| `NON_RECTANGULAR` | some row has fewer cells than `columnCount` | WARN |
| `DUPLICATE_COLUMN_ORDINAL` | two cells in one row derive the same column number | ERROR |
| `INDEX_MISMATCH` | `__tableColumnIndex + 1 ≠ derived column` (or row equivalent) | WARN |
| `SORTKEY_ORDINAL_DISAGREEMENT` | `__sortKey` order ≠ ordinal order | WARN |
| `UNEXPECTED_TABLE_CHILD` | child of table not a row, or child of row not a cell | WARN |
| `NESTED_TABLE` | a `DOORSTable` inside a cell | INFO |
| `ORPHAN_TABLE_MEMBER` | row/cell with no reachable `DOORSTable` | ERROR |
| `EMPTY_TABLE` | `DOORSTable` with zero cells | WARN |
| `MALFORMED_OBJECT_NUMBER` | `outlineOrdinal` cannot parse | ERROR |

**Never throw away a cell because of an anomaly.** Render everything you have; the anomaly
is an annotation, not a filter. In the UI, put a small warning affordance on the table
caption that opens a panel listing the anomalies — a systems engineer needs to know the view
may not match DOORS, and needs the `id` to go look.

---

## 8. Testing

Pure-function unit tests (`kotlin.test` / JUnit 5), no database:

- `outlineOrdinal`: `"2.1.0-1"→1`, `"2.1.0-1.0-3"→3`, `"…0-12"→12`, `"7.2"→2`,
  a `versionId`-style segment containing extra `-`, and malformed input → exception.
- Matrix assembly: rectangular table; ragged table; duplicate ordinals; a hole in the
  middle; single-cell table; zero-cell table.
- Header detection with `rowCount == 1` (header only, no body).
- Outer-column collection: 0 / 1 / 2+ values, including a value on the row object and one on
  the table object.
- `__tableRowIndex` present-and-agreeing, present-and-disagreeing, absent, and the
  `__taSbleRowIndex` corrupt-key case — all must still produce a correct matrix.

Integration test against the reference module `ExportedExampleModule_000969a2_current.json`
imported into a Testcontainers Neo4j 2026.x Community instance:

| Check | Expected |
|---|---|
| `DOORSTable` nodes found | 6 |
| `DOORSTableCell` nodes rendered | 399 (0 dropped) |
| Cells reachable from a table via 2× `__child` | 399 |
| `ORPHAN_TABLE_MEMBER` anomalies | 0 |
| `MALFORMED_OBJECT_NUMBER` anomalies | 0 |
| Every cell's derived column | ≥ 1 |
| Rendering the same module twice | byte-identical DTO payload |

Component tests: header row bold; `""` renders as an empty cell not the string `""`; a cell
whose `Object Text` is missing does **not** fall back to `__name`; multi-line text keeps its
line breaks; ID column empty for all table participants.

Layout tests (§6.6) — these catch the bugs that only appear at a specific width:

- Narrowing the container to 320 px must not make `.doors-table` overflow its parent
  (`scrollWidth <= clientWidth` on the content column) — the `minmax(0, …)` +
  `min-inline-size: 0` regression test.
- A cell containing a single 60-character token wraps inside its column instead of widening
  it.
- A cell wrapping to multiple lines increases its band height, and the value in an outer
  attribute column stays vertically aligned with that band.
- Weight-derived tracks: `columnWeights = [1, 1, 4]` produces a last column roughly four
  times the width of the first at a fixed container width.
- Resizing the content column from 1200 px to 600 px changes wrapping and leaves no stale
  cached band height in the virtual scroller.

---

## 9. Things this feature must not do

- **Must not write to the graph.** No derived `rowIndex`, no cached layout, nothing. If
  caching becomes necessary: API-layer cache first; if it must be persisted, a separate meta
  node linked by a `__`-prefixed relationship, per the project rule that imported data stays
  exactly as imported.
- **Must not reconstruct the table from `objectNumber` string prefixes.** Splitting on `.`
  only (importer rule R7).
- **Must not hardcode attribute names.** `Object Text` is the one exception, and it is the
  only DOORS attribute name allowed to appear as a literal in this feature's code — put it
  in a single `const OBJECT_TEXT = "Object Text"` so it is greppable when a module turns up
  that names it differently.
- **Must not use the DOORS `id` as a key.** It is unique within a module only. Keys, routes,
  `track` expressions and map lookups all use `__id`.
- **Must not infer merged cells.** Report `NON_RECTANGULAR`; render empty cells.
- **Must not render cell text as HTML.**
- **Must not set pixel widths on the table or its cells, or compute widths in TypeScript.**
  Width is fluid and follows the content column; the browser does the reflow (§6.6).
- **Must not use `auto` / `max-content` grid tracks or `white-space: nowrap`** — both defeat
  wrapping and make the table push past its column.

---

## 10. Suggested build order

1. Pure derivation functions + unit tests (§3, §8). No I/O.
2. Repository query + DTO assembly + anomaly collection (§4). Integration test against the
   reference module.
3. `DoorsTableComponent` standalone, fed by a fixture JSON — no backend needed (§5).
4. Inline integration into the document view: subgrid alignment, blank ID column, outer
   attribute columns (§6).
5. Anomaly panel and the developer "Show IDs" toggle (§7).
6. Virtual scrolling and performance pass (§6.5).

Steps 1–3 are independently shippable and independently testable; do not start 4 until 3
renders the reference module's six tables correctly from a fixture.

---

## 11. What was actually built, and where it departs from this document

Steps 1, 2, 3 and 5 are done, and step 4 was done **into the Req review table rather than into a
document view**, because there is no document view — see `adr/0009-doors-tables-in-the-flat-review-table.md`,
which records the decision and its cost. Verified against the reference module: 6 tables, 399 cells,
0 dropped, 0 anomalies.

Four deliberate departures, each argued in the ADR and in the code:

| § | What this document says | What was built, and why |
|---|---|---|
| §4.2 | `sortKey` on the view and on each band | **Dropped.** `__sortKey` is "never shown" in the R5 alias map, and shipping it puts an internal ordering key in a payload a browser can read. The server positions the bands instead: `DoorsTableBandDto.after`. |
| §6.1–6.2 | subgrid alignment with the outer grid's columns | **Not available.** A table occupies one ag-grid row, so its bands cannot line up with anything outside it. |
| §6.3 | outer attribute values shown beside the table | **Not implemented at all.** A table shows its cells' `Object Text` and nothing else. Two designs were built and measured against the reference module and both failed: stacked in the single outer cell it put 247 values — one distinct — in a cell 9 000 pixels tall; as trailing columns of the table it was legible but nobody asked for it. There is no `outerColumnValues` on the wire and no `attrs` on the endpoints. |
| §1.7, §5.2 | the first row is bold | **Not drawn differently.** A bolded row reads as a heading inside a document that already has headings, and on a sanitised export where every cell holds the same sentence it emphasises nothing. The row keeps its `columnheader` role, so a screen reader is still told what it is. |
| §6.3 | a developer "Show IDs for table objects" toggle | **Removed.** The DOORS id stays on each cell's `title`, which is enough to tell which object a cell is when an import goes wrong, and costs no screen space and no control in the action bar. |
| §6.5 | never split a table across a viewport boundary | **Bounded at `70vh`, scrolling within itself.** That rule is about a virtual scroller; unbounded here, one table takes the whole module's scrollbar with it. |

**The Type column is blank on a table's row too**, alongside the ID column §6.3 asks for. A table
object carries an `Object Type` — usually `TBD`, because DOORS does not type the parts of an
embedded table — and printing it says nothing about the figure on the row while reading as a
finding about it. Both are blanked in the column's *value*, not hidden by the renderer, so a copy
and any future export agree with the screen.

One correction to §7's table, because a finding nobody can act on is worse than none:
`MISSING_CELL` is raised for a hole in the **middle** of a row only. A row that stops early is one
`NON_RECTANGULAR`, not one finding per trailing column.

One kind was added — `DUPLICATE_ROW_ORDINAL`, because §7 lists the cell case only and dropping a
row whose ordinal is taken would break "never throw away a cell".

### Still open

- **§6.1–6.2 and §6.5 in full** — the document view, subgrid alignment, and virtual scrolling.
- **§3.6's nested-table recursion**, which has nothing to recurse into while the read query descends
  exactly the two `__child` levels a table is. A nested table is reported as `NESTED_TABLE` and its
  own rows are not drawn.
- **Traceability on cell objects.** A `DOORSTableCell` can carry `refersTo` links, in either
  direction, to objects outside the table — the graph has them and `/items/{ref}/traces` already
  answers for them, but nothing in the drawn table shows or reaches them. Deciding what a link on a
  cell *means*, and how a cell in a fluid grid offers it without becoming a control, is a feature of
  its own. **To be done.**