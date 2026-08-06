# Handover

Transient session-to-session note — not project documentation. Delete once its content is
absorbed into commits or superseded.

## State as of 2026-08-06 (end of session 6)

Branch `master` (**not** the repo's main branch). This session's work is committed; see the
warning in "Resume here §1" before trusting the settings dialog.

**There is uncommitted, staged work in `importers/` that belongs to someone else** — a DOORS
importer refactor (`derivations.py`, `parser.py`, `validator.py`, `importer.py`, `schema.py`,
`reporter.py`, `exceptions.py`, `tests/`) plus `docs/DOORS_IMPORTER_INFO`. It was left staged and
untouched, deliberately excluded from this session's commit.

---

## What was built

### 1. Both tables moved to ag-grid Community (ADR 0006)

`ag-grid-angular` + `ag-grid-community` **36.1.0, pinned exactly**, MIT. It answered the five
defects the previous session logged: ID pinned left, Comment pinned right, resize, sort with a
*Document order* reset, and row **and column** virtualization.

| File | What it is |
|---|---|
| `core/grid/sec-grid.ts` | module registration + `secGridOptions()` + shared column defaults |
| `core/grid/grid-testing.ts` | `flushGridFrames()`, test-only |
| `styles/_grid.scss` | the whole grid theme, global |
| `review/cells/`, `modules/cells/` | the cell renderers |

### 2. The Req review table, reworked against real data

Description replaced Name; flat list with headings styled H1–H6 by `objectLevel` on a light blue
ground; table structure hidden; wrapping cells; column rules; a spreadsheet-style comment editor;
References as a vertical list.

### 3. Consistency checks, surfaced in an Issues column

Two kinds in one list (`REQ_REVIEW.md` §5.3):

- **fixed** — always run, not configurable. Currently "Object Type shall not be TBD", excluding
  table structure and `__UNDEFINED`.
- **configured** — the module's mandatory-attribute policies.

Computed **on read**, never stored, in the query that already loads the rows. The decisive reason
is written up in §5.3 and is worth reading before anyone proposes caching it: the verdict depends
on user-editable policy, not just on the import, so there is nothing to backfill and nothing to go
stale.

### 4. System level is editable from the Modules table

A `<select>` in the cell writing to a `ref`-keyed buffer, batch-saved behind a save icon —
the same shape as the review table's comments. New endpoint
`POST /api/v1/modules/system-levels` (not `{ref}`-scoped: the batch spans modules). Coloured
`--sec-level-0` … `--sec-level-4`, green → teal → blue → purple → magenta.

---

## Resume here

### 1. ⚠ The review settings dialog lost data once, and it was never explained

**Symptom, observed once:** opening the Req review attribute dialog, un-ticking a single
*Mandatory* checkbox and saving deleted **all 9 mandatory policies and 8 of the 10 visible flags**
on Segment. The data was restored by hand and verified.

**It could not be reproduced afterwards**, and everything checked came back clean:

- a container test posts the exact shape the dialog sends — every attribute's absolute state with
  one flipped off — and the writer keeps the others (`turning one mandatory attribute off leaves
  the others alone`);
- the dialog's real payload was intercepted in the browser and was correct: 8 `mandatory:true`,
  only the un-ticked one `false`.

So there is a **confirmed data loss with no identified cause**. Treat the dialog as suspect. The
next lead worth pulling: whether the dialog can seed its Signal Forms model from a *stale*
`moduleAttributes` resource — it is created per dialog open from `ModulesApiService`, and a
mandatory list that arrived empty would produce exactly this payload while still displaying
correctly if the display read a different source.

**Do not verify dialogs against live modules.** Seed a scratch module first. That mistake is what
caused the loss.

### 2. Not implemented

- `GET /modules/{ref}/checks/attribute-policy` — the aggregate report endpoint. Still specified,
  still unbuilt; the review table does not need it (it computes per row inside `/objects`).
- `GET /api/v1/config/navigation` — still a TODO, still 404s on every page load. The sidenav's
  hardcoded fallback masks it. **It is the one standing console error and it is expected.**
- The Modules **settings dialog** still has the pre-rework shape (mandatory-only tab, no search).
  The pattern to copy is `review-settings-dialog.*`. ~2 h.
- Statistics, Windchill, SOI views, Functions are still empty states.

### 3. Open questions the user raised and did not settle

- **Should mandatory attributes be definable once and applied to every module?** Today the policy
  is per module (R2, Shape B). The snag is that modules do not share an attribute schema — SRD has
  78 attributes, Segment 53 — so a global rule would flag requirements in modules that simply do
  not have that attribute. A middle option was offered and not taken up: keep per-module policies
  but add "copy mandatory settings from another module" to the dialog.
- **The system-level colour ramp reuses two semantic hues** (`#009F4D` "verified", `#0077C8`
  Tier-2). `CLAUDE.md` §8 records the exception and its fence. The accepted risk, stated there: a
  green chip can read as "good" and magenta as "bad", which is not what L0 and L4 mean. If that
  reads wrong in use, a single-hue light-to-dark ramp keeps the ordering without the connotation.

---

## Verified / not verified

| | Status |
|---|---|
| `./gradlew check` | **green** |
| `./gradlew :backend:integrationTest` | **green** — including 5 new container tests |
| `npm run lint` / `npm test` / `npm run build` | **green** — 33 tests, initial bundle 165 kB |
| Review table: pinning at full scroll, sort, reset, comment save/clear, wrapping, heading styles | **driven end to end** |
| Issues column: both check kinds | **driven** — Segment shows 86 mandatory + 79 TBD = 165 |
| System level editing: edit → save → persisted → restored | **driven end to end**, ending in the original state |
| The review **settings dialog** | **suspect** — see §1 |
| Column resize by dragging | **simulated** via mouse events, not dragged by hand |

The graph was left as found: SRD `L1`, Segment `L2`, Segment's 9 mandatory policies intact.

---

## Environment

- Backend `:8080` (`SEC_NEO4J_USER=neo4j SEC_NEO4J_PASSWORD=admin123 ./gradlew :backend:run`);
  frontend `npm start` → `:4200`; Neo4j native from
  `C:\Users\juanm\neo4j\neo4j-community-2026.06.0` (`./bin/neo4j.bat console`), creds
  `neo4j` / `admin123`. **All three were left running.**
- **Restart the backend after any backend change** — `./gradlew :backend:run` serves the code it
  started with. `Get-NetTCPConnection -LocalPort 8080 -State Listen` → `Stop-Process`.

### Traps that cost time, in rough order of how much

1. **A backgrounded Chrome tab measures the grid wrong.** `requestAnimationFrame` does not fire
   and `ResizeObserver` goes quiet, so ag-grid reports `scrollWidth - clientWidth === 0` while the
   scrollbar is plainly visible, and renderers appear not to instantiate. **Take a screenshot to
   force a paint and trust the screenshot over the measurement.**
2. **Column virtualization means an absent column proves nothing** — scroll first, confirm
   `scrollLeft` actually moved, then assert.
3. **Never set `position` on an ag-grid cell.** It lays cells out `position: absolute` with an
   inline `left`/`right`; overriding to `relative` discards the offset. Invisible while a column
   is the only one pinned to its side, and drops the cell onto its neighbour when a second joins.
4. **ag-grid's stylesheet is injected after ours**, so at equal specificity it wins — overriding
   one of its structural rules needs two of our own classes, never an `.ag-*` name.
5. **`autoHeight` nests cell content in content-sized wrappers**, which collapses a textarea to
   its intrinsic 20-column width whatever `width: 100%` says.
6. **`<select>` with `[value]` and `@for` options** binds before the options exist and silently
   falls back to the first one. Bind `[selected]` on the options instead.
7. **In specs:** `whenStable()` never resolves with two `httpResource`s in flight (it times out
   instead of failing), and `TestBed.resetTestingModule()` inside a test corrupts the rest of the
   suite. `reload()` schedules a refetch rather than issuing it — let a macrotask pass first.
8. **`Page.captureScreenshot` still times out intermittently.** Re-take it; do not debug the app.
9. **Clicking a `mat-select` option by screenshot coordinates is unreliable** — navigate by the
   `?module=<ref>` query parameter instead.

---

## Decisions

`docs/adr/` — 0002 errors and log format, 0003 the paper visual style, 0004 the frontend quality
gate, 0005 the Req review backend, 0006 ag-grid Community. Not to be re-litigated without changing
the ADR.
