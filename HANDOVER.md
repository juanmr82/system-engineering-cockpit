# Handover

Transient session-to-session note — not project documentation. Delete once its content is
absorbed into commits or superseded.

## State as of 2026-08-05 (end of session 4)

Branch `master`, nothing pushed. `master` is **not** the repo's main branch.

- `b7c1e78 Verify the Req review backend against Neo4j, and unblock the container tests`
- plus the Req review Angular view, committed on top of it.

Session 3's open item — "prove the Req review backend works, then build its Angular view" — is
done on both halves, and the view has now been driven in a browser against the live 984-object
module.

---

## Resume here

Nothing is half-done. The largest unbuilt things, in the order they were specified:

1. **`docs/features/attribute-policy-checks.md`** — the spec is complete and
   `GET /modules/{ref}/checks/attribute-policy` does not exist. It is the natural next feature: the
   Mandatory flag the review dialog now writes has no consumer until it ships.
2. **`GET /api/v1/config/navigation`** — still a TODO that 404s on every page load.
3. The remaining views are still empty states (Statistics, Windchill, SOI views, Functions).

**What could not be exercised, and needs an unsanitised DOORS export:** the whole
attribute-dependent half of the review view. The live export is sanitised, so
`GET /modules/{ref}/attributes` legitimately returns `[]`, nothing can be marked Visible,
Mandatory or Verification, and the table shows only its five fixed columns. The component tests
cover that logic against a fixture that has attributes; the browser cannot, on this data.

### What was checked in the browser, and what it cost

Driven against the live module: the empty state, the module selector, 984 rows in document order,
virtual scrolling, search (`1 shown / 984 in module`), the requirements-only filter (**487 shown**,
matching the backend's `requirementLike` count exactly), the detail panel from a row id and from an
incoming reference, a comment typed → saved → verified in the graph → cleared → verified gone, the
exit guard with its singular wording and Keep editing, and the settings dialog. The graph was left
with exactly the one `:__Classification` it started with.

Three defects were found and fixed:

- The module selector's closed control rendered the option's secondary path text too, as one
  run-on string (`SRD/XXX-/Level 1 - System/SRD`). Needs an explicit `mat-select-trigger`.
- A row with several unresolved references repeated "Not yet imported" once per target — three
  identical phrases, clipped, in a 46px row. Unresolved targets are now counted
  (`3 not yet imported`) with the modules named in the tooltip. A placeholder has no id to tell
  one from another anyway, which is why listing them individually was never going to work.
- The requirements-only checkbox wore the Tier-2 accent. That accent means "the application wrote
  this, DOORS did not"; a filter writes nothing, and spending the signal there weakens it.

One trap for the next session: **`Page.captureScreenshot` times out on this app** roughly one call
in five, reporting "the renderer may be frozen". It is not — a frame-gap measurement across the
same interaction showed a worst frame of 18ms. Re-take the screenshot rather than debugging the
app.

---

## What was built this session

### 1. Docker, and why the error lied

`integrationTest` had never run. Testcontainers reported **"Could not find a valid Docker
environment"**, which reads as "the daemon is down" — it was up, and `docker info` worked.

The real cause: docker-java negotiates **Docker API 1.32**, and **Engine 29 rejects anything below
1.40** with a 400 carrying no message. Reproduce it in one line:
`DOCKER_API_VERSION=1.32 docker info` fails, `1.41` succeeds.

Fixed in `backend/build.gradle.kts` by pinning `api.version=1.41` on the `integrationTest` task
(overridable from the command line), with Testcontainers bumped to 1.21.3. Nothing about
`DOCKER_HOST` or the `desktop-linux` context was the problem, though both look like it.

### 2. The review backend, verified

`./gradlew :backend:check :backend:integrationTest` is **green**: 15 Docker-free tests plus
`ReviewFeatureTest` (11) and `ModulesFeatureTest` (3) against a real Neo4j Community container.

`ModulesFeatureTest`'s meta-schema test could never have passed as written: `SHOW INDEXES` returns
**NULL `labelsOrTypes`** for the token-lookup indexes every database ships, and coercing that to a
list throws. It now also asserts `meta_attribute_setting`.

Against the live 984-object module, every expectation in session 3's table held. One correction to
that table: **document order is not id order.** `SRD-1` is followed by `SRD-228`, `SRD-1187`,
because DOORS ids do not ascend down the outline — which is exactly why `__sortKey` exists. A
paged read looks unsorted and is not; check `__sortKey`, never the ids.

Also exercised live: item detail, traces both directions, a comment written, read back and cleared,
with the graph returning to exactly one `:__Classification`.

### 3. The Req review view (`docs/REQ_REVIEW.md` §1–§7)

`features/requirements/review/`: `requirement-review.{ts,html,scss}`, `review-settings-dialog.*`,
`item-detail-panel.*`, `review-api.service.ts`, `review.model.ts`, `review.guard.ts`,
`requirement-review.spec.ts`. New shared `ConfirmDialog`; four new SVG icons (close, save, search,
info) registered in `sec-icons.ts`.

**The table is not a `mat-table`.** Material has no virtual scroll for tables, and the combination
of ~1 000 rows, a dynamic column set and a sticky header is where that hurts. The layout instead
is: one box that scrolls horizontally, holding a header row and a `cdk-virtual-scroll-viewport`
that scrolls vertically. The header is inside the horizontal scroller, so it tracks the columns
sideways and never moves vertically — no scroll listener, no `position: sticky`, no transform
fighting. Header and rows share one `grid-template-columns` string computed from the visible
attribute list.

Consequences worth knowing before changing it: rows are a fixed 46px because virtual scroll
requires it, so every cell truncates with an ellipsis and carries a tooltip; and a DOORS attribute
name is only ever a display label — cells are addressed by index, never by name.

Other decisions:

- **Saving does not reload the table** (§5.2). The save response is applied as an overlay keyed by
  `ref`, which is what that response payload is for. `commentText()` reads edit → overlay → loaded
  row, and `storedText()` is the baseline an edit is measured against, so typing a comment back to
  its original text stops being an edit.
- **The exit guard is one `CanDeactivateFn`** reading the component instance, plus a
  `window:beforeunload` host binding for tab close. No store, no router-wide guard (R7).
- The selected module is a query parameter, seeded from the route snapshot. On a cancelled discard
  the `MatSelect` is put back by hand — Material has already moved its own value by the time
  `selectionChange` fires, and re-rendering an unchanged binding will not undo that.

---

## Verified / not verified

| | Status |
|---|---|
| `./gradlew :backend:check` | **green** |
| `./gradlew :backend:integrationTest` | **green** — 14 container tests |
| Review endpoints against the live 984-object graph | **green** — reads, comment write, comment delete |
| `npm run lint` / `npm test` / `npm run build` | **green** — 14 tests, 7 of them new |
| The Req review view in a browser, against the live module | **driven end to end** — see above |
| Dynamic attribute columns, mandatory/visible/verification end to end | **not exercisable** on the sanitised export |

---

## Environment

- Neo4j runs natively from `C:\Users\juanm\neo4j\neo4j-community-2026.06.0`, creds
  `neo4j` / `admin123`, no Windows service: `./bin/neo4j.bat console`.
- Docker Desktop 29.4.0. The `neo4j:2026.06.0-community` image is pulled.
- Backend `:8080`; `npm start` → `:4200` (`proxy.conf.json` forwards `/api`).
- Neo4j's HTTP API on `:7474` is the quickest way to inspect the graph:
  `POST /db/neo4j/tx/commit` with basic auth and `{"statements":[{"statement":"..."}]}`. It warns
  that it is deprecated in favour of the Query API; it still works.

### The live graph, as measured

984 `DOORSObject` (all `DOORSTBD` — sanitised export), 318 `:__UNDEFINED` placeholders,
1 `DOORSModule`, 409 `refersTo` of which 343 objects have at least one unresolved target,
33 objects have an incoming link, 1 `:__Classification`. No user attributes at all.

### Traps that cost time

1. **The `Write` tool mangles raw Unicode combining characters** — confirmed again. The accent
   regex in `requirement-review.ts` had to be patched to `/[\u0300-\u036f]/g` at byte level after
   `Write` wrote the literal characters. Check with `grep | cat -A` after writing one.
2. **`git checkout -- <file>` reverts the whole file**, including uncommitted work you meant to
   keep. Cost a restore of four unrelated edits here.
3. `angular.json` changes need a dev-server restart; the symptom is all component CSS silently
   missing.
4. Never run npm as `npm --prefix frontend …` from the repo root. Run it from `frontend/`.

---

## Known gaps

- **`GET /api/v1/config/navigation` is still a TODO** and 404s on every page load. The sidenav's
  hardcoded fallback masks it; this is the one standing console error and it is expected.
- **Inter is not shipped** (`public/fonts/` holds only `.gitkeep`), so the app renders in the Segoe
  UI fallback. The `@font-face` contract is in `styles.scss`.
- **The Material icon font is not self-hosted**, so every icon must be an SVG in `public/icons/`
  registered in `sec-icons.ts`. A `<mat-icon>ligature</mat-icon>` renders as raw text.
- **`GET /items/{ref}/traces` never fills `moduleName`**, though the row payload does — the traces
  endpoints skip the per-page module-name lookup. Nothing consumes it yet (the References column
  reads the row), so it is an inconsistency rather than a bug. Fix when the detail panel starts
  showing links.
- **`docs/features/attribute-policy-checks.md` is not implemented** —
  `GET /modules/{ref}/checks/attribute-policy` does not exist. Its spec is complete.
- **No backend static analysis** (ktlint/detekt); `BACKEND_REVIEW.md` §5 flags it as its own call.
- **O5:** `incomingComplete` is hard-coded `false`. Making it real needs import-coverage tracking
  and no wire change.
- `SE_ITEM_SCHEMA.md` and `DOORS_TO_NEO4J_IMPORTER_SPEC.md` are still stubs, and the DOORS importer
  is still `NotImplementedError`. The live data was loaded by some other means.

---

## Decisions taken, and where they live

`docs/adr/` — 0002 errors and log format, 0003 the paper visual style, 0004 the frontend quality
gate, 0005 the Req review backend. Not to be re-litigated without changing the ADR.

No architectural decisions are pending. The Req review view introduced no new ones: the layout
choice above is a technique, not a rule, and is documented where it lives.
