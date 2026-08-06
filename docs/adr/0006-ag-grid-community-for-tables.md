# ADR 0006: ag-grid Community for the data tables

Status: accepted
Date: 2026-08-06

## Context

The Req review table was designed against a module with five columns. Two real DOORS modules are
now imported — SRD with 78 attributes and Segment with 53 — and at fourteen visible columns the
hand-rolled CSS-grid table has defects that are not cosmetic:

| Defect | Why it matters |
|---|---|
| **ID scrolls out of view** — by column 6 you cannot tell which requirement a row is | fatal for review work |
| **Comment is ~9 columns off-screen** — the whole point of the view is unreachable without scrolling right | fatal |
| **No column resize** — `Object Text` is a full requirement statement truncated to one line | severe |
| **No sorting** — `REQ_REVIEW.md` §5 asks for it; it was never built | owed |
| **No column virtualization** — tick 40 attributes and every rendered row holds 45 cells | degrades as it is used |

These share one cause. The table renders every column of every visible row into a CSS grid, and the
only thing it virtualizes is rows (CDK). Nothing about it is wrong at five columns and nothing about
it survives seventy-eight.

The columns are not knowable ahead of time. They come from
`GET /api/v1/modules/{ref}/attributes` at runtime, they differ per module by design, and their
names contain spaces, dots, slashes and umlauts. Whatever renders them cannot assume a fixed
schema.

Three options were weighed.

**By hand.** Sticky pinned columns ~3 h, resize ~4 h, sorting ~2 h ≈ 9 h. No column virtualization
in that number, and we own all of it forever — including the parts that are genuinely fiddly
(pinned columns interacting with a horizontally scrolling container, resize interacting with
`minmax()` tracks).

**TanStack Table.** Headless: it supplies the column model, sorting and virtualization hooks, and
leaves the scroll container, the pinned-column rendering and the header alignment with us — which
is most of the ~9 h. It would be the better answer if the complaint were *styling*, because
headless means our own markup. The complaint is not styling.

**ag-grid Community.** Pinned columns, resize, sort, row *and* column virtualization are its
feature list, and it is MIT.

## Decision

**ag-grid Community 36.1.0** (`ag-grid-angular` + `ag-grid-community`, both MIT, both pinned
exactly), used for the Req review table and for the Modules table — so there is one table system
in the application, not two.

Three constraints on how it is used, each of which exists to keep an existing rule true:

**1. Never `field`, always `colId` + `valueGetter`.** ag-grid reads a dot in `field` as a property
path, so a column declared `field: 'REQ. Priorität'` looks for `row['REQ']['Priorität']` and
renders blank — silently, with no error. Half this project's attribute names would hit that. Every
column therefore carries a synthetic `colId` (`attr-0`, `attr-1`, …) and reads its value through a
`valueGetter`. This is the same rule CLAUDE.md §11 already states — a DOORS attribute name is a
display label and never a key — arriving through a new door.

**2. The theme is our tokens, expressed in SCSS.** ag-grid's Theming API emits every parameter as
a `--ag-*` custom property inside a `:where(...)` selector, which has zero specificity. So a plain
class selector in `styles/_grid.scss` setting `--ag-background-color: var(--sec-paper)` wins
outright. The consequence is that no colour, size or radius is written in TypeScript: the grid
reads the same `--sec-*` ramp as everything else, and the "paper" style of ADR 0003 stays a single
source of truth. This is the direct analogue of the M3 token overrides in `_theme.scss` — and, like
those, it means **no rule ever targets an `.ag-*` internal class**, only the documented parameters.

**3. Comments stay this view's own state.** The Comment column is a custom cell renderer holding a
real `<input>`, wired back to the component's `ref`-keyed edit buffer. ag-grid's own editing model
is not used: R7 and `REQ_REVIEW.md` §5.2 require one buffer, one Save, one transaction, and grid
cell editing would put a second staging concept next to it.

## Consequences

The five defects above are answered by configuration rather than by code we maintain, and the two
fatal ones are answered by pinning alone: ID pinned left, Comment pinned right, so the identity of
a row and the reason for the view are both always on screen no matter how far right the attributes
run.

`REQ_REVIEW.md` §5's owed sorting arrives for free, including the "control resets to document
order" it asks for — document order is `__sortKey` and the reset is one `applyColumnState` call.

**Costs, stated plainly.** This is the first UI dependency outside Angular Material, so the
frontend now has two component vocabularies and a reviewer has to know which one a given table
speaks. It is ~1.5 MB unminified of grid code for a table. And ag-grid's release cadence is fast:
the version is pinned exactly, and an upgrade is a deliberate act, not a `^` drifting under us.

We accept those because the alternative is nine hours to arrive at less, and because the thing
being bought — column virtualization at seventy-eight attributes — is not something worth building
by hand.

Rejected explicitly: ag-grid *Enterprise*. Nothing in `REQ_REVIEW.md` or
`attribute-policy-checks.md` needs it, and a licensed dependency is a different kind of decision
that this ADR does not make.
