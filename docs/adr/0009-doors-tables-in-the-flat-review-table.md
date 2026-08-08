# ADR 0009: DOORS tables are drawn in the Description column, not as a document view

Status: accepted
Date: 2026-08-08

## Context

`docs/DOORS_TABLES.md` specifies how to reconstruct an IBM DOORS table from the graph and render
it. Its build order is explicit: pure derivation, then the read model, then a standalone component,
and only then §6 — inline integration into a **module document view** with CSS subgrid, one outer
grid row per table band, and a virtual scroller.

That document view does not exist. The only place a module's objects are on screen today is
**Requirements → Req review**, which is a flat ag-grid list, one row per object (`REQ_REVIEW.md`).
Waiting for the document view would have meant shipping steps 1–3 and nothing a user can see;
building the document view instead would have meant a second, parallel way to look at a module
before anyone had asked for one.

Three constraints made the shape of the compromise:

- **ADR 0006 says every data table is ag-grid.** A DOORS table has a column count and column widths
  that come from the data and differ per table, so there is no column definition to write. It is
  not a data table; it is a figure inside a document.
- **A table occupies one ag-grid row.** §6.2's subgrid alignment needs one outer grid row per
  table band, and a flat list has exactly one row for the whole table. Per-band alignment with the
  *outer* columns is therefore not available at all here.
- **`autoHeight` measures a cell once, when it is created.** The tables request answers after the
  rows do, so the cell is measured while its content is nothing.

## Decision

**A table is drawn inside the Description column of its own `DOORSTable` row**, which is where
DOORS draws it: in the main text column, at that column's full width, with the display columns
continuing on either side. `DOORSTableRow` and `DOORSTableCell` stay filtered out of the flat list,
because the table they belong to is already drawn.

`shared/doors-table/` is a standalone component fed by a server-assembled view model. It is the one
exception to ADR 0006, and the exception is stated in the component itself.

Three consequences of "one ag-grid row" had to be decided rather than inherited:

1. **The outer attribute columns are not drawn at all.** §6.3 wants a band's attribute values
   beside the band. Collapsing them into the single outer cell was tried against the reference
   module and is untenable — one table put 247 values in one cell, 9 000 pixels tall, of which
   exactly one was distinct. Drawing them as trailing columns of the table instead was legible and
   kept per-band alignment, and was then dropped too: **a table shows its cells' `Object Text` and
   nothing else**. The ag-grid attribute cell is empty on a table's row, and the endpoints take no
   `attrs`.
2. **The table is bounded and scrolls within itself** — `max-block-size: 70vh`, with its own
   horizontal scrollbar below `8rem` per column. This departs from §6.5's "never split a table
   across a viewport boundary", which was written for a virtual scroller. Unbounded, one table
   takes the whole module's scrollbar with it.
3. **The row height is measured by the component and stated to ag-grid** — a `ResizeObserver` on
   the drawn table calling `node.setRowHeight()` + `api.onRowHeightChanged()`. `resetRowHeights()`
   is not usable: ag-grid rejects it for an auto-height column, in as many words, in the console.

Two further pieces of chrome went the same way, for the same reason — a table is a figure, and a
figure that argues with the document around it is worse than a plain one. **The first row is not
bolded**: it reads as a heading inside a document that already has headings, and it keeps its
`columnheader` role so a screen reader is still told what it is. **The "Show IDs for table objects"
toggle is gone**: the DOORS id stays on each cell's `title`, which is enough to debug an import and
costs neither screen space nor a control in the action bar.

**The Type column is blank on a table's row**, alongside the ID column. A table object's
`Object Type` is usually `TBD` — DOORS does not type the parts of an embedded table — so printing
it says nothing about the figure and reads as a finding about it.

## Consequences

**Easier.** A user sees the module's tables today, in document order, in the view they already use.
The component is dumb and fed by a DTO, so the document view of §6 can mount the same component
without changing it — only the alignment strategy around it differs.

**Harder.** An attribute value that genuinely sits on a cell or a row object is now invisible in
this view. That is accepted knowingly: it is reachable through the detail panel and through the
graph, and against every module seen so far it is scaffolding rather than content. If a real module
turns up where it is content, §6.3 is written down and the geometry that fed it is one commit back.

**Also open.** A `DOORSTableCell` can carry `refersTo` links to objects outside the table, in
either direction. The graph has them and `/items/{ref}/traces` answers for them, but nothing in the
drawn table shows or reaches them — deciding what a link on a cell means, and how a cell in a fluid
grid offers it without becoming a control, is a feature of its own.

**Foreclosed, deliberately.** Nothing here is a second rendering path: when the document view
arrives it consumes the same `DoorsTableView`, and §6.1–6.2's subgrid work becomes a wrapper around
this component rather than a rewrite of it. What that view will *not* inherit is the height and
bounding machinery above — a virtual scroller measures differently, and the three decisions above
are scoped to the flat list by intent.
