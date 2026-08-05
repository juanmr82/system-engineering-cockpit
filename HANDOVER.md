# Handover

Transient session-to-session note — not project documentation. Delete once its content is
absorbed into commits or superseded.

## State as of 2026-08-06 (end of session 5)

Branch `master`, working tree clean, nothing pushed. `master` is **not** the repo's main branch.

```
c693c09 Rework the attribute settings dialog for modules with 78 attributes
4aeffb8 Discover attributes from the whole module, not the first 25 objects
58bcce2 Give the review table enough scroll buffer to survive a flick
77b6542 Fix three things the Req review view only showed in a browser
7092eba Build the Req review view
b7c1e78 Verify the Req review backend against Neo4j, and unblock the container tests
```

The Req review view (`docs/REQ_REVIEW.md` §1–§7) is built, driven in a browser against real data,
and works. **Two real DOORS modules are now imported**, which changed what "works" means — see the
next section.

---

## Resume here

### 1. The table is the open problem, and it needs a decision first

With real data the review table has three defects that share one cause — it was designed against a
module with five columns and now has fourteen:

| Defect | Why it matters |
|---|---|
| **ID scrolls out of view** — by column 6 you cannot tell which requirement a row is | Fatal for review work |
| **Comment is ~9 columns off-screen** — the point of the view is unreachable without scrolling right | Fatal |
| **No column resize** — `Object Text` is a full requirement statement truncated to one line | Severe |
| **No sorting** — `REQ_REVIEW.md` §5 asks for it; it was never built | Owed |
| **No column virtualization** — tick 40 attributes and every rendered row holds 45 cells | Degrades as it is used |

Pinned columns + resize + sort + column virtualization is exactly ag-grid Community's (MIT) feature
list. **The decision is open and it is the user's:**

- **ag-grid Community** — ~10–12 h for the review table, +2 h to move the Modules view onto it so
  there are not two table systems. Everything above arrives working. Costs: a second theming
  system next to Material's M3 tokens (the user said they are flexible on ag-grid's styling, which
  removes most of that), and a trap — ag-grid's `field` treats a dot as a property path, so
  `REQ. Priorität` silently renders blank. Every column must use `colId` + `valueGetter`.
- **By hand** — sticky pinned columns ~3 h, resize ~4 h, sorting ~2 h ≈ 9 h. Barely cheaper, no
  column virtualization, and we own it forever.
- **TanStack Table** was considered and is the weaker fit *for this complaint*: headless means the
  scroll container and pinning land back on us. It would be the better answer if the objection were
  the styling.

Recommendation on the evidence: **ag-grid Community**. Do not start the table until this is settled,
because the two paths share almost no work.

### 2. The Modules settings dialog has the same problem the review dialog just had

`features/requirements/modules/module-settings-dialog.*` still has the pre-rework shape: a
mandatory-only tab, no search, a small scroll window. Against SRD's 78 attributes it is as awkward
as the review dialog was. The pattern to copy now exists in `review-settings-dialog.*` — one
CSS-grid list, `shared/text/normalize.ts` for the search, per-column All/None with `markAsDirty()`.
**~2 h, independent of the ag-grid decision.**

### 3. Smaller, already specified

- **`docs/features/attribute-policy-checks.md` is not implemented.** The spec is complete and
  `GET /modules/{ref}/checks/attribute-policy` does not exist. The Mandatory flag the dialog writes
  has no consumer until it ships — and 8 policies are already stored on Segment.
- **`GET /api/v1/config/navigation` is still a TODO** and 404s on every page load. The sidenav's
  hardcoded fallback masks it; it is the one standing console error and it is expected.
- Statistics, Windchill, SOI views and Functions are still empty states.

---

## What was done this session

### The two-module import went clean

Checked after loading: **0 stale `:__UNDEFINED:DOORSObject`** — the risk that a placeholder keeps
its `__UNDEFINED` label after the referenced module is imported did not materialise, so the loader
clears it. **461 cross-module references now resolve**, which is the path that could never be
exercised before. 318 placeholders remain, pointing at modules that are genuinely not imported.

| Module | Objects | Attributes | Configured |
|---|---|---|---|
| SRD | 977 | 78 | system level L1 |
| Segment | 903 | 53 | L2, 9 visible, 8 mandatory |

### A silent data bug, found and fixed (`4aeffb8`)

**Attribute discovery sampled the first 25 objects and lost `Object Text` on SRD.** 774 of 977
objects carry it and 203 do not; the 25 the planner returned were among the 203. The module's most
important attribute never reached the settings dialog and could not be shown in the table — it
looked like a gap in the export, not a bug. Discovery now reads the whole module.

The sample was never buying anything: through the driver, 25 objects and 977 both answer in
**~17ms**. Beware measuring this with Neo4j's HTTP API on `:7474` — it charges ~2.1s for `RETURN 1`,
which is how the full scan first looked like a 2-second regression.

### The dialog rework (`c693c09`)

Two stacked `mat-table`s became one CSS-grid list under one header; added search, per-column
All/None over the filtered rows, and a taller dialog. Material caps dialog content at 65vh whatever
the dialog's height is — overridden on our own class, not on a `.mat-mdc-*` internal.

Two details worth keeping in mind:

- **Bulk actions must call `markAsDirty()`.** Writing through a Signal Forms field's `value` signal
  updates the model but does not mark the form dirty, and Save is gated on dirty — without it the
  change is unsaveable.
- **The search filters the view, never the payload.** Save sends the absolute state of all 78
  attributes; sending only the visible rows would silently unset everything filtered out of sight.
  That is what the last dialog test guards.

### Scroll buffer (`58bcce2`) and three browser-found fixes (`77b6542`)

The viewport had CDK's default 100px/200px buffers — three spare rows at 46px — so a flick showed
blank bands until the re-render landed. Now one screen of buffer, two at most. Also fixed: the
module selector leaked the option's path into the closed control, unresolved references repeated
"Not yet imported" once per target and clipped, and the requirements-only checkbox wore the Tier-2
accent (which means "the app wrote this", and a filter writes nothing).

---

## Verified / not verified

| | Status |
|---|---|
| `./gradlew :backend:check` | **green** |
| `./gradlew :backend:integrationTest` | **green** — 15 container tests |
| `npm run lint` / `npm test` / `npm run build` | **green** — 20 tests |
| Review view in a browser: table, search, filter, detail panel, comment save/clear, exit guard | **driven end to end** against the live modules |
| The **reworked** dialog rendered | **not seen** — the Chrome extension disconnected after the rework. Covered by 6 tests and DOM measurements |
| Cross-module references rendering in the table | **not looked at** — 461 now resolve, so the References column finally has real ids and module names to show |

---

## Environment

- Backend `:8080` (`SEC_NEO4J_USER=neo4j SEC_NEO4J_PASSWORD=admin123 ./gradlew :backend:run`);
  frontend `npm start` → `:4200`. Both were left running.
- Neo4j runs natively from `C:\Users\juanm\neo4j\neo4j-community-2026.06.0`, creds `neo4j` /
  `admin123`, no Windows service: `./bin/neo4j.bat console`.
- Docker Desktop 29.4.0, `neo4j:2026.06.0-community` pulled. Container tests need the
  `api.version=1.41` pin already in `backend/build.gradle.kts` — Engine 29 rejects the 1.32 that
  docker-java negotiates by default, and reports it as "Could not find a valid Docker environment".

### Traps that cost time this session

1. **`./gradlew :backend:run` keeps serving the code it started with.** A backend left running from
   an earlier session served the old attribute discovery for the whole browser pass, so the dialog
   showed 76 attributes after the fix was committed. Restart it after any backend change:
   `Get-NetTCPConnection -LocalPort 8080 -State Listen` → `Stop-Process`.
2. **Neo4j's HTTP API on `:7474` costs ~2.1s per request regardless of the query.** Fine for
   inspecting the graph, useless for timing it. Time through the app's endpoints instead.
3. **`Page.captureScreenshot` intermittently times out on this app** ("the renderer may be frozen")
   and dialog screenshots come back looking translucent. Both are the capture pipeline: the surface
   measures `rgb(255,255,255)` at opacity 1, and frame-gap sampling showed a worst frame of 18ms.
   Re-take the screenshot; do not debug the app. Measure with `javascript_tool` when in doubt —
   but note `requestAnimationFrame` does not fire in a non-focused tab, so rAF loops hang.
4. **The `Write` tool mangles raw Unicode combining characters.** The accent range must read
   `/[\u0300-\u036f]/g`; check with `grep | cat -A` after writing, and patch at byte level with
   Python if needed. It is now in one place: `shared/text/normalize.ts`.
5. **`git checkout -- <file>` reverts the whole file**, including uncommitted work meant to be kept.

---

## Known gaps that are not on the critical path

- **`GET /items/{ref}/traces` never fills `moduleName`**, though the row payload does. Nothing
  consumes it yet (the References column reads the row). Fix when the detail panel links out.
- **`incomingComplete` is hard-coded `false`** (O5). Making it real needs import-coverage tracking
  and no wire change.
- **A module node missing `__id` or `__name` would 500 the whole modules list** — those two are read
  with no fallback while `lastModified` and `path` have defaults, and Community cannot enforce
  property existence (§7). Two-line defensive fix, not yet done.
- **Inter is not shipped** (`public/fonts/` holds only `.gitkeep`); the app renders in Segoe UI.
- **The Material icon font is not self-hosted**, so every icon must be an SVG in `public/icons/`
  registered in `core/icons/sec-icons.ts`. A `<mat-icon>ligature</mat-icon>` renders as raw text.
- **No backend static analysis** (ktlint/detekt); `BACKEND_REVIEW.md` §5 flags it as its own call.
- `SE_ITEM_SCHEMA.md` and `DOORS_TO_NEO4J_IMPORTER_SPEC.md` are still stubs, and the DOORS importer
  in this repo is still `NotImplementedError` — the live data is loaded by other means.

---

## Decisions

`docs/adr/` — 0002 errors and log format, 0003 the paper visual style, 0004 the frontend quality
gate, 0005 the Req review backend. Not to be re-litigated without changing the ADR.

**One decision is pending and it is the user's: ag-grid Community for the tables, or hand-rolled
pinning, resize and sorting.** Everything needed to decide it is in "Resume here" §1. If ag-grid is
chosen it deserves an ADR 0006, because it is the first UI dependency outside Angular Material and
the reasoning (pinned columns and column virtualization at 78 attributes, not "grids are nice")
should survive the choice.
