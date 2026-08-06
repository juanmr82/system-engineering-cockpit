# Handover

Transient session-to-session note — not project documentation. Delete once its content is
absorbed into commits or superseded.

## State as of 2026-08-06 (end of session 7)

Branch `master` (**not** the repo's main branch). This session's work is committed as
`6fe5450`, "Add the Breakdown tab". Session 6's warning about the review settings dialog
(§1 below) is carried forward unchanged — nothing this session touched it.

**There is still uncommitted, staged work in `importers/` that belongs to someone else** — the
DOORS importer refactor (`derivations.py`, `parser.py`, `validator.py`, `importer.py`,
`schema.py`, `reporter.py`, `exceptions.py`, `tests/`) plus `docs/DOORS_IMPORTER_INFO`. Left
staged and untouched for the second session running, deliberately excluded from this
session's commit.

---

## What was built

**The Breakdown tab** — the second tab of the Req review detail panel, specified in
`docs/requirement-breakdown-tree.md`, which is now committed with a §10 recording what the
implementation changed about it. Read §10 before changing any of this; the decisions in it were
made against real data and each one reversed an earlier attempt.

`GET /api/v1/items/{ref}/breakdown?maxDepth&maxNodes` walks `refersTo` up to every root, then
back down over everything decomposing those roots, and returns nodes + edges + roots. Files:

| File | What it is |
|---|---|
| `graph/cypher/BreakdownCypher.kt` | four statements — edges up, edges down, nodes, verification attributes |
| `source/doors/BreakdownProjection.kt` | the two-phase BFS, the node cap, the back-edge DFS |
| `api/dto/BreakdownDtos.kt` | the wire types |
| `review/breakdown/breakdown.model.ts` | `buildTree` / `flatten` — the DAG-to-forest rendering |
| `review/breakdown/breakdown{,-row}.*` | the view |

Four things worth knowing before touching it:

1. **The traversal is a Kotlin loop over one query per level, not one var-length pattern.**
   Neo4j will not take a parameter as a var-length bound, so a single statement has to bake a
   literal in — and then `maxDepth=2` costs what `maxDepth=12` costs. A query per level makes
   both bounds real and truncation exact. §10.2.
2. **A requirement is drawn under every parent it refines.** §3B of the spec (draw once, "also
   refines" chips) shipped first and left SEG-REQ-1247 missing from the second tree it belongs
   to. Before switching, the duplication cost was measured on the imported data rather than
   assumed: worst case 40 rows over 31 nodes, so the 500-row cap is a guard, not a working
   limit. The cascade this implies is real and is documented in §10.1 — a branch under two
   parents appears under both wherever it appears, which is why the specs assert 4 copies of
   `CMP1` and not 3.
3. **Nothing in the tree is clickable.** The spec left "should a node re-root the view" open; it
   was built, the user rejected it, and the twisty is now the only control on a row. A spec asserts
   this so it does not creep back.
4. **Nothing is stored.** Verification attributes are read from `:__AttributeSetting` per call;
   the description is derived server-side (mirroring `review-table.model.ts`'s `describe()`, both
   sites commented as pointing at each other) because the alternative was a 78-attribute bag per
   node for two strings.

Three panel-level changes came out of using it, all in the commit message and in §10.7:

- The panel **leads with the DOORS id**. It was headed by `__name` = `Object Text`, and a
  sanitised export blanks user attributes, so every object showed the same sentence.
  `ItemDetailDto.id` is null for anything with no id of its own — never invented.
- The opened requirement gets a navy rail, a navy wash and the words *The requirement you
  opened*, on every copy. The wash is a **fourth exception** to "colour is a rail, never a
  background", recorded in `CLAUDE.md` §8 with its fence.
- The **level badge stays when unset**, empty and outlined. Dropping it un-aligned every id in
  the column. `.sec-level--none` moved into the shared `system-level-scale` mixin on its second
  use, so `module-level-cell.scss` no longer carries its own copy.

---

## Resume here

### 1. ⚠ The review settings dialog lost data once, and it was never explained

**Unchanged from session 6, and still unexplained.** Opening the Req review attribute dialog,
un-ticking one *Mandatory* checkbox and saving deleted **all 9 mandatory policies and 8 of the
10 visible flags** on Segment. Restored by hand, verified, never reproduced. A container test
posts the dialog's exact shape and keeps the others; the real payload was intercepted in the
browser and was correct.

So: **a confirmed data loss with no identified cause. Treat the dialog as suspect.** The lead
worth pulling is whether the dialog can seed its Signal Forms model from a *stale*
`moduleAttributes` resource — created per dialog open from `ModulesApiService`, and a mandatory
list arriving empty would produce exactly this payload while still displaying correctly if the
display read a different source.

**Do not verify dialogs against live modules.** Seed a scratch module first; that mistake is
what caused the loss.

### 2. Not implemented

- `GET /modules/{ref}/checks/attribute-policy` — the aggregate report endpoint. Specified,
  unbuilt; the review table does not need it (it computes per row inside `/objects`).
- `GET /api/v1/config/navigation` — still a TODO, still 404s on every page load. The sidenav's
  hardcoded fallback masks it. **It is the one standing console error and it is expected.**
- The Modules **settings dialog** still has the pre-rework shape (mandatory-only tab, no
  search). The pattern to copy is `review-settings-dialog.*`. ~2 h.
- Statistics, Windchill, SOI views, Functions are still empty states.

### 3. Open questions the user raised and did not settle

- **Should mandatory attributes be definable once and applied to every module?** Today per
  module (R2, Shape B). Modules do not share an attribute schema — SRD 78, Segment 53 — so a
  global rule would flag requirements in modules that do not have the attribute. A middle
  option was offered and not taken up: keep per-module policies, add "copy mandatory settings
  from another module".
- **The system-level colour ramp reuses two semantic hues** (`#009F4D` "verified", `#0077C8`
  Tier-2). `CLAUDE.md` §8 records the exception and its fence. If a green chip reading as
  "good" turns out to mislead, a single-hue light-to-dark ramp keeps the ordering without the
  connotation. The Breakdown tab now shows these badges in a second place, so this reads on
  more screens than it did.

---

## Verified / not verified

| | Status |
|---|---|
| `./gradlew check` | **green** |
| `./gradlew :backend:integrationTest` | **green** — including 11 new container tests (10 breakdown + 1 panel id) |
| `npm run lint` / `npm test` / `npm run build` | **green** — **62 tests**, initial bundle 165 kB |
| Breakdown against SEG-REQ-1247 | **driven end to end** — two parents, both roots, the placeholder leaf, collapse/expand, the subject marker on every copy |
| A real verification attribute | **driven** — SRD-1158 shows a Verification Requirement value, so that path is exercised with live data, not only in tests |
| Level badge alignment, set vs unset | **measured** in the browser — 24 px empty, 25 px with `L1`/`L2` |
| Cycle handling | **tests only** — the imported modules contain no `refersTo` loop, so the fixture is the only place it has been seen |
| Truncation / the 500-row cap | **tests only** — real data does not reach either bound |
| The review **settings dialog** | **still suspect** — see §1 |

The graph was left exactly as found. Nothing was written this session.

---

## Environment

- Backend `:8080`, frontend `npm start` → `:4200`, Neo4j native from
  `C:\Users\juanm\neo4j\neo4j-community-2026.06.0` (`./bin/neo4j.bat console`). **All three were
  left running**, backend restarted after the `id` field was added.
- **Credentials are not written down here.** `scripts\win\sec-env.local.ps1` holds them, is
  git-ignored, and is what `sec-env.ps1` reads — see `docs/RUNNING.md` §1.1. Dot-source that and
  the three scripts beside it start everything with the environment already set.
- **Restart the backend after any backend change** — `./gradlew :backend:run` serves the code it
  started with. `Get-NetTCPConnection -LocalPort 8080 -State Listen` → `Stop-Process`.

### Traps that cost time, in rough order of how much

New this session, first:

1. **A component inside an `@if` on a resource unmounts while that resource reloads.** The
   detail panel's `mat-tab-group` lived inside `@if (detail.value(); as item)`, so pointing the
   panel at another row threw a reviewer off the Breakdown tab and back to Attributes. Hoist
   anything holding UI state outside every `@if` and let each branch handle its own loading.
   The regression test asserts **while both requests are in flight** — that is the only window
   it happens in, and a test that settles first sees nothing.
2. **`doc.twisty` draws a triangle out of three borders and says nothing about the fourth.** On
   a `<button>` the UA's `border-right` survives and it renders as an hourglass. Zero the fourth
   side *after* the include; `border: 0` before it takes the triangle with it. Now in
   `CLAUDE.md` §6.
3. **`outline-chip` uppercases.** Fine for a two-word label, wrong for a sentence — `CLAUDE.md`
   §8 allows caps only for three words or fewer. Set `text-transform: none` when the chip
   carries prose.
4. **The driver's `Node` has a member `id()`.** An extension function named `id()` on it is
   silently shadowed by the member and never runs. The one in `BreakdownProjection.kt` is called
   `nodeKey()` for that reason — do not "tidy" it back.
5. **jsdom has no layout**: `scrollIntoView` does not exist. Call it optionally, or do not rely
   on it.

Carried forward, still true:

6. **A backgrounded Chrome tab measures the grid wrong** — `rAF` does not fire and
   `ResizeObserver` goes quiet. Take a screenshot to force a paint and trust it over the
   measurement.
7. **Column virtualization means an absent column proves nothing** — scroll, confirm
   `scrollLeft` moved, then assert.
8. **Never set `position` on an ag-grid cell**; **ag-grid's stylesheet is injected after ours**,
   so overriding a structural rule needs two of our own classes, never an `.ag-*` name.
9. **`autoHeight` nests cell content in content-sized wrappers**, collapsing a textarea to its
   intrinsic width.
10. **`<select>` with `[value]` and `@for` options** binds before the options exist. Bind
    `[selected]` on the options.
11. **In specs:** `whenStable()` never resolves with an `httpResource` in flight — it times the
    spec out rather than failing it, so use `detectChanges()` plus a macrotask when a click
    starts a request. `TestBed.resetTestingModule()` inside a test corrupts the rest of the
    suite. `reload()` schedules a refetch rather than issuing it.
12. **`Page.captureScreenshot` times out intermittently.** Re-take it; do not debug the app.
13. **Clicking a `mat-select` option by screenshot coordinates is unreliable** — navigate by the
    `?module=<ref>` query parameter instead.

---

## Decisions

`docs/adr/` — 0002 errors and log format, 0003 the paper visual style, 0004 the frontend quality
gate, 0005 the Req review backend, 0006 ag-grid Community. Not to be re-litigated without
changing the ADR.

`docs/requirement-breakdown-tree.md` §10 is the same kind of record for the Breakdown tab: six
of its seven entries reverse or amend something the spec asked for, and each says why.
