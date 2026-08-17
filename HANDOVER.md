# Handover

Transient session-to-session note — not project documentation. Delete once its content is
absorbed into commits or superseded.

## State as of 2026-08-17 (session 35) — the two backend test items closed; a fifth Access screen
## (Containers) built, and the Unassigned/Defaults product gap closed a different way than session
## 34 sketched

Branch **`feature/access-control`**, continuing session 34 (below). Two unrelated pieces of work,
plus two fixes to session 34's own uncommitted diff found on resume.

**1. Two fixes to what session 34 left uncommitted, found before anything else was touched.**
`deploy/keycloak/sec-realm.json`'s `sec-access-manager` realm role name had been corrupted to
`"sI "` in the diff session 34 described as "the ID-token mapper fix, still uncommitted" — an
unrelated stray edit, not part of that fix. Restored to `"sec-access-manager"`. Separately,
`docs/JIRA_TOKEN_FOR_EXAMPLE_GIT_IGNORE.md` — a real-looking Atlassian API token and host, sitting
untracked and **not actually gitignored** despite its own filename — added to `.gitignore`. Neither
was committed as-is; worth checking `git diff` against a session's own summary before trusting it
literally.

**2. The two backend test items session 34's "Resume here" named, both done, both docker-verified
live:**

- Extended `AccessAdminServiceTest.kt` (not `VisibilityMatrixTest.kt` as session 34 suggested —
  that file's `@BeforeAll`-built fixture is shared, unordered, across all its tests, and adding new
  categories/objects to it risked silently inflating another test's exact-count assertions;
  `AccessAdminServiceTest` already resets the graph `@BeforeEach` and already wires
  `AccessResolver`+`AccessAdminService` together, so it is the safer home) with two tests proving
  `AccessResolver.invalidate()` closes the phase-2 staleness gap **live**, no restart: a category
  created and granted resolves into the caller's `AccessSet` on the very next `resolve()` call, but
  the module's *objects* stay invisible until `AccessReconciler.reconcile()` actually tags them —
  reconcile itself needs no second resolve, since it never touches `AccessResolver`. A second test
  does the same for a `seesAll` flip.
- New `AccessAcceptanceTest.kt` — seeds a real JIRA project via the actual `JiraImporter`/
  `JiraGraphWriter` against the committed `docs/JIRA.json` export (no hand Cypher), then drives
  create-category → grant → assign (from the Unassigned queue) → reconcile through the real
  `AccessAdminService`/`AccessReconciler`, asserting visibility flips from 0 rows to all 9 issues in
  the project. JIRA rather than a literal DOORS module: DOORS' importer is a separate Python
  process outside the backend (a JVM test cannot run it without shelling out — the exact question
  session 32 parked as its own future feature); JIRA's runs in-process (ADR 0013) and is the only
  source that is both a real importer and a real container-bearing source reachable from a Kotlin
  test without new machinery. Windchill also runs in-process but is `containerless`, so it never
  reaches the Unassigned queue and could not exercise the "assign" step.

**3. A fifth Access screen, "Containers" — the user's own correction to session 34's sketched fix.**
Session 34's handover proposed *retiring* the Unassigned screen's framing (adding a `state=all`/
`assigned` toggle to the same screen). Redirected by the user: **keep Unassigned exactly as it is**
— the never-yet-graded queue an access manager works right after an import — and add a **second,
general screen** that lists every container of every source, categorised or not, and lets an
access manager change any container's grant at any time. Two capabilities named explicitly: (1)
change the grant of any container on demand, (2) keep assigning from the Unassigned queue exactly
as before.

Backend: `AccessCypher.containersWithCategories(containerLabel)` — a new, deliberately simpler
query than `unassignedContainers` (no per-member-match counting, just the container's own current
direct category set), same §16.2a-shaped exemption from `visible()` and the same reasoning: an
access manager has to be able to find a container to re-grade it even when their own account
cannot yet see it through a category. New domain type `ContainerCategories`, new
`AccessAdminService.listContainers(source, q)`, new DTOs, and `GET /access/containers` now branches
on `?state=unassigned` (unchanged) vs `?state=all` (new) rather than 400ing on anything but the
first — exactly what that route's own comment said a second state would do. `AccessGuardTest` and
`GraphNamesTest` both extended (indices 32-33, one `containersWithCategories(...)` call per
distinct `containerLabel`, same grouping `unassignedContainers` already uses). Three new tests in
`AccessAdminServiceTest.kt`, docker-verified.

Frontend: new `features/access/containers/` (`AccessContainers`, ag-grid list — Container / Source
/ Categories / Actions — with search, mirroring Unassigned's own shell) and `ContainerActionsCell`
(Edit, and — only once there is something to clear — Clear). Reuses `AssignCategoriesDialog`
verbatim for Edit, extended with an `initialSelection` field so it pre-fills from the row's current
categories instead of assuming empty; `canConfirm` was deliberately **not** relaxed to allow an
empty confirm, to avoid changing Unassigned's own already-shipped behaviour — Clear is a separate,
explicit action instead, behind `ConfirmDialog`, shown only when there is something to clear. New
nav item `access-containers` between Grants and Not assigned, in both `nav-group.ts`'s fallback and
`application.yaml`.

**One real bug caught by the test suite, not by inspection**: `layout/sidenav/sidenav.ts` filters
nav items **per item**, via its own `ITEM_ROLE` map — not per group, despite `nav-group.ts`'s own
comment reading "the sidenav role-filters this group client side," which is only true today because
every existing item in the group happens to be in the map. Adding `access-containers` to
`DEFAULT_NAV_GROUPS` without adding it to `ITEM_ROLE` left the new link visible to every signed-in
user, not only access managers — caught immediately by `sidenav.spec.ts`'s existing "hides the
Access group entirely for a caller without the role" test failing. Fixed by adding the missing map
entry. Worth remembering next time a nav item is added: the group-level framing in the docs is a
description of the *effect*, not the *mechanism* — check `sidenav.ts`'s own map.

**A second real trap, in the new screen's own spec**: `AccessContainers.save()` calls both
`this.api.containers.reload()` **and** `this.accessBadge.refresh()` (which itself calls
`summaryResource.reload()`) after a successful write — both schedule a refetch rather than issuing
one, the same established trap, but there are **two** of them here where Categories/Grants/Defaults
each only had one. A test that flushes only the containers reload and not the summary reload
deadlocks `whenStable()` on the second, and because Vitest's `--isolate` is `false`, that hang
cascaded into unrelated failures in files sharing the same worker (`jira-links-dialog.spec.ts`,
`module-settings-dialog.spec.ts`, `review-settings-dialog.spec.ts`) — the same "an unrelated file's
TestBed failure means suspect a deadlock upstream" signature session 33 already named. Fixed by
flushing both after the `setTimeout(0)` that lets the scheduled reloads actually fire.

### Verified

- `mvn -pl backend -am test` — **367/367**.
- `mvn -Pdocker -pl backend -am test` — **218/218**, up from 215 (this session's own two earlier
  additions) before that, up from 212 at session 34's close (+3 AccessAdminServiceTest cases for
  the staleness gap, +1 AccessAcceptanceTest, +3 AccessAdminServiceTest cases for
  `listContainers`/re-grading).
- `npm run lint && npm test && npm run build` from `frontend/` — lint clean, **329/329** (up from
  319), build green; the new `access-containers` lazy chunk confirmed present in a verbose build.
- Committed as three commits on `feature/access-control`: the Keycloak realm-name fix, the
  `.gitignore` entry, and everything else in this entry together.

### Resume here

Phase 6 is code-complete and its acceptance pass (§15.3) already ran clean in session 34. What
remains, in order of what was already known before this session:

1. Nothing new is blocking a merge from this session's own work — it is additive (a new read path,
   a new screen, two new test files) and does not touch anything session 34 already verified on
   screen. Worth a short second look at the new Containers screen live (`ng serve` + real
   Neo4j/Keycloak) before merging, the same "acceptance answered by running the app" standard
   session 34 held itself to for the rest of phase 6 — this session did not do that for the new
   screen specifically, only unit/component-tested it.
2. Machine-auth for `POST /access/reconcile` and the DOORS-upload feature that would retire it
   remain exactly where session 32 parked them — still a separate, larger feature, still not
   started, still needing its own ADR before any code (`access-control.md` §15.3's "direction
   favoured" paragraph has the design reasoning already worked out).

## State as of 2026-08-17 (session 34) — phase 6's manual acceptance pass (step 13) is done; one real gap found while doing it

Branch **`feature/access-control`**, continuing session 33 (below). Drove the acceptance pass live
in Chrome as `sec-dev-admin`/`sec-dev-user` against a real backend/Neo4j/Keycloak, per this
project's own "acceptance answered by running the app" rule.

**1. A real, pre-existing Keycloak bug, unrelated to phase 6's own code**: the built-in "roles"
client scope's realm-role mapper does not put `realm_access.roles` in the **ID token** by default
(`id.token.claim` defaults to `false`) — only in the access token. This backend reads only the ID
token (ADR 0017), so every real-Keycloak-authenticated session has had an empty role set since
phase 1; unit tests never caught it because they use fake principals. Root-caused via a temporary
diagnostic log in `Oidc.kt` (removed again) plus the Keycloak Admin REST API. Fixed the same way
the `groups` claim already was: a client-specific protocol mapper directly on `sec-backend`,
mirroring the `groups` mapper's shape (`id.token.claim: true` explicit). Patched live on the
running dev Keycloak via the Admin REST API, **and** the same mapper added to
`deploy/keycloak/sec-realm.json` under `sec-backend`'s `protocolMappers` — **that file edit is
still uncommitted**, staged only on disk; worth its own small commit. The live-instance patch is
separate and does not need redoing unless the Keycloak container/volume is recreated.

**2. The core acceptance claim is proven**: revoking then re-granting a category through the
Grants UI (as `sec-dev-admin`) takes effect for `sec-dev-user`'s session with **no backend
restart** — confirming `AccessAdminService`'s unconditional `AccessResolver.invalidate()` closes
the phase-2 staleness gap. Also exercised the Categories create/delete round trip live against
real Neo4j (create → toast → row with 0/0 counts → delete → confirm dialog → toast → row gone).

**3. A confirmed product gap, decided with the user, not yet built**: `PUT
/access/containers/{ref}/categories` already supports changing an *already-assigned* container's
categories — it's an unconditional whole-set replace, no state check. But the only read path,
`GET /access/containers?state=unassigned` (`AccessRoutes.kt` ~line 250), hardcodes `state !=
"unassigned"` as a `400` and only returns containers with **zero** categories. Once an access
manager assigns a container, it drops out of the Access UI entirely — there is no screen to find
it again and change the grant. `module-settings-dialog.ts` has no category controls either.

**Decided fix, not yet implemented** (explicitly deferred to a future session — "we dont have
enough credits, so this is in the plan"): retire the "Unassigned" framing. The screen becomes a
general container-grants view listing **every imported container** with its current categories
(or "Unassigned"/empty when it has none), editable at any time via the same
`AssignCategoriesDialog` — pre-populated from the container's current categories instead of
assuming empty. Needs: backend accepts a second `state` value (e.g. `all` or `assigned`) on the
existing route rather than a new one (the route comment already anticipated this: *"named as a
query parameter rather than assumed, so a second state has somewhere to go without a route
change"*); frontend renames/reworks `access-unassigned.*` accordingly, dialog pre-fills selection.

Nothing else from session 33 changed. Environment section below (§ "Environment") still applies;
the backend was restarted mid-session (same env vars, same `mvnw ... exec:java` invocation) with
no code change, purely to pick up nothing new — just a routine restart during the manual pass.

---

## State as of 2026-08-17 (session 33) — phase 6's entire build order is code-complete (steps 1–12); only the manual acceptance pass (step 13) is left

Branch **`feature/access-control`**. **Every backend and frontend step in the phase-6 build order
is done, committed, and verified by the automated suites.** What is left is exactly one thing:
the manual on-screen acceptance pass (§15.3 below) — sign in as `sec-dev-user` and
`sec-dev-admin` against a real backend and confirm the whole loop works end to end. Nothing in
this session touched real infrastructure (no Docker, no Keycloak, no `ng serve`) — everything
below is unit/component-tested only.

Commits, in order: `b9e35e2` (step 4 follow-up, defaults/summary docker tests) · `a650a11`
(step 5, `/auth/me`) · `f74522d` (step 6, `/config/navigation`) · `3ad498a` (step 7, shell/guards)
· `b9fb0aa` (step 8, Categories) · `a3c3d55` (step 9, Grants) · `ecbabca` (step 10, Unassigned)
· `494dba7` (step 11, Defaults) · `e393b8f` (step 12, routing).

Read the sections below in order if you're resuming — each one names the traps found while
building it, and every later step hit a variant of the same few traps, so reading them in order
is genuinely faster than reading only the last one.

Branch **`feature/access-control`**, continuing session 32's backend steps 1–3 (categories, groups
& grants, unassigned queue — all committed and docker-verified already). This session finished the
rest of the backend half of phase 6, then started the frontend, per
`/home/juanmrc/.claude/plans/sorted-baking-sparkle.md`.

### Step 7 (frontend shell/guards) — done, commit `3ad498a`

`AuthenticatedUser`/`AuthStore` gain `seesAll`/`categoryCount`; new `core/auth/roles.ts` mirrors
the backend's `Role` object; five near-identical `extractErrorDetail`/`detailOf` copies collapsed
into one `core/error/problem-details.ts:detailOf(cause, fallback)`; new
`shared/refusal-panel/` built on `EmptyState`, the same way `NotFound` already is;
`nav-group.ts` gains the `access` group matching step 6's `application.yaml` entry; sidenav
role-filters via a small key→role map and overlays the unassigned-container badge from a new
`AccessBadgeService`. **Two real, independent bugs fixed while touching `navigation.service.ts`**:
an unguarded `resource.value()` that threw instead of falling back on a fetch failure, and a wire-
shape mismatch — the backend wraps navigation as `{ groups: [...] }` (`NavigationResponseDto`),
not a bare array, so the frontend was silently iterating `undefined` on every real (non-fallback)
response. Neither was caught by any existing test because no test exercised the real endpoint's
shape before this session added one.

**A test-harness trap worth recording before anyone writes another `httpResource` spec in this
codebase**: `ApplicationRef.whenStable()` does not resolve while an `HttpTestingController` request
is in flight, and a request only leaves flight when the test calls `.flush()` on it — so
`TestBed.tick(); await appRef.whenStable(); /* then */ httpTesting.expectOne(...).flush(...)` is a
**deadlock**, not just a race, whenever the tick causes a new request to fire. The fix is the order
already used by `auth-store.spec.ts` and now copied into `access-badge.service.spec.ts` /
`sidenav.spec.ts`: flush synchronously right after the tick, *then* await stability. Cost real time
to diagnose — the symptom was two 5-second test timeouts that, because Angular's Vitest runner
defaults `--isolate` to `false` ("to align with the Karma/Jasmine experience"), cascaded into
`TestBed.configureTestingModule` failures in unrelated spec files that happened to share a worker
with the hung ones. **If a spec file's failure output points at an unrelated file's `TestBed`
setup, suspect a deadlocked async test upstream in file order before suspecting the unrelated
file.** Also simplified `AccessBadgeService`'s own two tests to stub `AuthStore` with a plain
`signal()`-backed fake rather than driving it through a second live `httpResource` — chaining two
real resources' reactivity added complexity the deadlock fix didn't actually need.

None of the four Access screens exist yet and `app.routes.ts` is untouched — an Access sidenav
link today lands on the catch-all `NotFound` page, which is the expected state at this point in
the build order (steps 8–11 build the screens, step 12 wires the routes).

Verified: lint clean, **282/282** tests (up from 273 baseline), build green — run twice to confirm
the deadlock fix actually eliminated the flakiness rather than masking it.

### Step 8 (Categories screen) — done, commit `b9fb0aa`

`features/access/categories/` — `AccessCategories` (the ag-grid list, columns Name/Key/
Description/Any group/Objects/Groups/Actions) and `CategoryDialog` (create + rename, one dialog,
modelled on `ModuleSettingsDialog`'s Signal Forms pattern). `key` is editable only in create mode
and is never sent on the rename request — a read-only `.sec-field-readout` in edit mode. Delete
pre-empts the 409 using the row's own `objectCount`/`groupCount` (already in hand from the list
query) to build the `ConfirmDialog` message, but still attempts the delete on confirm either way;
a real 409 is the TOCTOU backstop and surfaces as an inline error under the table, never lost.
New `trash.svg` icon registered in `sec-icons.ts` — there was no delete/trash glyph before this.

Extended `access.model.ts` (category DTOs) and `access-api.service.ts` (categories list +
create/rename/delete) — this is the one shared HTTP client file for all four screens, per the
plan; it grows with each one. **`AccessApiService.categories` now guards its request on
`AuthStore.hasRole(Role.ACCESS_MANAGER)`**, matching `AccessBadgeService`'s own pattern — every
`/access/*` route needs the role on the backend, so without this guard a screen's own role
self-check (rendering `RefusalPanel` instead of content) would still leave the resource firing a
wasted `403` underneath it. Found by a real test failure, not by inspection — worth checking
this same guard exists when Grants/Unassigned/Defaults add their own `httpResource`s to this file.

**Two more deadlock-trap variants found while writing these specs** (same family as step 7's,
now three known shapes — worth reading before writing the next screen's spec):
1. `httpResource.reload()` *schedules* a refetch, it does not issue one — the request is not
   pending yet when `reload()` returns, so there is nothing to flush and nothing for
   `whenStable()` to wait on either. `requirement-review.spec.ts`'s settings-save test already
   names this and has the fix: `fixture.detectChanges(); await new Promise(r => setTimeout(r, 0));`
   *then* `httpTesting.expectOne(...)`. Neither `settle()` nor `whenStable()` alone is enough.
2. A component that injects a service *only* for its write methods (never reads the service's own
   list resource) still constructs that resource as a side effect of injection. Fixed here by
   giving the dialog's own spec a fake `AuthStore` whose `hasRole()` answers `false`, which (via
   fix above) keeps the resource from ever requesting anything — cleaner than flushing a stray
   request nobody in the test cares about.

Verified: lint clean, **295/295** tests (up from 282), build green. Not wired into
`app.routes.ts` yet (step 12) — the component compiles and is fully tested but is not reachable
from the running app.

### Step 9 (Grants screen) — done, commit `a3c3d55`

`features/access/grants/` — `AccessGrants`, the matrix (rows = groups, columns = categories,
built client-side from `AccessApiService`'s two resources, no server-built shape per spec §9),
three cell renderers: `GrantCell` (one checkbox per group×category, identified by
`params.column.getColId()` which the parent sets to the category's own `ref`), `SeesAllCell`
(its own visually distinct column, Tier-2 accent colour, immediate write behind a
`ConfirmDialog` — never batched into the grant buffer), `GrantsRowSaveCell` (pinned-right,
one `PUT` per row). Grant edits buffer in a `Map<groupRef, Set<categoryRef>>`, the same
per-`ref`-not-position shape `Modules`' `levelEdits` uses, cleared per row on that row's own
successful save via `gridApi.refreshCells({ rowNodes: [node] })` — **never a batch refresh**,
which is what `access-grants.spec.ts`'s regression test actually asserts (`saves exactly one PUT
for the row that was edited, and clears only that row`).

`SeesAllCell`'s checkbox always shows the *stored* `seesAll`, never an optimistic click —
`onChange` asks the parent to confirm-then-write and snaps the local `checked` signal straight
back to the stored value, so a cancelled confirmation needs no separate revert path (unlike the
review table's native `<select>`, which does — `requirement-review.ts`'s `moduleSelect` — because
that element bypasses Angular's own binding and a `mat-checkbox` does not).

Extended `access.model.ts`/`access-api.service.ts` with `GroupWithGrants`/`saveGrants`/
`setSeesAll`, both role-guarded the same way `categories` already is. **One test fix this
surfaced immediately**: `access-categories.spec.ts` now also has to flush a stray
`/api/v1/access/groups` request, since `AccessApiService` constructs that resource on injection
too — confirms the step-8 handover note's prediction that every future screen adding a resource
to the shared service needs the same check in every *other* screen's spec that injects the
service. **Expect this again for Unassigned and Defaults.**

Verified: lint clean, **302/302** tests (up from 295), build green, stable across two full runs.
Not wired into `app.routes.ts` yet.

### Step 10 (Unassigned screen) — done, commit `ecbabca`

`features/access/unassigned/` — `AccessUnassigned` (the one screen whose backend read *and*
write are both deliberately exempt from `AccessCypher.visible()`, §16.2a) and
`AssignCategoriesDialog` (a plain-signal checkbox list — no Signal Forms, since there is no form
here, just a selection). Multi-row selection via ag-grid's modern `rowSelection: { mode:
'multiRow', checkboxes: true, headerCheckbox: true }` config object (ag-grid 36's shape — the
old `rowSelection: 'multiple'` string is deprecated). On confirm: one `PUT` per selected
container (R7's own transaction unit, never one request for the whole batch), then — the
decision confirmed with the user during planning, and it must not be dropped if this screen is
ever refactored — `POST /access/reconcile?scope=source&source=<id>` once per distinct source
among the containers just touched, so accepting the queue is one gesture. `AccessBadgeService`
gained a `refresh()` method so the sidenav's own badge count drops immediately after a
successful assign rather than waiting for the next page load.

**Two new testing traps, on top of the two from step 8:**
1. **Selecting an ag-grid row through its checkbox DOM doesn't reliably fire the Angular
   `selectionChanged` output in tests.** Drive selection through the grid API directly instead —
   `(fixture.debugElement.query(By.directive(AgGridAngular)).componentInstance as
   AgGridAngular).api.getRowNode(ref)?.setSelected(true)` — which is also what ag-grid itself
   recommends, and it isolates "does this view's own wiring work" from "does ag-grid's checkbox
   implementation work" (the second is not this codebase's to re-test).
2. **Even the API-driven `setSelected(true)` needs a macrotask tick before the event reaches the
   component** — `getSelectedRows()` reflects the new state synchronously, but the
   `selectionChanged` event Angular's output binding depends on does not; a bare `await
   settle()` right after `setSelected` is not enough, the same class of gap `flushGridFrames()`
   exists for on the render side.

Also: a `Promise.all(...)` chain (the per-container `PUT`s, then the per-source reconciles) needs
its own `await settle()` between each stage in a test — flushing the first stage's HTTP requests
does not itself advance the `await` past it; that needs a further microtask tick.

Verified: lint clean, **313/313** tests (up from 302), build green, stable across two runs.

### Step 11 (Defaults screen) — done, commit `494dba7`

`features/access/defaults/` — the last of the four screens, and the one place this session
deliberately departed from the plan's literal wording. The plan says "one Signal Forms model";
what got built instead is `Modules`' own system-level batch-save shape (a buffer keyed by row id,
one `ModuleLevelCell`-style `<select>` renderer, one whole-view Save button, one `PUT` of the
*entire* set on save) — because this screen is structurally identical to Modules' own (a short,
fixed row set, one editable select per row), and there is no established pattern anywhere in
this codebase for binding Signal Forms across an ag-grid cell-renderer boundary. Forcing one in
for a three-row screen would have been a novel integration invented and proven nowhere else,
where a directly-analogous, already-proven pattern already existed one file over. Worth knowing
this was a deliberate choice, not an oversight, if anyone revisits this screen.

`DefaultCategoryCell` mirrors `ModuleLevelCell` almost line for line, including the dashed-ring
dirty-mark convention. Every known `(sourceId, containerLabel)` pair is always a row — "empty is
the default answer" (spec §10.2), never an absent one.

No new testing traps this time — the four found across steps 8–10 covered everything, and this
screen's spec passed clean on the first real run once they were all applied up front.

Verified: lint clean, **319/319** tests (up from 313), build green, stable across two runs.

### Step 12 (routing) — done, commit `e393b8f`

Four lazy routes under `/access/*` in `app.routes.ts`, `/access` redirecting to
`/access/categories` — the exact shape `jira/issues`+`jira/kids` and the `settings` subtree's own
redirect already use, no new pattern. No `canActivate` guard, on purpose: each screen already
self-checks the role and renders `RefusalPanel` in place (frontend/CLAUDE.md §8's "never a
redirect"), and the sidenav already hides the links from anyone without the role. **This is the
first commit where the four screens are actually reachable** — confirmed by the production build
growing four new lazy chunks that did not exist in step 11's build (they compiled correctly the
whole time, but as unreferenced dead code excluded from bundling until something routed to them).

Verified: lint clean, **319/319** tests, build green.

| Step | Change | Commit |
|---|---|---|
| 4 (follow-up) | 7 docker-tagged `AccessAdminServiceTest` cases for `listDefaults`/`saveDefaults`/`summary` — the zero-graph `summary` case is what actually exercises `SUMMARY_COUNTS`'s `COUNT {}` rewrite, previously committed unverified | `b9e35e2` |
| 5 | `/auth/me` gains `seesAll`/`categoryCount`, resolved via `AccessResolver.resolve(principal.groups)` the same way every filtered read path already does. Documented on the DTO field that `categoryCount` reads `0` for a `seesAll` caller by design (`AccessSet.SEES_ALL` carries no `categoryIds`) | `a650a11` |
| 6 | `GET /api/v1/config/navigation` — new `NavigationSettings` loader (absent `navigation.groups` means empty, same contract as an unconfigured Windchill host), served with the same `Cache-Control: max-age=3600` `/system-levels` uses. `application.yaml` gains the `access` group (Categories/Grants/Not assigned/Defaults), matching `frontend/CLAUDE.md` §9's sidenav diagram exactly — no `role` field, since nav-item visibility stays a frontend decision | `f74522d` |

### A real gap found and fixed while touching step 6

`RoleGuardTest`'s `everyWriteRouteIsAccountedFor` completeness check had **never been extended**
for any of the `/access/*` write routes phase 6 steps 1–4 already shipped (categories, groups/
grants, containers/items, defaults) — nine routes total, silently unchecked since session 32. Added
all nine to both the `administrative` table and `WRITE_ROUTES`, and fixed a missing `PATCH` case in
the test's own `HttpClient.call` dispatcher that the new `PATCH` routes exposed (it only had `GET`/
`POST`/`PUT`/`DELETE`). Also added `GET /api/v1/config/navigation` to both guard tests' sample lists.

### One environment oddity, resolved and not a real bug

A phantom `com.sec.ProbeTest` — no such source file exists anywhere in the repo — was reported by
surefire mid-session with a `NoSuchMethodError` against `configureApp$default`'s old signature. A
`mvn clean` made it vanish permanently; almost certainly a stale compiled class from something
outside this session (an IDE-generated scratch test, most likely) surviving in `target/test-classes`
across incremental builds. Not investigated further since it does not reproduce after clean and no
source file ever existed to explain it — worth a `clean` first if it reappears, not a code fix.

### Verified

- `mvn -pl backend -am test` — **367/367** (post-clean baseline; the pre-clean run that included the
  phantom `ProbeTest` is not the real count).
- `mvn -Pdocker -pl backend -am test` — **212/212**, up from 205 at session 32's close (+7 step-4
  follow-up cases).
- Not run this session: frontend lint/test/build — no frontend file touched.
- Every step committed individually per this branch's own convention; nothing left uncommitted.

### Resume here

**Every code step (backend 1–6, frontend 7–12) is done.** Two things remain, neither of them
"write more code" — read the plan file's own Testing section before starting either, it has the
full detail this is only pointing at:

1. **Step 13 — the manual on-screen acceptance pass.** Needs real infrastructure this session
   never touched: Neo4j + Keycloak up (`deploy/docker-compose.dev.yml` has the dev realm with
   `sec-dev-user`/`sec-dev-admin`/`sec-dev-nogroup`), the backend running against them, and
   `ng serve` for the frontend (or the packaged jar). Sign in as `sec-dev-admin`, take a freshly
   imported module from invisible to visible using **only** the Access UI — create a category,
   grant it to a group, assign it from the Unassigned queue (confirms the auto-reconcile fires),
   then sign in as `sec-dev-user` in that group and confirm the module is now visible: the `+n`
   badge, the statistics tiles, and the module list should all agree, live, with no backend
   restart. This is the literal acceptance line phase 6 was scoped against
   (`docs/features/access-control.md` §15's own wording) and nothing in this session's test runs
   substitutes for actually watching it happen.
2. **Two backend test items the plan's own Testing section names and this session did not
   build**: extend `VisibilityMatrixTest.kt` to prove `AccessAdminService`'s unconditional
   `invalidate()` calls actually close the phase-2 staleness gap *live* — a category created and
   granted becomes visible only after reconcile, and a `seesAll` flip is observable on the very
   next `/auth/me` call with no restart; and write the dedicated `AccessAcceptanceTest.kt` (or
   similarly named) that seeds a module via the real import fixtures — not hand Cypher, that is
   the point — and drives the full create-category → grant → assign → reconcile sequence
   programmatically, asserting a `sec-dev-user`-shaped caller's visibility flips from zero rows to
   every row.

**If resuming this specifically to run the acceptance pass**: it needs Docker (Neo4j) and the
Keycloak realm both up, which is exactly the kind of infrastructure bring-up this session
deliberately did not attempt — confirm with whoever is driving before spinning up local
containers or asking for real sign-in credentials, since standing infrastructure and credential
handling are outside what a coding session should assume it's cleared to do unprompted.

## State as of 2026-08-17 (session 32) — one of phase 6's open questions closed; DOORS-upload sketched, not started

Branch **`feature/access-control`**, no code changed this session — docs only, both already
committed by session 31 or about to be by this one. Picked up session 31's two named loose ends
(§16 Q2, and the machine-auth gap on `POST /access/reconcile`) and closed one, deliberately parked
the other with its design captured rather than lost.

**§16 Q2 answered** — the unassigned queue (phase 6 screen 3) is exempt from `AccessCypher.visible()`,
narrowly: container name/source/count only, never the contained items, `sec-access-manager`-gated
already. Written up as `access-control.md` §16.2a, mirroring how §16.1a closed Q1. Nothing to
implement yet — the endpoint itself is phase 6 work — this just means phase 6 doesn't re-litigate it.

**Machine-auth gap — explored, not fixed.** Started down "give `POST /access/reconcile` a periodic
background tick instead of a credential" (mirrors ADR 0018's `ImportScheduler` for JIRA, zero new
auth surface). The user redirected mid-discussion to a bigger, better idea: retire the headless
caller entirely by moving DOORS import behind an authenticated upload — a "DOORS Importer" settings
screen like Windchill's and JIRA's, so reconcile always runs with a real session behind it. Three
rounds of clarification nailed down the actual product rule, now recorded in `access-control.md`
§15.3's new "direction favoured" paragraph:

- **First-time import of a module**: always allowed, no check possible or needed. Lands invisible
  per the ordinary default (§8.3) — nothing new here.
- **Re-import of an existing module**: rejected with a *stated reason* (the user was explicit:
  "reject and notify the reason of it," not a bare 404) unless **both** — the module already
  carries a direct access category, **and** the uploading user can see it through that category
  (`AccessCypher.visible()`) or `seesAll`. `DoorsProjection.moduleExists(moduleId, access)` already
  computes exactly the second half of that and needs no changes to reuse.
- **Async, non-blocking**, same mechanism as Windchill/JIRA: `ImportRunService` + the existing SSE
  `ImportRunStore`. Windchill's route (`WindchillRoutes.kt`) is the shape to mirror almost exactly —
  raw JSON text body (not multipart), synchronous parse-or-400, then `importRunService.start(...)`
  → `202` with a run id.
- **One open design note surfaced but not resolved**: a stated rejection reason for an
  exists-but-invisible module is a deliberate, narrow departure from R8's "an unauthorized object is
  always a 404, never a 403 that confirms existence" — justified here because the uploader already
  holds the file (proof the module exists), unlike an arbitrary graph-browsing caller. Worth a second
  look before shipping, not just waved through.
- **What was explicitly left undecided, on the user's own instruction**: how ~2,200 lines of DOORS
  parsing/graph-write logic (`importers/src/sec_import/doors/{parser,derivations,importer}.py`,
  including the 738-line table-reconstruction spec and ADR-0012's reconciliation) gets into the
  backend's request path — a full Kotlin port on the `WindchillGraphWriter` model, or the backend
  shelling out to the existing, proven Python CLI. **"Treat as another feature"** — the user's own
  words. Don't start building the upload screen without settling this first; it decides almost the
  entire shape of the work.

### Resume here

1. If picking the DOORS-upload feature back up: start with the import-engine question above — it's
   the fork that decides everything else. `access-control.md` §15.3's new paragraph and this entry
   have the rest of the design already reasoned through; a fresh planning pass shouldn't need to
   re-derive it, only decide that one thing and write the ADR it deserves.
2. Otherwise, phase 6 (the Access views) is still next per the build order — see session 31's entry
   below, unchanged.

## State as of 2026-08-17 (session 31) — phase 4's on-screen check, closed for real

Branch **`feature/access-control`**, still on top of session 30's phase 5 commits. No code changed
in the access-control model itself — this session did the one thing `access-control.md` §15.2 named
as not done: **read the `+n` badge, the unresolved-modules banner and the statistics on screen, as
two different users, against real DOORS data.** Full writeup is in §15.2 itself now; this entry is
the pointer.

Three sanitised DOORS exports (977 + 545 + 924 objects) were imported into a bare-metal local
Neo4j — one (`Something_0009f361_current.json`) had never actually landed as a `:DOORSModule`
despite the user believing all three were imported; it only existed as 394 `:__UNDEFINED`
placeholders referenced by the other two. Imported it, confirmed the CLI importer is idempotent
(0 nodes/relationships on a second run), then re-seeded `deploy/dev-access-seed.cypher` cleanly
(a first pass at 2-of-3 modules and a second at 3-of-3 had left two modules double-tagged with both
categories, which would have made the scoped user see everything and defeated the check).

**Result: no leak found.** `sec-dev-user`'s statistics (2 modules, 1469 items) and `sec-dev-admin`'s
(3 modules, 2446 items) match the reconciler's own propagation count exactly. The `+n` badge and the
unresolved-modules banner behave correctly, including at an edge case the original phase-4 badge
test doesn't cover — an item whose *only* neighbour is access-filtered renders a plain empty state,
not a count and not a hint that something exists.

**One unrelated bug found and fixed**: `sec-realm.json`'s `post.logout.redirect.uris` was `"+"`,
which only matches `redirectUris` (the backend's OIDC callback) — so "Sign out" errored with
"Invalid redirect uri" for anyone running `ng serve` on `:4200`, exactly the setup
`SEC_AUTH_FRONTEND_URL` exists for. Fixed to name both landing pages explicitly; verified end to end
(sign out now lands cleanly back on the login form, no stale SSO re-auth).

### Resume here

Phase 4's acceptance question is now fully answered; the branch is ready to merge on that count.
Next is **phase 6, the Access views** (`access-control.md` §15, done when *"an access manager can
take a freshly imported module from invisible to visible without touching Cypher"*) — it retires
`dev-access-seed.cypher` and gives `AccessResolver.invalidate()` its first caller. Still open,
unchanged from session 30: machine-auth for `POST /access/reconcile`, and §16 question 2 (is the
unassigned queue exempt from filtering).

## State as of 2026-08-16 (session 30, later) — phase 5 is complete too

Branch **`feature/access-control`**, four further commits on top of phase 4. Phase 5 is done in two
parts (`4f52bc7` capability, `02fa03f` visibility), plus the dev seed (`2b5c45d`) that makes phase
4's on-screen check cheap.

**`AccessGuardTest` now has no deferred exemptions at all.** Every read is filtered, every Tier-2
write is filtered on its anchor, and every remaining entry in that map is permanently exempt with its
own reason.

### Phase 5, and the four things that had to be learned by running it

| Trap | What it looked like |
|---|---|
| **`createChild` reuses a child whose selector compares equal** | A shared `object` selector made *every* `requireRole` mount onto one node. Two guards = one node carrying both: `sec-admin` demanded of `/access/reconcile`, and a holder of the right role refused its own route. A fresh selector instance per call fixes it |
| **A raw `intercept` runs for everything resolved through the route** | Hence a route-scoped plugin. `authenticate` is not built on a bare interceptor either, and this is why |
| **`AuthenticationChecked` fires even when authentication failed** | Null principal + a 401 already challenged. Throwing there turned every 401 into a 500. It returns instead — the CSRF check's trap from the other side |
| **Filtered write statements need their parameters bound** | `MetaWriter` builds `Query` objects and runs them as one transaction, so `Write.kt` needed the access-binding overload `Read.kt` already had. Without it every Tier-2 write fails on a missing parameter — safe, not shippable |

### The split inside Tier 2, so it is not "fixed" later

**`POST /modules/{ref}/comments` stays `sec-user`.** It and module settings are both `:__Meta`, and
the difference is real: a comment is one reviewer's note on one object; a mandatory-attribute policy
governs every object in a module and changes what the Issues column says about all of them. Only one
is an opinion. `RoleGuardTest` asserts both directions, and the second list matters as much as the
first — a guard drawn too wide fails closed, so it passes every "is it a 403" check while quietly
breaking the application for ordinary users.

### Two layers on the write path, on purpose

`MetaWriter` already refused a forged `__id` before phase 5 started, through the filtered
`moduleExists` and `MODULE_OBJECT_IDS`. The nine write statements are filtered *as well*, which is
redundant today and deliberate: a guarantee that survives its caller being reordered is worth more
than one that depends on the order two checks happen to run in.

### Verified

- `mvn -pl backend -am test` — **368/368**; `-Pdocker test` — **183/183**.
- Frontend untouched since phase 4's check: lint clean, 273/273, build green.
- Deliberate breaks, all four recorded: `AccessGuardTest` (marker removed), `VisibilityMatrixTest`
  (neighbour filter removed → badge inflated; `UPSERT_COMMENTS` filter removed → forged note stored),
  `RoleGuardTest` (guard removed → failed by name).

### Resume here

1. **The on-screen check** — still the only thing phase 4 owes, now a short job:
   `deploy/dev-access-seed.cypher` plus `access-control.md` §15.2. Sign in as `sec-dev-user` and
   `sec-dev-admin`; look hardest at the `+n` badge, the unresolved-modules banner and the statistics.
   Do it before merging — the branch is otherwise ready.
2. **Phase 6, the Access views** — and it is what retires the dev seed. Note `AccessResolver.invalidate()`
   still has no caller, so until `AccessAdminService` exists a grant edited by hand needs a backend
   restart (`access-control.md` §5 has the table).
3. Still open and unchanged: machine-auth for `POST /access/reconcile` — now sharper, since that
   route is `sec-access-manager`-gated and `sec-import-doors.ps1` has neither cookie nor role, so it
   degrades to §8.3's warning and the startup pass is what actually closes it. And §16 question 2
   (is the unassigned queue exempt from filtering?), which phase 6 has to answer to build screen 3.

## State as of 2026-08-16 (session 30) — access control phase 4 is complete

Branch **`feature/access-control`**, on top of session 29's step 1 (`c4b414e`). Steps 2-5 of the plan
in **`/home/juanmrc/.claude/plans/ancient-juggling-magpie.md`** are done, in two commits
(`3bce197` threading, `d5561ef` filtering) plus this session's test and doc work.

**`AccessGuardTest` has no `phase 4 read path` entries left.** Every one of the 37 statements is
filtered. The branch is now internally coherent — a half-filtered API was the thing phase 4 must not
ship, and it no longer is one.

### The gap session 29 named, closed first

`mvn -Pdocker test` was run before anything else. The unrun fail-closed test passes: **Neo4j does
error on the missing `$seesAll`/`$acl` parameter**, so a mis-wired call site fails rather than
quietly returning everything, and step 2's threading rests on solid ground. No compile-time guard
was needed.

### What steps 2-5 delivered

| Step | Change |
|---|---|
| 2 | `AccessSet` threaded through ~25 projection methods, every route handler, and `MetaWriter`'s three public methods; every one of those call sites moved to the binding `executeRead` overload. **No Cypher changed** and all 362 tests stayed green, so the diff reviewed as pure plumbing |
| 3 | All 37 statements filtered, in the plan's lockstep order: Review → cards/graph/breakdown → statistics → JIRA → Windchill, plus `ModuleCypher`, `ItemCypher` and `TableCypher` |
| 4 | Audited rather than built: every problem-detail message interpolates a count or the caller's own input, so none of them names an object the caller cannot see. Nothing to change |
| 5 | **`VisibilityMatrixTest`** — 21 docker-tagged cases over one fixture as four callers |

### Three things worth knowing before touching this again

1. **`AccessCypher.visible()`'s subquery variable is `aclCat`, not `c`.** An `EXISTS { }` imports its
   outer scope, so declaring `c` inside it is a hard error wherever the statement already binds `c`
   for the system-level classification — `RequirementCardCypher`, `ModuleCypher`, `StatisticsCypher`.
   Renaming it back breaks three files at once.
2. **`GraphNamesTest.stripComments` is now nesting-aware**, because Kotlin nests block comments and
   the old non-greedy regex closed a KDoc at the first `*/` it found inside quoted prose — which the
   ACL marker is, in every comment that explains it. The rest of that KDoc was then scanned as code.
   The cheap way to silence it was to stop naming graph names in comments, which is backwards.
   (Writing the replacement's own doc comment reproduced the bug once: spelling the delimiters out
   closed the KDoc early and would not compile. The doc describes them in words for that reason.)
3. **The badge test has to seed one hop further out than seems natural.** Seeding on the object that
   owns the hidden link does *not* expose the leak: an unfiltered walk admits the hidden neighbour
   and then drops it for having no card, so it lands inside the admitted set and is never counted as
   cut. Seeding one hop back puts it beyond the set, where the badge counts it. Verified by removing
   the predicate from the two neighbour statements and watching a badge read 3 where it reads 2.

### Both guards verified by deliberate break, as the spec asks

- `AccessGuardTest`: removing the marker from `WindchillCypher.LIST_DOCUMENTS` failed it by name
  (`WindchillCypher[3]`); green again on restore.
- `VisibilityMatrixTest`: removing the predicate from `DependencyGraphCypher`'s two neighbour
  statements failed the badge case; green again on restore.

### Two loose threads from session 29, both settled

- **The `seesAll` naming.** `AccessControlFeatureTest`'s test claimed "seesAll included" and never
  asserted it — and the claim was false. A `seesAll` group *does* see an uncategorised object, which
  is what *Sees everything* means; R8's "invisible to everyone, administrators included" is about
  capabilities, which grant no visibility at all. The test is renamed and now pins both readings, and
  `access-control.md` §15.1 states the distinction so the matrix cannot encode the wrong one.
- **`AccessCypher[8]`**, the placeholder containment's redundant seed pass, is unchanged — still a
  no-op, still exempted and explained. Not worth collapsing until a third DOORS containment exists.

### One leak found after the plan's list was exhausted

A `ReferenceDto` carried `moduleRef` — base64url of the module's `__id`, which for DOORS is its
export URL and is reversible without server state (R5). No view renders it without a name, and the
name was already filtered, so nothing was *displayed*; it was on the wire regardless, and R8 draws no
line between shown and sent. Both reference paths now drop the handle wherever the name did not
resolve (`ReviewProjection.named`), and the two cases that produces — a module that is invisible and
one that was never imported — are deliberately not told apart, because telling them apart is the
disclosure.

Reachable only through §8.1's escape hatch, an object granted a category its module does not carry,
which is why `VisibilityMatrixTest`'s fixture now builds that shape explicitly (`item-b-exception`,
an object of module B granted category A, kept clear of a1/a2 so the badge cases stay readable). It
is also the first test of the escape hatch itself. Verified by reverting the fix: the assertion
failed naming the leaked handle itself — `bW9kdWxlLWI`, which decodes to `module-b`.

### The statistics page is per-caller, and that is now written down

Raised in review: the statistics are limited to the caller's visibility scope. Confirmed, and it is
permanent rather than interim — R8 leaves no alternative and reopening it needs an ADR. Two users can
pick the same scope and read different totals.

`requirements-statistics.md` §12.5 now records that, including the part that is easy to get wrong
later: **the page carries no caveat about it, deliberately.** A line saying "some data is hidden from
you" is itself the disclosure, and §7 already decides this shape — the Breakdown tree reaching an
invisible child "simply ends. No 'loops back to ‹id›', no ellipsis, no count."

One string was actually falsified by the filtering and is fixed: the Items tile's hint read
**"Everything imported"** and now reads **"Headings and tables included"**. It was always contrasting
item *types* with the Requirements tile beside it rather than claiming anything about access, but
"everything" is an absolute the filter no longer delivers. `All modules` in the scope selector is
left alone — it selects over the modules that exist for this caller, and there is no other list it
could offer.

### Project-wide numbers: answered, no ADR needed

Confirmed in review: the users who produce programme-level figures will be given access to
everything, so the per-caller statistics are fine as they stand. That needs no new machinery —
`seesAll` on a group *is* that mechanism, and §16 question 3 already chose it over a per-source
variant. The "second read path with its own capability" floated earlier is **not** wanted; do not
build it.

**`seesAll` and "granted every category" are not the same thing, and for this use case the
difference is decisive.** `seesAll` short-circuits the predicate, so it also covers objects carrying
*no* category — which is every freshly imported object until an access manager works the Unassigned
queue. A group granted every category instead would silently under-count after each import, which is
exactly the failure that gets reported as "the statistics are wrong" with no way to see why. Use
`seesAll` for these users.

**One operational trap while the Access views are still phase 6.** `AccessResolver.invalidate()` has
no caller yet, so its cache — keyed on the *sorted group set*, not on the user or the session — is
frozen for the process lifetime. Adding someone to a group key this process has never resolved is a
cache miss and works immediately; flipping `seesAll` on a group that has already been resolved once
does **not** take effect until the backend restarts, and signing out and back in does not help. There
is no TTL to wait out, deliberately. Written up in `access-control.md` §5. It disappears when phase 6
lands, since every write there calls `invalidate()`.

### One correction found while filtering

`WindchillCypher.COUNT_DOCUMENTS` was exempted as "Windchill Documents view, count". It is not: it is
what `WindchillGraphWriter` reads either side of its own sweep for the mass-deletion warning
(ADR 0015 §7), and it has no user-facing caller at all. Reclassified as a permanent importer-read
exemption with the real reason.

### Verified

- `mvn -pl backend -am test` — **362/362**, unchanged from the baseline all the way through, which is
  what made step 2 reviewable as plumbing.
- `mvn -pl backend -am -Pdocker test` — **181/181** (159 + the 22 new matrix cases), against
  `neo4j:2026.06.0-community`.
- From `frontend/`: lint clean, **273/273**, build green. No frontend file changed this session.

### Resume here

**Read it on screen as two different users before merging.** That is the half of phase 4's own
acceptance question no test can answer (`access-control.md` §15.1): the matrix proves the projections
return a filtered answer, not that the frontend renders it without adding a claim of its own. Look
hardest at the `+n` badge, the unresolved-modules banner and the statistics page. `deploy/docker-compose.dev.yml`
has the Keycloak realm with `sec-dev-user` / `sec-dev-admin` / `sec-dev-nogroup`; the categories and
grants still have to be written by hand in Cypher, because the Access views are phase 6.

Then **phase 5** (write guards): `requireRole` on `/settings/*`, on the imports, and on the meta
writes; per-anchor visibility on Tier-2 writes. Note that phase 4 already pulled the *container* half
of that forward — `MetaWriter`'s `moduleExists` check is filtered, so a module that 404s on read
cannot accept a write. What phase 5 still owes is the per-anchor guard and `requireRole` itself,
which does not exist yet (`security/Roles.kt` declares the strings and says why the plugin waited).

Still open and unchanged: machine-auth for `POST /access/reconcile` (`sec-import-doors.ps1` has no
session cookie), and §16 question 2 — whether the unassigned queue is exempt from filtering so an
access manager can see what they are assigning. That one is phase 6's, not phase 5's.

## State as of 2026-08-16 (session 29) — access control phase 4, step 1 of 5 only

Branch **`feature/access-control`**. **Phase 4 is started and deliberately incomplete.** The full
approved design is in **`/home/juanmrc/.claude/plans/ancient-juggling-magpie.md`** — read that first,
it is the working document for this phase and holds the analysis this section only summarises.

**Phase 4 must not be split across a release** (`access-control.md` §15: *"a half-filtered API is
worse than an unfiltered one: it looks guarded"*). What is committed so far changes **no** read path
— it is foundations only, so the branch is in a safe, coherent state, but it must not be merged until
steps 2–5 are done.

### What step 1 delivered (committed)

| Change | Where |
|---|---|
| `AccessSet.SEES_ALL` beside the existing `NONE`; `resolveFromGraph` now returns it | `security/AccessResolver.kt` |
| **`doors.placeholders` containment** (§16.1a) — `:__UNDEFINED` nodes inherit their module's categories via the `__moduleUrl` the importer already stores on them | `security/AccessContainment.kt` |
| **`Containment.name`** — `sourceId` stopped being unique the moment DOORS declared two containments, which silently broke `single { it.sourceId == "doors" }` and made the reconciler log two indistinguishable `Reconciled 'doors'` lines | `AccessContainment.kt`, `AccessReconciler.kt`, `AccessReconcilerTest.kt` |
| `AccessCypher[1]`–`[10]` exemptions renumbered; JIRA field catalogue (`LIST_FIELDS`/`FIND_FIELDS`) reclassified from `phase 4 read path` to **permanently exempt** | `AccessGuardTest.kt` |
| Fail-closed test: a `/*ACL*/` statement issued without `$seesAll`/`$acl` must error, not return everything | `AccessControlFeatureTest.kt` |

### Verified, and the one gap

- `mvn -pl backend -am test` — **362/362**, 0 failures. Both guard tests green.
- **`mvn -Pdocker test` was NOT run this session.** The new fail-closed test is therefore **unrun**,
  and step 2's whole approach assumes the property it asserts. **Run it first thing.** If Neo4j turns
  out not to error on the missing parameter, the threading in step 2 needs a compile-time guard
  instead of relying on a runtime failure — that is a design change, not a tweak.

### Resume here — step 2 of the plan

**Mechanical threading, no Cypher changes.** `access: AccessSet` through ~25 projection methods, 20
route handlers, the 5 route functions that have no `AccessResolver` yet, and `MetaWriter`'s three
write methods (the `moduleExists` decision — filter it including its write-path callers). Everything
still returns everything; every existing test stays green; the diff reviews as pure plumbing. Expect
~60 test call sites to gain `AccessSet.SEES_ALL`.

Then step 3 — the **lockstep groups**, where the actual risk is. The plan file has the table. The one
to be most careful with: `RequirementCardCypher.NODES` is shared by the dependency graph and the
breakdown tree, and filtering it *without* the neighbour statements makes the `+n` badge **grow** by
exactly the number of hidden objects — a worse leak than not filtering at all.

### Two loose threads worth a look

- `AccessControlFeatureTest`'s test named *"an untagged object is invisible to every group, seesAll
  included"* only asserts for a normal group member. The predicate is `$seesAll OR EXISTS{…}`, so a
  `seesAll` group **does** see untagged objects. Either the name overclaims or R8/§4.4's wording
  needs revisiting — worth settling before the visibility matrix test encodes one reading.
- `AccessCypher[8]` (the placeholder containment's seed pass) is a no-op in practice: it seeds the
  same `:DOORSModule` container `[7]` already seeds. Exempted and explained, but if a third DOORS
  containment ever appears, the seed step is doing redundant work worth collapsing.

## State as of 2026-08-16 (session 28) — JIRA: RBAC is the gate, plus a periodic scheduler

Branch **`feature/access-control`**, on top of session 27's committed `CurrentUser.PLACEHOLDER`
wiring (`e969892`). Closes the JIRA scope gap session 26 explicitly deferred: the project allow-list
is gone, access categories are the sole visibility gate (R8), and a new coroutine `ImportScheduler`
re-runs JIRA periodically since removing the picker removed the only manual re-trigger. Full design
reasoning is **ADR 0018**.

| # | Change | Where |
|---|---|---|
| 1 | `JiraJql.build` drops `projectKeys`; query is now fixed (`created <= … ORDER BY key ASC`) | `JiraJql.kt` |
| 2 | `JiraSettingsStore`, `:__JiraSettings`, `NoProjectsConfigured`/`InvalidProjectKey` deleted outright | `JiraSettingsStore.kt` (removed), `JiraFailure.kt`, `JiraNames.kt` |
| 3 | Sweep collapses to one statement (`SWEEP_DECONFIGURED` removed, `SWEEP_DELETED` loses its project scope) | `JiraCypher.kt`, `JiraGraphWriter.kt`, `JiraImporter.kt` |
| 4 | `GET`/`PUT /jira/settings` removed; `GET /jira/projects` kept, repurposed as a read-only diagnostic | `JiraRoutes.kt`, `ApiPaths.kt`, `JiraDtos.kt` |
| 5 | New `ImportScheduler` — source-agnostic, mirrors `ImportRunService`'s coroutine shape, delay-first ticks, `AlreadyRunning` is a silent no-op | `importer/ImportScheduler.kt` |
| 6 | `jira.scheduleMinutes` config (default 60, `0` disables; not read via the shared `intOr` helper — see the field's own doc comment for why) | `config/JiraSettings.kt`, `application.yaml` |
| 7 | New `GET /import/{importerId}/schedule` (`ImportScheduleDto`), wired via a source-agnostic `Map<String, ImportScheduler>` threaded through `Routes.kt` | `ImportRoutes.kt`, `ImportDtos.kt`, `Application.kt`, `Routes.kt` |
| 8 | `GraphNamesTest`/`AccessGuardTest`'s `JiraCypher` statement lists and exemption indices renumbered (both are self-checking; both passed clean on the first try) | both test files |
| 9 | Frontend: project chip picker + JQL preview deleted; Connection section now lists what the token can see; Import section shows "Next scheduled import" | `jira-settings.*` |
| 10 | New ADR 0018; ADR 0014 decisions #2/#5/#18 and `JIRA_ISSUES_FEATURE_SPEC.md` §8/§10.1/§12/§13.5/§17 carry forward-pointers to it | `docs/adr/`, `docs/JIRA_ISSUES_FEATURE_SPEC.md` |

### Verified

- `mvn -pl backend -am test` — **362/362**, 0 failures (includes the new `ImportSchedulerTest`, the
  trimmed `JiraJqlTest`, and the two now-obsolete `JiraIssueImportTest` scenarios removed).
- `mvn -pl backend -am -Pdocker test` — **158/158**, 0 failures, including the real-Neo4j
  `JiraIssueImportTest` exercising the simplified `SWEEP_DELETED` against a real Community instance.
- `npm run lint && npm test && npm run build` from `frontend/` — lint clean, **273/273** tests pass,
  build succeeds.
- Committed as **`bcfa3e4`** on `feature/access-control`, on top of `e969892`. Not pushed.

### Resume here

Phase 4 (`docs/features/access-control.md` §15) is next, same as session 27 left it: every remaining
read path gets the `/*ACL*/` predicate — 37 statements are named in `AccessGuardTest`'s exemption
list with a `phase 4 read path` reason, and they are the checklist.

**§16 question 1 is now answered — see the new §16.1a**, which phase 4 implements: a node is visible
only through a container that resolves, and where none does it is invisible to everyone (fail-closed,
R8). Three consequences for the build:

- `:__DELETED` DOORS objects are **already correct** — they keep `:DOORSObject` + `__moduleUrl`, so
  the existing `AccessContainment.doors` pattern matches them. Needs a test, not code.
- `:__UNDEFINED` placeholders need a **containment change** to inherit their module's categories:
  they carry `:SEItem:__UNDEFINED` and *not* `:DOORSObject`, so today's pattern misses them.
  `__moduleUrl` is stored on them at import (`importer.py:181,209`) — a lookup, not URL parsing.
- A standing placeholder's module is usually **not** imported, so most of them stay invisible, and
  the incoming-arrow "refined by a module not yet imported" evidence goes with them. That is the
  accepted cost of fail-closed, written up in §16.1a so it is not re-litigated as a bug.

Still open, and worth settling before phase 6 rather than phase 4: §16 question 2 (is the unassigned
queue exempt from filtering, so an access manager can see what they are assigning?). Machine-auth for
`POST /access/reconcile` remains a separately deferred, still-open gap (unaffected by this session).

## State as of 2026-08-16 (session 27) — wiring `CurrentUser.PLACEHOLDER` into real routes

Branch **`feature/access-control`**, on top of session 26's committed phase 3 (`cd0ee65`). Closes
the first of the two "Resume here" items session 26 left open; phase 4 is still next.

Every route that writes Tier-2 data on behalf of a signed-in caller now passes the real principal
instead of the `"system"` default:

| Route | Writer call | Where |
|---|---|---|
| `POST /modules/system-levels`, `POST /modules/{ref}/settings` | `MetaWriter.saveSystemLevels` / `saveModuleSettings` | `ModuleRoutes.kt` |
| `POST /modules/{ref}/comments` | `MetaWriter.saveComments` | `ReviewRoutes.kt` |
| `PUT /jira/columns`, `PUT /jira/settings` | `JiraColumnStore.saveFieldIds` / `JiraSettingsStore.saveProjectKeys` | `JiraRoutes.kt` |

Each handler now does `call.principal<SecPrincipal>() ?: error(...)` — the same pattern
`ReviewRoutes`'s `/objects` route and `AuthRoutes`'s `/auth/me` already used — and passes
`principal.auditName`, a new extension in `Principal.kt`:

```kotlin
public val SecPrincipal.auditName: String get() = name.ifBlank { username }
```

Pulled out because `AuthRoutes`'s `/auth/me` already computed the identical fallback inline for
`displayName`; both call sites now share it, so `__createdBy` / `__updatedBy` and the identity a
user sees for themselves can never drift apart.

`CurrentUser.PLACEHOLDER` is not deleted and its default parameters on `MetaWriter`'s three
methods are untouched — it now correctly means two things, both intentional, and its doc comment
was rewritten to say so: `AccessReconciler`'s own writes (a machine decision, permanent, explained
in that class's own doc) and tests that call `MetaWriter` / `JiraColumnStore` / `JiraSettingsStore`
directly without a session, which is most of the existing test suite (`ModulesFeatureTest`,
`ReviewFeatureTest`, `JiraColumnsReadTest`, `JiraIssueImportTest`, `StatisticsFeatureTest`,
`BreakdownFeatureTest`) — none of those needed to change, since they never went through a route.

### Verified

- `mvn compile` / `mvn test-compile` — clean.
- `mvn clean verify` (non-docker) — **366 tests, 0 failures**, unchanged count from session 26
  (no new test methods; existing route-level auth tests, e.g. `AuthGuardTest`, already covered the
  401 path and needed no change).
- Not run this session: `-Pdocker test`, `npm run lint && npm test && npm run build` — no graph
  behaviour or frontend changed, only which string a write already made records as the actor.
- **Not yet committed** — sitting on `feature/access-control` on top of `cd0ee65`.

### Resume here

Phase 4 (`docs/features/access-control.md` §15): every remaining read path gets the `/*ACL*/`
predicate — item, tree, children, traces, breakdown, dependency graph, modules, objects, tables,
JIRA issues and link graph, Windchill documents, statistics, search, and every leak named in §7.
Must not be split across a release (spec's own words). The JIRA "RBAC is the gate" allow-list
removal and machine-auth for `/access/reconcile` remain deliberately deferred, not part of this.

## State as of 2026-08-16 (session 26) — access control, phase 3: containment and the reconciler

Branch **`feature/access-control`** — session 25's uncommitted work (phases 1-2, docker-verified)
was moved off `master` onto this branch at the start of this session and committed there (commit
`eb16b0a`); `master` was left untouched. This session's phase-3 work was committed as `cd0ee65`.

Phase 3 per `docs/features/access-control.md` §15: `AccessContainment`, `AccessReconciler`
(propagate/retract/seed), `POST /api/v1/access/reconcile`, the startup pass, and the in-process
import-pipeline hook. Built and docker-verified in this session; `CurrentUser.PLACEHOLDER` (the
task selected at the end of session 25) was **not** touched — the user redirected to phase 3
before it was started, and it is still open.

| # | Change | Where |
|---|---|---|
| 1 | **The model's last two names** — `:__AccessDefault`, `__assigns`, `__accessSeeded` | `domain/GraphNames.kt` (new `AccessRelProp`, `AccessOrigin`, `AccessDefaultProp`) |
| 2 | **`Containment`** — one entry per source (`doors`, `jira`, `windchill`), source-agnostic type, values built from each source's own name constants | `security/AccessContainment.kt` |
| 3 | **`AccessCypher.propagate/retract/seed`** — three `Containment`-parameterised query builders, each a returning `CALL … IN TRANSACTIONS` so the batched count is exact, not a unit-subquery row count | `graph/cypher/AccessCypher.kt` |
| 4 | **`executeAutocommit`** — the one narrow addition `CALL … IN TRANSACTIONS` needs, since it cannot run inside `executeWrite`'s explicit transaction | `graph/Write.kt` |
| 5 | **`AccessReconciler`** — `reconcile(Containment)` and `reconcileAll()`, returning counts | `security/AccessReconciler.kt` |
| 6 | **`POST /api/v1/access/reconcile?scope=all\|source&source=`** — synchronous, returns counts directly rather than a run id on the SSE stream (a reconcile pass is index-driven and batched, not the minutes-long kind of work `ImportRunService` exists for) | `api/routes/AccessRoutes.kt`, `ApiPaths.kt` |
| 7 | **The import-pipeline hook** — `ImportRunService` reconciles a job's own `sourceId` after `job.run()` succeeds, fully generically (filters `AccessContainment.all` by `importerId`, no source named in the framework); a reconcile failure fails the run, same as any other phase | `importer/ImportRunService.kt` |
| 8 | **The startup pass** — `AccessReconciler.reconcileAll()` in `module()`, best-effort (a failure logs and does not fail boot — under-visible is the safe failure direction) | `Application.kt` |
| 9 | **`sec-import-doors.ps1` calling it** — after a real (non-dry-run) `import` run, `POST …?scope=source&source=doors`; failure is a caught, printed warning, never touches `$LASTEXITCODE` | `scripts/win/sec-import-doors.ps1` |
| 10 | **`AccessReconcilerTest`** (docker-tagged) — the phase's acceptance test: propagate tags every object and a second pass creates nothing new; untagging retracts everything and a second pass retracts nothing new; an untagged module propagates nothing; seed is a no-op with no default, seeds once with one, and is never re-seeded after a human empties the container | `AccessReconcilerTest.kt` |
| 11 | `GraphNamesTest` / `AccessGuardTest` extended — both read `AccessContainment.all` directly and generate their statement list from it, so a fourth source's containment is checked and exempted automatically | both test files |

### The one real bug the new test caught

`AccessContainment.doors`'s `memberMatch` first read `{ __moduleUrl: c.__moduleUrl }` — wrong: a
`:DOORSModule` node does not carry its own `__moduleUrl`; `ModuleCypher.kt`'s own comment says
objects match against the module's **`__id`**. `AccessReconcilerTest` caught it immediately
(`expected 10 but was 0` — nothing propagated), which is exactly the kind of mistake a docker
test against real Cypher execution catches and a unit test of Kotlin string-building would not.
Fixed to `{ __moduleUrl: c.__id }`; all 5 reconciler tests and the existing 5
`AccessControlFeatureTest` cases pass after the fix.

### A naming collision worth knowing about before adding a fourth `AccessCypher` query parameter

The first draft named the audit-actor parameter `$createdBy`. `GraphNamesTest`'s inverse check
failed on it — not because it is wrong Cypher, but because **`JiraRel.CREATED_BY = "createdBy"`**
is a declared, non-namespaced relationship type, and the test cannot tell a query parameter from a
graph name written out by hand; both are just the word "createdBy" in the source. Renamed to
`$user`, matching the parameter name `MetaWriter`'s own statements already use for the same
concept. Worth remembering: **any bare (non-`__`) word chosen for a parameter name in
`graph/cypher/` should be checked against `JiraRel`/`JiraProp`/`DoorsModuleAttr`'s un-prefixed
values first** — the collision surface is bigger than it looks, because most of those values are
themselves ordinary English words.

### What is still open

- **Not committed.** This session's phase-3 work sits uncommitted on `feature/access-control`,
  on top of session 25's committed phase 1-2 (`eb16b0a`).
- **`POST /access/reconcile` has no machine-auth story.** It sits behind the same
  `requireSecSession {}` every route does, which is correct and not a regression — but
  `sec-import-doors.ps1`'s own call to it therefore has no session cookie to present and will 401
  until some future phase gives a script a credential. This is not a bug in what shipped: the
  call is written exactly per §8.3 ("a failure there is a warning, not an error"), so it already
  degrades correctly — the startup pass is what actually closes the gap today. Named here, and in
  `AccessRoutes.kt`'s own doc comment, so it is not mistaken for finished.
- **`CurrentUser.PLACEHOLDER` still writes `"system"`** on every `:__Meta` write — unchanged from
  session 25, and not this session's scope (the user redirected to phase 3 before it started).
  Every write `AccessReconciler` itself makes is correctly `"system"` — that one is not a gap; see
  the class doc.
- **Phase 4 (every other read path) has not started.** Only `/modules/{ref}/objects` is filtered,
  same as since phase 2.
- **The two design decisions phase 3 was scoped around are implemented, not just decided**: DOORS
  has no pre-import tree enumeration (a module is tagged only after import, via the existing
  Unassigned-queue behaviour once phase 6 builds it); JIRA's "RBAC is the gate" project-allow-list
  removal was **not** done this session — `JiraSettingsStore.projectKeys` and `JiraJql`'s
  `project IN (...)` clause are untouched, so JIRA's containment (`AccessContainment.jira`) is
  correct for what is imported today but the importer still gates by project key rather than
  importing everything unconditionally. Worth flagging clearly: this is a scope gap, not an
  oversight — the reconciler works correctly either way, since it only ever sees what JIRA's own
  importer already wrote.

### Verified

- `mvn compile` / `mvn test-compile` — clean.
- `mvn verify` (non-docker) — **366 tests, 0 failures**, unchanged from session 25 (no new
  non-docker test methods this session — `AccessReconcilerTest` is docker-tagged).
- `mvn -Pdocker test` — **526 tests, 0 failures** (521 from session 25 + 5 new
  `AccessReconcilerTest` cases), including the moduleUrl bug fix above.
- Not run this session: `npm run lint && npm test && npm run build` — no frontend changed.

### Resume here

Two independent threads, either order:

1. **Wire `CurrentUser.PLACEHOLDER`** — small and mechanical, flagged since session 23, still not
   done. `MetaWriter.kt` (3 sites) and `JiraRoutes.kt` (2 sites).
2. **Phase 4** (`docs/features/access-control.md` §15): every remaining read path gets the
   `/*ACL*/` predicate — item, tree, children, traces, breakdown, dependency graph, modules,
   objects, tables, JIRA issues and link graph, Windchill documents, statistics, search, and every
   leak named in §7. The big one, and the spec says explicitly it must not be split across a
   release.

Before either: **commit this session's work** (`feature/access-control` is uncommitted past
`eb16b0a`) and decide whether to open a PR now or keep building on the branch.

## State as of 2026-08-16 (session 25) — the Docker-gated work session 24 left open

Branch **`master`**. Docker became reachable on this machine; this session did exactly the two
things session 24's "Resume here" named as blocked on that, and nothing else — phase 3 has not
started.

| # | Change | Where |
|---|---|---|
| 1 | **`mvn -Pdocker test` actually run** — 521 docker-tagged tests, 0 failures, including `AccessControlFeatureTest` (5), and session 24's edits to `ReviewFeatureTest` (17) and `StatisticsFeatureTest` (22) | whole docker-tagged suite |
| 2 | **The owed `PROFILE` measurement, taken** — form A vs form B on `MODULE_OBJECTS`/`COUNT_MODULE_OBJECTS`, at three `$acl` sizes, against a synthetic 984-object module plus 4 000 decoy objects in 6 other modules (so `__moduleUrl` has real selectivity — see the method note below) | ADR 0016 §8, rewritten with the numbers |
| 3 | **Decision: form A ships, unchanged** — the a-priori guess in §6.1 (form B wins) was wrong; recorded as such rather than quietly corrected | `AccessCypher.kt` doc comment, `docs/features/access-control.md` §6.1 |

### The measurement, and why the a-priori guess was wrong

No DOORS export or JIRA set was reachable from this machine (DOORS is Windows-only; no JIRA
credential here either), so "a module shaped like the reference export" was built synthetically
rather than imported: a disposable `neo4j:2026.06.0-community` container (not Testcontainers — a
plain `docker run` plus `cypher-shell`, since this was a one-off measurement, not a permanent test),
seeded with 984 `:DOORSObject:DOORSRequirement:SEItem` nodes on one `__moduleUrl`, one-to-one across
5 `:__AccessCategory` nodes, **plus 4 000 decoy objects across 6 other module URLs**. That second
part is load-bearing and was not in the original plan: without decoys, every `:DOORSObject` in the
database belongs to the one module, `__moduleUrl = $u` has 100% selectivity, and the planner answers
with a full label scan instead of the `doors_object_module` index seek a real multi-module deployment
would get — measuring an artifact of the fixture rather than the predicate. Same schema as
production (`importers/.../doors/schema.py`, `MetaSchema.kt`), `cypher-shell PROFILE`, `$seesAll =
false` throughout.

**Form A wins, and not narrowly**, except at the smallest `$acl`:

| `$acl` size | form A | form B | delta |
|---|---|---|---|
| 1 category | 4 525 | 4 340 | B −4% |
| 2 categories | 5 116 | 7 098 | A −28% |
| 4 categories | 6 298 | 10 841 | A −42% |

§6.1's guess was that B wins because it reads no property in the inner loop. It doesn't read one,
but `any(cat IN acl WHERE EXISTS {...})` opens one relationship-existence subquery **per entry in
`$acl`**, per row — cost scales with how many categories a caller's groups collectively grant, which
is realistically more than one. Form A expands each node's own category edges once, regardless of
`$acl`'s size, and reads a property only on edges that exist. B's single-category win is the narrow
case, not the common one. **No call site changes** — `AccessCypher.visible()` is untouched, exactly
because the measurement confirmed what already shipped.

### What is still open, unchanged from session 24

- **`AccessAdminService` and the reconciler do not exist** — phase 3 (`AccessContainment`,
  `AccessReconciler`, `POST /api/v1/access/reconcile`, the startup pass, the import-pipeline hook).
  Not started this session; the two design decisions session 24 already settled (DOORS: no
  pre-import tree enumeration; JIRA: RBAC is the gate, drop the project allow-list) still stand and
  do not need re-litigating when phase 3 starts.
- **`CurrentUser.PLACEHOLDER` still writes `"system"`** on every Tier-2 write (`security/CurrentUser.kt`,
  used from `MetaWriter.kt` and the two JIRA settings routes). Small and mechanical, flagged as
  low-risk to pick up "once someone is in the neighbourhood of these files" since session 23 —
  neither session 24 nor this one touched it.
- Everything else session 24 listed as not done (no frontend Access views, no live sign-in
  verification beyond what `AccessControlFeatureTest` now exercises against a real database) is
  unchanged.

### Verified

- `mvn -Pdocker test` — **521 tests, 0 failures**, against `neo4j:2026.06.0-community` via
  Testcontainers. First time this suite has actually run since access control started.
- `mvn compile` — clean after the doc-comment edits in `AccessCypher.kt` (no logic changed).
- Not re-run this session: `mvn verify` (non-docker) and the frontend suite — no frontend or
  non-docker backend code changed.

### Resume here

**Phase 3** (`docs/features/access-control.md` §15) is next, per session 24's own "Resume here",
now genuinely unblocked rather than blocked-on-Docker: `AccessContainment`, `AccessReconciler`
(propagate/retract/seed), `POST /api/v1/access/reconcile`, the startup pass, and the import-pipeline
hook. It is a multi-file feature spanning all three sources — worth confirming scope/order with
whoever resumes before diving in, rather than assuming session 24's sketch is still exactly right.

---

## State as of 2026-08-15 (session 24) — access control, phase 2: model, resolver, predicate

Branch **`master`**, continuing directly from session 23 (phase 1) in the same day. Same design
docs: **ADR 0016** (now with a new §8), **`docs/features/access-control.md`** §15's build order.
Phase 2's scope exactly: the model, `AccessResolver`, `AccessCypher.visible()`, `AccessGuardTest`,
applied to **exactly one endpoint** — `/modules/{ref}/objects`. Every other read and write in the
backend is still unfiltered, each one now named in `AccessGuardTest`'s exemption list with the
phase that will filter it (4 for reads, 5 for writes) — that list is the honest map of what phase 2
did *not* do, not an oversight.

| # | Change | Where |
|---|---|---|
| 1 | **The model** — `:__AccessCategory` (Shape D, carries `:__Meta`), `:__Group` (not `:__Meta`, ADR 0016 §6.2), `__inAccessCategory`, `__mayRead` | `domain/GraphNames.kt` (new `GroupProp` object alongside `MetaProp`) |
| 2 | **Schema** — `access_category_key`, `group_key` uniqueness constraints, applied at boot alongside `:__Meta`'s | `meta/MetaSchema.kt` |
| 3 | **`AccessResolver`** — `groups` claim → `AccessSet(seesAll, categoryIds)`, cached on the sorted group-key set, invalidated by a version counter (nothing bumps it yet — `AccessAdminService` is phase 6) | `security/AccessResolver.kt` |
| 4 | **`AccessCypher`** — `RESOLVE_GROUPS` (the resolver's one query) and `visible(alias)`, the `/*ACL*/`-marked predicate | `graph/cypher/AccessCypher.kt` |
| 5 | **The `AccessSet`-binding `executeRead` overload** — the one place `$seesAll`/`$acl` are bound, so no call site assembles them by hand | `graph/Read.kt` |
| 6 | **`/modules/{ref}/objects` filtered** — `ReviewCypher.MODULE_OBJECTS`/`COUNT_MODULE_OBJECTS` now embed the predicate; `ReviewProjection.getModuleObjects` takes an `AccessSet`; the route resolves it from the caller's principal | `graph/cypher/ReviewCypher.kt`, `source/doors/ReviewProjection.kt`, `api/routes/ReviewRoutes.kt` |
| 7 | **`AccessGuardTest`** — reads every statement in `graph/cypher/` + `MetaSchema`, same premise as `GraphNamesTest`; ~70 statements exempted by name and reason, 2 filtered | `security/AccessGuardTest.kt` |
| 8 | **`AccessControlFeatureTest`** — the phase's acceptance test, hand-tagging a module via raw Cypher and asserting visible-to-one/invisible-to-another, `seesAll`, `everyGroup`, and untagged-is-invisible-to-everyone | `AccessControlFeatureTest.kt` (docker-tagged) |

### The one design call worth flagging: `const val` → `val`

`ReviewCypher.MODULE_OBJECTS` and `COUNT_MODULE_OBJECTS` changed from `const val` to `val`, the
only two statements in the codebase that are not compile-time constants. `AccessCypher.visible()`
is a function (it takes the bound alias as a parameter — `visible("o")`), and a Kotlin `const val`
cannot embed a function call, only other constants. Every *name* the predicate embeds is still a
single interpolated constant (ADR 0010 is intact); only the mechanism producing the final string
differs, and only for statements that call `visible()`. Every other filtered statement phase 4
adds will need the same change — this is not a one-off.

### `AccessGuardTest` was built statement-first, not audited by eye

Rather than manually classifying ~70 statements across every `graph/cypher/*.kt` file (error-prone
at that count), the test was written with the enumeration and the label-touch check first and an
**empty** exemption map, run, and every statement it flagged got a real, specific reason added —
iterating against the compiler rather than trying to get a from-memory audit right in one pass.
Verified the way `GraphNamesTest` was: the marker was stripped from both filtered statements by
hand, the test failed exactly on those two, and the fix was reverted.

### What is not done, and would be easy to assume is

- **The `PROFILE` measurement is still owed.** §6.1 asks for form A vs form B to be measured
  against the reference module and the JIRA set before the predicate's exact shape is settled.
  This session had no reachable Docker/Podman daemon (same gap as session 23), so **form A ships
  provisionally** — correct, but not proven faster or slower than form B. ADR 0016 §8 has the full
  writeup and what running the measurement later looks like. This matters more than it sounds:
  phase 4 is about to multiply whichever form is chosen across dozens of call sites.
- **`AccessControlFeatureTest` has not actually run.** It compiles; `mvn -Pdocker test` is what
  would execute it, and could not be run this session for the same reason.
- **`ReviewFeatureTest` and `StatisticsFeatureTest`'s edits (the new `access` parameter, passed as
  a `seesAll` constant) have not run live either** — same Docker gap. They compile, and the
  `seesAll` value should make every existing assertion behave exactly as before, but "should" is
  not "verified" for a docker-tagged suite.
- **No frontend change at all.** Phase 2 is backend-only by the spec's own build order; nothing in
  `docs/features/access-control.md` §10 (the Access views) starts before phase 6.
- **`AccessAdminService` does not exist.** Categories, grants and containers are still hand-written
  Cypher in a test fixture; there is no write path yet, so `AccessResolver.invalidate()` has no
  caller. Phase 6.
- **The reconciler does not exist.** Nothing propagates a container's tag to its members yet —
  `AccessControlFeatureTest` tags the one object directly. Phase 3.
- **Everything session 23 left open is still open**: no live sign-in verification, and
  `CurrentUser.PLACEHOLDER` still writes `"system"`.

### Verified

- `mvn verify` — **366 tests, 0 failures** (was 362 at session 23's close; +4: `AccessGuardTest`).
  `GraphNamesTest` and `AccessGuardTest` both pass and were both broken deliberately to confirm
  they catch the regression they exist to catch.
- **Not verified live, and not verified under `mvn -Pdocker test`.** No Docker/Podman daemon was
  reachable this session either. `AccessControlFeatureTest` (new), and the two existing
  docker-tagged suites this session edited (`ReviewFeatureTest`, `StatisticsFeatureTest`), are
  unrun. Phase 2's own acceptance line — *"One module tagged by hand in Cypher is visible to one
  group and invisible to another, and the db-hit numbers are in ADR 0016"* — is therefore only
  half true: the behaviour is implemented and the non-container guard tests hold it in place, but
  nobody has watched `AccessControlFeatureTest` pass against a real Neo4j, and the db-hit numbers
  are not in the ADR.
- No frontend changes this session, so frontend numbers are unchanged from session 23 (272 specs).

### Resume here

**First, before anything else in phase 2 or 3**: get a Docker or Podman daemon reachable and run
`mvn -Pdocker test`. This clears three things at once — confirms `AccessControlFeatureTest` and the
edited `ReviewFeatureTest`/`StatisticsFeatureTest` actually pass, and is the environment the
`PROFILE` measurement needs anyway. Do the measurement in the same sitting: `PROFILE` form A (what
ships) against form B (`docs/features/access-control.md` §6.1) for `MODULE_OBJECTS` and
`COUNT_MODULE_OBJECTS`, record db hits in ADR 0016 §8, and change `AccessCypher.visible()` if B
wins — cheaply, since it is the one function every filtered statement calls through.

**Then phase 3** (`docs/features/access-control.md` §15): `AccessContainment`, `AccessReconciler`
(propagate/retract/seed), `POST /api/v1/access/reconcile`, the startup pass, and the import-pipeline
hook. Two design decisions from the prior session's discussion are already settled and do not need
re-litigating:

- **DOORS**: no pre-import tree enumeration. A module is tagged only on or after it is actually
  imported, via the existing Unassigned-queue behaviour (§10.2) — unmodified. The DXL for exporting
  a module tree is explicitly out of scope, to be written in a separate future session.
- **JIRA**: **"RBAC is the gate."** The importer moves to importing every available project
  unconditionally — `JiraSettingsStore`'s `projectKeys` allow-list and the project-picker UI in
  `features/settings/jira/` are dropped, `JiraJql.kt`'s `project IN (...)` clause goes away, and the
  existing `inProject`/`:JiraProject` relationship `IssueMapper.kt` already writes becomes what the
  reconciler traverses to gate visibility. `GET /api/v1/jira/projects`'s reason for existing shrinks
  once every project is always imported — decide then whether to drop it or repoint it at showing
  project names in the Access views. Not yet implemented; this is a design decision made in
  conversation, not code written.

## State as of 2026-08-15 (session 23) — access control, phase 1: Keycloak realm + the session

Branch **`master`**. Full design: **ADR 0016** (the authorization model), **ADR 0017** (the
backend is the OIDC client), **`docs/features/access-control.md`** (the spec, §15 the build
order). This session is **phase 1 only, by explicit user choice** — the spec's own words are "Build
it in the order of §15 and nothing else," and phase 1 alone touched every route in the backend.
**No data filtering exists yet.** Every endpoint now requires a session; nothing yet asks whether
that session's groups may see the object being requested — that is phase 2 onward.

| # | Change | Where |
|---|---|---|
| 1 | **Hand-rolled OIDC Authorization Code flow with PKCE** — discovery, JWKS-validated `id_token`, refresh — not driven through `ktor-server-auth`'s `oauth {}` provider, which fights a per-attempt PKCE verifier | `security/Oidc.kt` |
| 2 | **The session** — cookie name, CSRF header name, the session-authentication provider, all one declaration | `security/Session.kt` |
| 3 | **`/auth/login`, `/auth/callback`, `/auth/me`, `/auth/logout`** | `api/routes/AuthRoutes.kt` |
| 4 | **The route-tree guard** — every feature route file now sits inside `requireSecSession { }`; only `/health`, `/ready`, `/auth/login`, `/auth/callback` are outside it | `api/Routes.kt` |
| 5 | **`AuthSettings`** — `auth.*` in `application.yaml`; every value but the client secret has a working dev default | `config/AuthSettings.kt` |
| 6 | **The dev Keycloak** — realm export (client, 4 roles, `/SEC/*` groups, 3 test users) + a compose service | `deploy/keycloak/sec-realm.json`, `deploy/docker-compose.dev.yml` |
| 7 | **The frontend shell** — `AuthStore` (`/auth/me` as a signal), `authInterceptor` (credentials + CSRF header + 401-navigates), the toolbar's user menu now shows the real name/email/roles/groups and a working sign-out | `core/auth/`, `layout/toolbar/` |

### Why hand-rolled, not the library's `oauth {}` provider

Read `security/Oidc.kt`'s class doc before touching this. The short version: `oauth {}` is shaped
for "redirect to a third-party login," where `providerLookup` is invoked independently on the
login request and the callback request with no supported way to carry a per-attempt PKCE
`code_verifier` between them except abusing `onStateCreated`. Hand-rolling it — plain
unauthenticated routes, this backend's own `pending` map keyed by `state`, manual JWKS validation
via the `com.auth0` classes `ktor-server-auth-jwt` brings transitively — is about 350 lines and
every one of them is inspectable. ADR 0017 anticipated exactly this ("verify PKCE support... pass
`code_challenge` through `extraAuthParameters` if the provider does not expose it directly") but
what actually shipped skips the provider's redirect/callback machinery entirely rather than
fighting it.

### The one thing that is not obvious about `requireSecSession`

`Session.kt`'s `requireSecSession { }` bundles **two** rules in one wrapper — the session guard
*and* the CSRF double-submit check — because a route registered inside it needs both, always, and
a route that only got one would be a silent gap. The CSRF intercept has a one-line guard that
matters: `call.principal<SecPrincipal>() ?: return@intercept`. Without it, a request with no
session at all reaches the CSRF check too (Ktor's `Call` phase runs after `AuthenticatePhase`
regardless of what the authentication challenge already sent), and it emits its **own** competing
`403` response, silently turning "no session" into the wrong status code. `AuthGuardTest` is what
caught this — first run reported `403` where `401` was expected.

### What is not done, and would be easy to assume is

- **No data filtering, anywhere.** Phase 2 (`AccessResolver`, `AccessCypher.visible()`,
  `AccessGuardTest`) has not started. Every authenticated user currently sees every object.
- **`CurrentUser.PLACEHOLDER` still writes `"system"`** as `__createdBy`/`__updatedBy` on every
  Tier-2 write. A real username is sitting right there in `SecPrincipal` now, and wiring it in
  would be a small, mechanical change — deliberately not made this session, because it touches
  every existing meta-writing route and phase 1's stated scope is the session, not that.
- **`requireRole(Role.X)` does not exist.** `security/Roles.kt` declares the four role strings and
  says why the plugin waits for phase 5: it has no route to guard yet, and an untested plugin is
  scaffolding.
- **No frontend route guards** (`canRead` / `canAdminister` / `canManageAccess` from spec §10.1).
  Same reasoning as `requireRole` — `/settings/*` and `/access/*` are not gated yet, so there is
  nothing for a guard to sit in front of. The `authInterceptor`'s 401-navigation already enforces
  "you need a session" at the HTTP layer regardless.
- **No `403` in-app refusal panel.** Nothing in phase 1 can produce a `403` (no role-gated route
  exists), so the frontend has nothing to render one against yet. The interceptor already leaves a
  `403` alone rather than navigating, which is the half of the split phase 1 could actually build.
- **"Connected graph/database name"** from `CLAUDE.md` §9's user-menu sketch is still not shown —
  nothing in the API reports it, before or after this session.
- **No `toolbar.spec.ts`.** The component never had one; `AuthStore.signOut()`'s real behaviour is
  covered in `auth-store.spec.ts`, and the toolbar component itself is now a thin pass-through to
  it. Testing the Material `mat-menu` overlay's rendered content would need a CDK test harness this
  codebase has never used anywhere else — a reasonable next addition, not done here.
- **`docs/RUNNING.md` is untouched.** Its Keycloak section is explicitly phase 7 work
  (`docs/features/access-control.md` §15).

### Verified

- `mvn verify` — **362 tests, 0 failures** (was 349 at session 22's close; +13: `OidcFlowTest` (8,
  a real embedded fake Keycloak — see below), `AuthGuardTest` (3), plus the two fixed by the trap
  above staying fixed).
- `npm run lint` clean, **272 frontend specs** (+15: `auth-store.spec.ts`,
  `auth.interceptor.spec.ts`), `npm run build` green.
- **Not verified live.** Docker/podman's daemon was not reachable on this machine this session
  (client present, socket absent) — the user chose to start it themselves and drive live
  verification in a follow-up. Phase 1's own acceptance line — *"Signing out and back in as two
  different users shows two different names and role sets, and every `/api/v1` call without a
  session is a `401`"* — is therefore unmet in the one sense that matters: nobody has watched it
  happen in a browser. Everything below **is** verified, short of that.

### `OidcFlowTest` is worth reading before extending `Oidc.kt`

It runs against a **real embedded Netty server** on a loopback port, not a `MockEngine`-backed
`HttpClient`. The reason is specific and easy to re-break: `jwks-rsa`'s `JwkProviderBuilder` fetches
the JWKS document with its own `java.net.URL` connection, entirely outside the `HttpClient` `Oidc`
is handed — a mocked client leaves signature verification, the one part of this flow that is
actually hard to get right, completely untested. Covers the PKCE challenge (an RFC 7636 test
vector), redirect-target sanitisation (the open-redirect guard), a full round trip with the nonce
threaded through correctly, `refresh()` re-reading roles/groups from a freshly issued token, and
four rejections: unrecognised `state`, untrusted signing key, wrong audience, expired token.

### Resume here

**Phase 2** (`docs/features/access-control.md` §15): the model (`__AccessCategory`, `__Group`,
`__mayRead`, `__inAccessCategory` — all in `domain/GraphNames.kt`, per ADR 0010, no new names file),
`MetaSchema` additions, `AccessResolver` (groups → `AccessSet`, cached, version-invalidated),
`AccessCypher.visible()`, `AccessGuardTest`, and the `PROFILE` measurement against
`/modules/{ref}/objects` with the db-hit numbers recorded in ADR 0016. Its acceptance line: *"One
module tagged by hand in Cypher is visible to one group and invisible to another."*

**Before phase 2**, in whichever order the next session finds convenient:

1. **Start Docker/podman and actually sign in.** `docker compose -f deploy/docker-compose.dev.yml
   up` (needs `SEC_NEO4J_PASSWORD` and `SEC_KEYCLOAK_ADMIN_PASSWORD` set), start the backend with
   `SEC_OIDC_CLIENT_SECRET=sec-backend-dev-secret` (already in `.run/Backend.run.xml`) and
   `SEC_AUTH_FRONTEND_URL=http://localhost:4200`, `ng serve`, then sign in as `sec-dev-user` and
   `sec-dev-admin` (`docs/KEYCLOAK_SETUP.md` §7 has the third, `sec-dev-nogroup`, for the empty
   application) and confirm the user menu shows two different name/role/group sets. This is the
   one thing this session could not do.
2. **`docs/adr/0016-authorization-model.md` §6.1's two candidate predicate forms (A/B) need the
   `PROFILE` measurement** before phase 2 writes `AccessCypher.visible()` — that decision was
   deferred to phase 2 on purpose (the spec table says so), but do it early in that phase rather
   than late, since every phase-2 read path is threaded through whichever form wins.
3. **Wire `CurrentUser.PLACEHOLDER` to the real principal** (see "what is not done" above) — cheap,
   mechanical, and removes a lie that exists only because auth did not used to. Not required for
   phase 2, but there is no reason to keep deferring it once someone is in the neighbourhood of
   these files.

## State as of 2026-08-12 (session 22) — the Windchill importer, fed by an uploaded export

Branch **`master`** (session 21 was merged as PR #5 before this started). The whole design is in
**`docs/adr/0015`** — read it before changing anything below; it is short and every paragraph is a
decision that cost something.

| # | Change | Where |
|---|---|---|
| 1 | **`WindchillImporter`** — three phases, fed by an upload rather than connected to a host | `source/windchill/` |
| 2 | **`ImportRequest`** — a run can now be *given* its input, source-agnostically | `importer/ImportRequest.kt` |
| 3 | **`POST /api/v1/windchill/import`** — the upload **is** the import, one request (R7) | `api/routes/WindchillRoutes.kt` |
| 4 | **`GET /api/v1/windchill/documents`** — the whole set, unpaged, capped at 20 000 | `WindchillProjection.kt` |
| 5 | **The Documents view** — version groups, collapsible, instant search over every column | `features/documents/windchill/` |
| 6 | **`/settings/windchill`** — the address, the file picker, the last import | `features/settings/windchill/` |
| 7 | The Python Windchill stubs are **deleted** — two importers for one source is the thing this repo forbids | `importers/` |

### The four decisions the user made, so they are not re-litigated

1. **Valid JSON only.** The exporter emitted Python dict syntax at one point; that is a `400` naming
   the parse position, not a lenient reader.
2. **The whole set loads into the browser.** ~1 500 documents, instant search, and — decisively —
   grouping a document's versions needs all of them at once. The opposite call from JIRA Issues.
3. **Newest version first** inside a group.
4. **The export is the whole truth**, with a mass-deletion *warning* rather than a narrower sweep.
   A file covering one folder removes every document it does not mention; the run says so.

### What is not done, and would be easy to assume is

- **No folder hierarchy.** `FolderLocation` is a string on the document, as Windchill sends it.
  There are no folder nodes and no `__child`; when there are, R3 says where they go.
- **No Tier 2 on a document** — no notes, no tags, nothing. The re-import test protects an
  annotation that nothing writes yet, on purpose: what it pins is that `MERGE … SET` leaves
  relationships alone, which has to stay true as this source grows.
- **`/windchill/import` deletes documents and is unguarded**, like every other route here. That is
  the standing authorization gap (ADR 0014 point 9), not a new one — but this is the first endpoint
  where "unguarded" and "deletes data" are the same sentence.
- **Dates on the settings page are raw ISO**, the same unfinished thing the Issues table has. One
  decision, two views, still not made.
- **The file is read into memory whole**, on the client and again on the server. Fine at 1 500
  documents; the 64 MB cap is what stands between a bigger export and an `OutOfMemoryError`.

### The traps this session hit, in the order they cost time

1. **`|` is the wrong separator for a sort key**, and it looked right. `__sortKey` is
   `<number><sep><complemented version>`, and a separator only works if it sorts *below* every
   character a Number can contain — `|` sorts above every letter and digit, so `ABC-1` came before
   `ABC`. Groups stayed adjacent, which is exactly why it passed a glance. It is **U+0001** now, and
   a test pins it.
2. **`[context]` on `<ag-grid-angular>` is an input of its own, and it beats the same key inside
   `gridOptions`.** A context passed only through `gridOptions` reaches a renderer as `undefined`,
   `this.context?.toggleGroup(…)` is a silent no-op, and the twisty does nothing with no error
   anywhere. **Every spec passed.** Found by clicking it.
3. **ag-grid refreshes a cell only when its *value getter's output* changed.** A group header's
   folder, name and number read the same open or shut, so a header whose expanded state travelled in
   its row data is a header ag-grid never redraws — the versions below it vanished and the arrow went
   on pointing down. Two wrong fixes were tried first: folding the state into the row **id** (which
   made a row's identity change while the row stayed the same thing, and broke re-expanding), and
   `refreshCells({force: true})` from an effect (which fixed the rows and not the header, because
   effect ordering against the grid's own update is not guaranteed). The right shape is that the
   renderer **reads the view's signal** through `context.isExpanded(number)`: Angular then redraws
   the arrow for the ordinary reason and ag-grid is not involved at all.
4. **A cell renderer's row must be a signal.** ag-grid updates a row in place and calls `refresh`,
   and a plain field written there never marks an OnPush view dirty in a zoneless application.
5. **An indent sized to "line up with the header's text" is not an indent.** 13px put the two
   columns of text within two pixels of each other; it is 26px and visible.
6. **Bash heredocs mangled two large files** in this session (a Kotlin file and an HTML template)
   and once turned `''` into a raw control byte in a `.kt` file. Use the Write tool for
   anything large; `cat -A` is what caught the control byte.

### Verified this session

- `mvn verify` — **349** tests; `mvn -Pdocker test` — **149**, both 0 failures. Lint clean,
  **261** frontend specs, build green.
- **Live, against the running application**: the committed sample imported (2 documents, `paged:
  true`, the run amber with the next-link warning); a six-document export with two version groups
  imported and drawn — headers bold on the blue band with no version and no state, `10 [1]` above
  `02 [2]` above `01 [2]`, which a string sort gets wrong; collapse and re-expand both directions
  with the arrow following; `in work` matching the **State** column with no request at all and the
  header keeping its whole-set count of 3; the sweep removing 5 of 6 with the mass-deletion warning;
  and `{"value":[]}` refused as a `400` problem detail rather than run.
- **The graph now holds six fabricated Windchill documents** from that testing. They are harmless —
  the first real export deletes them, because the export is the whole truth — but they are there.

### Resume here

Unchanged from session 21 for JIRA: the detail drawer, the issue-type icon proxy, the empty state's
deep link, and **the date formatting decision**, which now shows up in two places rather than one.

For Windchill specifically, in the order a user will hit them:

1. **Import a real export** and see what a production file does that the sample does not — 1 500
   documents rather than 6, and real `Version` strings. The version parser reads three digit runs
   and warns on a version with none; that warning is the thing to watch.
2. **Folder hierarchy**, if the folder column turns out to be how people navigate. `__child`, per R3.
3. **A confirm-before-delete on the upload**, if the mass-deletion warning proves too weak. The count
   is already known before the sweep runs, so this is a dialog rather than a redesign.

---

## State as of 2026-08-12 (session 21) — search every field, and a diagram of related issues

Branch **`feature/jira-issues-table`**. Two changes asked for after seeing steps 9–11 working, both
widening something the spec deliberately narrowed. Both are ADR 0014, points 22 and 23.

| # | Change | Where |
|---|---|---|
| 1 | **The Issues search reads every field**, the issue's and its projection's | `JiraCypher.MATCHES_ANY_FIELD` |
| 2 | **A Related column**, before the link-out, with the graph icon only where there are links | `issues/cells/jira-links-cell.ts` |
| 3 | **The related-issues diagram** — ELK layout reused, drawing not | `features/jira/links/` |
| 4 | **`GET /api/v1/jira/issues/{ref}/graph`** — BFS walk, depth 1–5, 300-node cap | `JiraLinkGraphProjection.kt` |

### What is not done, and would be easy to assume is

- **No full-text index.** The widened search is a scan: per issue, over every property it carries,
  with no index and no query governor. Comfortable at 784 issues; at the spec's 50 000 target it is
  millions of comparisons per search, and the answer then is a full-text index built from the field
  catalogue at import time. That is a design, not a tweak.
- **The diagram has no edge labels on screen** — the link type is JIRA's own word and is carried in
  the data and shown on hover, but drawing it beside the line needs a label position ELK can be
  asked for and currently is not.
- **Everything still missing from session 20 is still missing**: the detail drawer, the issue-type
  icon, the empty state's deep link, the console's log filter.

### The traps this session hit

1. **`toString()` errors on a list**, and `labels` is the field a person is most likely to search —
   so the naive widening would have failed the *whole request* at runtime rather than narrowing
   badly. The predicate branches on `v IS :: LIST<ANY>` and searches element by element. Neo4j
   2026.06 accepts the type predicate; that was the one unknown worth testing first.
2. **A widened search reads the projection, so the projection has to be matched before the filter**
   — in the page query *and* the count query. Both now `OPTIONAL MATCH` it above the `WHERE`.
3. **Two old search tests failed and were right to.** `thermal` now matches an issue that carries it
   as a *label* as well as the one with it in its summary. The fixture also had no `summary`
   property at all — the name lived only in `__name`, which the search deliberately does not read —
   so it was made realistic. Third time this feature's fixtures have been simpler than reality.
4. **Neo4j will not take a parameter as a variable-length bound**, which is why both graph walks in
   this repository are breadth-first loops in Kotlin rather than one closure pattern. Read
   `DependencyGraphCypher`'s note before writing a third.
5. **A walk seeded with an unknown id produces a non-empty *set* and an empty *graph*.** Reading the
   nodes is what tells a typo from an issue with no links, and the test caught it.
6. **Kotlin `const val` initialisers resolve in declaration order**, so a constant interpolated into
   another has to be declared above it. Twice this session: `MATCHES_ANY_FIELD` and
   `JIRA_ISSUE_GRAPH`.

### Verified this session

- `mvn test` — **319**; `mvn -Pdocker test` — **138**, both 0 failures. Lint clean, **240** frontend
  specs, build green.
- **Live**: searching `idea` matches on a status that exists only on the projection, `juan` matches
  on a reporter; the Related column shows the icon on exactly the two linked issues; the diagram
  draws OTS-1 → OTS-2 with type, key, status and summary on each node and the seed named in words.
- Incidentally proved by the new search: the two `Subtarea` issues in the test instance genuinely
  have no parent stored, so the absent `subTaskOf` edges are the data rather than the importer.

### Four finishing changes, after the above

1. **The Related and open-in-JIRA icons are centred**, and the rule that centres them lives in
   `styles/_grid.scss`. It was in the feature's own stylesheet, where it matched nothing:
   **ag-grid builds cells at runtime, so Angular's emulated encapsulation never stamps them** and a
   `.sec-grid__cell` rule in a component `.scss` is silently dead. The icons sat 18px left of
   centre for exactly that reason. There is now a `.sec-grid__cell--control` class for a cell whose
   whole content is one icon, and `.sec-grid__header-cell--stale` moved for the same reason.
2. **JIRA cells wrap instead of truncating at 120 characters** (ADR 0014 point 24). Lists still cap
   at three chips and a `+n` — that is a count of values, not a truncation of one.
3. **The Req review comment box fills its cell.** It stopped at its own text, leaving up to 20px of
   dead space below it that looked like a margin and did not take a click. The chain is: the cell is
   a column flex container, its child is told to fill it, and *that* child is told to stretch —
   because ag-grid's wrapper is a flex row that **centres** its child, so one level was not enough.
   Both selectors are `> *` under our own class; neither names an `.ag-*` internal.
4. **"Links to deleted objects" is now "Links to unresolved objects"** and matches both a target
   DOORS deleted and one whose module has not been imported. The model keeps those apart because
   they ask for opposite fixes; a reviewer sweeping a module is not making that distinction yet.
   Live on SRD it narrows 486 rows to 350, where the old filter found a handful.
   `docs/REQ_REVIEW.md` §3 is updated.

**One flake seen once**: `dependency-graph-dialog.spec.ts` failed a text assertion in a full run and
passed alone and on re-run. It mounts ELK; not chased.

### Resume here

Unchanged from session 20 — the detail drawer, the icon proxy, the empty state's link — plus the
date formatting question, which is now the most visible unfinished thing on the Issues table.

---

## State as of 2026-08-12 (session 20) — JIRA steps 9, 10 and 11: settings, console, columns

Branch **`feature/jira-issues-table`**. `docs/JIRA_ISSUES_FEATURE_SPEC.md` §18 numbers the steps;
**1–11 are done**, in the order 8 → 10 → 11 → 9 rather than the order the spec numbers them, for a
reason that came from running the application: until something in the UI could choose a project and
start an import, a column picker had nothing to pick from. Session 19 started its import with
`curl`.

| # | Change | Where |
|---|---|---|
| 1 | **`/settings` subtree** behind a toolbar gear — one path for RBAC to guard later | `app.routes.ts`, `layout/toolbar/` |
| 2 | **JIRA settings page** — connection, project chips, JQL preview, columns summary, import | `features/settings/jira/` |
| 3 | **Import console** — phase rail, live log, counters, cancel, history. Names no source | `features/settings/importers/` |
| 4 | **`ImportRunStore`** — reads the run resource, then follows it over SSE, with backoff | `core/import/` |
| 5 | **Column picker** — two panes, search by name and id, filters, drag order, stale section | `features/jira/columns/` |
| 6 | **Four endpoints** — field catalogue, columns, column defaults, live project proxy | `JiraRoutes.kt`, `JiraFieldsProjection.kt`, `JiraColumnStore.kt` |
| 7 | **Seven more departures**, points 15–21 | ADR 0014 |

### What is not done, and would be easy to assume is

- **No detail drawer** on row click (§13.2), **no issue-type icon** (it needs the icon proxy of
  §9.1), and **no deep link** from the Issues empty state to `/settings/jira` — the third is now
  buildable and simply is not built.
- **The console has no log level filter and no pause-on-scroll**, and its history rows do not expand
  to show a run's JQL and warnings (§13.6). The log pane, counters, cancel and history are built.
- **There is still no authorization anywhere.** `/settings/*` is one subtree so that one guard will
  cover it, and that guard does not exist (ADR 0014 point 9).
- **A date renders as JIRA sent it** — `2026-08-11T12:14:08.833+0200`. It is source data shown
  verbatim, which is consistent, and it is not pretty.

### The traps this session hit, in the order they cost time

1. **`coalesce(i[k], p[k])` is backwards, and the spec says to write it that way.** §7.4's own
   formula contradicts §7.2's storage decision: a complex value is stored *both* as JSON text on the
   issue and as a display string on the projection, so reading the issue first means the blob always
   wins. Live, every Status and Priority cell was a wall of JSON. **The test that should have caught
   it asserted the impossible case** — its fixture put the value on the projection alone, which no
   import produces, so it passed under either order. ADR 0014 point 21.
2. **The catalogue was keyed by `__id` instead of JIRA's `id`.** The synthesised resource URL and
   the field id are one character apart in a statement, and nothing downstream can tell them apart:
   a column keyed by a URL reads every cell as null. The docker test caught this one.
3. **A root-provided service with an eager `httpResource` field is fetched by every page that
   injects it.** The settings page wanted a column *summary* and pulled the whole 1 171-field
   catalogue with it. A resource wanted by one dialog is a factory method, not a field —
   `ModulesApiService` had the pattern already.
4. **The console sat on "Running" after the import finished.** The stream describes one run and says
   nothing about the importer list or the history, both of which were fetched once on load. An
   effect on the terminal status re-reads them. **Found by watching a real import**, not by a test.
5. **jsdom has no `EventSource`**, so anything touching the store needs a fake — `new EventSource()`
   is a `ReferenceError` otherwise. `import-run-store.spec.ts` carries one, and it is what makes the
   event half of the store testable at all.
6. **Material caps a dialog at 560px, and that beats the `width` you ask for.** A dialog opened with
   `width: '900px'` renders at 560 with its content clipped and says nothing. The two settings
   dialogs escape it with their own `maxWidth: '94vw'`, which is why nobody had met it; the escape
   is now in `SEC_MODAL_DIALOG` so the next dialog does not rediscover it. Found by looking at the
   picker on screen — every spec passed, because jsdom has no layout.

### Verified this session

- `mvn test` — **319 tests**; `mvn -Pdocker test` — **124 tests**, both 0 failures.
- `npm run lint` clean, **231 frontend specs**, `npm run build` green.
- **In the browser**: the settings page (connection, chips, JQL, summary, last run), an import
  started from the settings page, a second started from the console with its **log streaming live**
  and the phase rail advancing, and the Issues table drawing the six default columns.

### Resume here

The three gaps of §13.2 and §13.6 above, in whichever order the next reader cares about. The detail
drawer is the one a reviewer will ask for first; the icon proxy is the one that makes the Type
column look finished.

**Before anything else, look at a date in the Issues table.** It is the only thing on that screen
that still reads as raw data rather than as a value, and deciding what to do about it is a display
decision the alias map (R5) has no entry for yet.

---

## State as of 2026-08-12 (session 19) — JIRA build-order step 8, the Issues table

Branch **`feature/jira-issues-table`**, one commit. `docs/JIRA_ISSUES_FEATURE_SPEC.md` §18
numbers the steps; **1–8 are done**, with step 4's second half (the import console) still not.

| # | Change | Where |
|---|---|---|
| 1 | **`GET /api/v1/jira/issues`** — server-side paging, search and sort, with `ref` and `browseUrl` derived on read | `JiraIssuesProjection.kt`, `JiraCypher` |
| 2 | **`__sortKey` for issues and placeholders** — nine digits, not DOORS's six | `mapping/IssueMapper.kt`, `JiraGraphWriter.placeholderRow` |
| 3 | **The Issues table**, three fixed columns: type, key, link out | `features/jira/issues/` |
| 4 | **`settleGrid`** — waits for the grid's DOM to stop changing, instead of two fixed frames | `core/grid/grid-testing.ts` |

### What is not done, and would be easy to assume is

- **There is still no JIRA settings UI and no import console.** The backend has had both
  endpoints since step 4 — `GET`/`PUT /api/v1/jira/settings` for the project keys and JQL preview,
  and `POST /api/v1/import/jira/runs` with its SSE feed — but nothing in the application calls
  them, so today an import is started with `curl`. That is §13.5 and §13.6, build-order steps 10
  and 11, and it is the gap a user hits first.
- **Every configurable column is still empty.** `fieldIds`, `columns` and `values` run end to end
  and the route never passes any: the picker and `__JiraColumnConfig` are step 9. `SortField`
  therefore offers exactly one column, `key`.
- **The issue type is a name, not an icon.** The icon proxy (§9.1) does not exist, and an `<img>`
  to JIRA's own `iconUrl` would send the browser to a host it cannot authenticate against.
- **No detail drawer** (§13.2, last bullet) and **no deep link from the empty state** to
  `/settings/jira`, because that page does not exist yet.

### The traps this step hit, in the order they cost time

1. **`httpResource` drops its value the moment a new request starts.** Reading it directly means
   the whole view re-renders from nothing on every keystroke — including the search box that
   started the request, which is destroyed and re-created a quarter of a second after the user
   typed, taking the focus and the caret with it. This is trap 7 of session 14 ("a component inside
   an `@if` on a resource unmounts while that resource reloads") arriving through a different door,
   and it was **reported from the running app, not by the suite**. The fix is a `linkedSignal` that
   latches the last page; the regression test asserts the input's *identity*, because a re-created
   input looks identical and behaves nothing like the same one.
2. **In a spec, a debounced signal's timer does not start until the next change detection.** This
   TestBed has no auto-detection, so `dispatchEvent` then `await 300ms` measures the wait from
   whenever the next `detectChanges()` happens — the request is still unsent when the assertion
   runs, and the spec times out instead of failing. One `fixture.detectChanges()` between the event
   and the wait is the whole fix.
3. **`settle()` must never be called while a request is in flight** — the same `whenStable()` trap
   as session 14's item 15, met twice here: once after the debounce fires and once after a
   paginator click. Both now call `detectChanges()` and assert the request instead.
4. **`flushGridFrames()`'s two frames are not enough for a row with a cell renderer.** The fifth
   row was drawn some runs and not others, so a spec passed alone and failed in the suite.
   `settleGrid` waits for two consecutive identical readings instead of a fixed duration.
5. **Data imported before `__sortKey` existed sorts arbitrarily**, because `coalesce(…, '')` makes
   every row equal. Nothing warns; the table simply comes back in storage order. A re-import fixes
   it, and this is the general shape of every Tier-1 derivation added after an import has run.

### Verified this session

- `mvn test` green; `mvn -Pdocker test -Dtest=JiraIssuesReadTest` — **14 tests, 0 failures**.
- `npm run lint` clean, **209 frontend specs**, `npm run build` green.
- **A real import against the live JIRA Cloud instance** (9 issues, 59 fields, 14 issue types, 1
  link, 0 deletions, `SUCCEEDED`), then the table **in the browser**: JIRA's own order, reversed on
  a second header click, `scrum` narrowing to 6, `scrumzzz` giving the no-match sentence with the
  term kept in the box, and all five characters of a search landing in one box that never lost
  focus.

### Resume here

**Step 9 — column config**: `__JiraColumnConfig` (§10.2), the picker dialog (§13.3), stale columns
(§13.4). The read path already takes `fieldIds` and returns `columns`; what is missing is the
persisted set, the `GET`/`PUT /api/v1/jira/columns` pair, and the dialog.

**But consider steps 10 and 11 first.** Nothing in the UI can configure the JIRA projects or start
an import, so today a user cannot get data into the table without `curl`, and the picker has
nothing to pick from until they can. The build order puts columns first; the running application
argues for settings first.

---

## State as of 2026-08-12 (sessions 16–18) — JIRA, build-order steps 1–7

One continuous piece of work over three sessions, on branch **`feature/jira-issues-dynamic-view`**.
`docs/JIRA_ISSUES_FEATURE_SPEC.md` §18 numbers the steps; **1–7 are done, and step 4's second half
is not** (see below). The importer now runs all six of the spec's phases end to end.

| # | Change | Where |
|---|---|---|
| 1 | **A source-agnostic import pipeline with SSE progress** — run lifecycle, `:__ImportRun` history, live event stream | `importer/`, `api/routes/ImportRoutes.kt` |
| 2 | **The JIRA importer runs inside the backend** — the only source that does, and why | ADR 0013, `source/jira/` |
| 3 | **Phases 0–3**: preflight, issue types, field catalogue, and issues with property removal | `JiraImporter.kt`, `JiraCypher.kt` |
| 4 | **Two search protocols behind one contract** — Data Center pages by offset, Cloud by cursor | `JiraHttpClient.searchAll` |
| 5 | **Phases 4 and 5**: links, placeholders for targets outside the import, and the sweep | `JiraImporter.importLinks/sweep`, `JiraGraphWriter.writeLinks/sweep` |
| 6 | **Fourteen documented departures from the spec** | ADR 0014 — read it before "fixing" an inconsistency |

### What is not done, and would be easy to assume is

- **The import console (§13.6) does not exist.** Step 4 has two halves and only the backend half is
  built, so the SSE endpoint has no consumer outside tests. Nothing later depends on it.
- **Phase 6's post-import validation (§12) is not written.** The counters and the outcome are
  recorded; the five consistency checks that would turn a bad import into `SUCCEEDED_WITH_WARNINGS`
  are not. Note that one of the five as written is wrong: "no `JiraIssue` has both `__UNDEFINED` and
  a `summary`" — a placeholder gets a summary from the link payload, deliberately.
- **The `deleted` counter is real; `created` / `updated` / `unchanged` are not.** They would need a
  per-node diff on every write, which nothing yet needs.
- **There is no authorization anywhere** (ADR 0014 point 9). Not a JIRA gap — a whole-backend one.

### The traps this work hit, in the order they cost time

1. **JIRA Cloud and Data Center need two independent settings, not one.** `jira.auth` picks how the
   credential is sent; `jira.deployment` picks how issues are paged. ADR 0014 originally said one
   would imply the other — **that was wrong and is corrected in place**: Data Center accepts Basic
   auth too, so deriving the search path from the auth scheme points a Server host at an endpoint it
   does not have. Preflight warns when the configuration disagrees with what `/myself` returned
   (Cloud sends `accountId` and no `name`; Data Center the reverse).
2. **`ImportContext.params` replaced instead of merging.** Phase 3 recorded the JQL and silently
   erased the host, time zone and deployment preflight had recorded. Every unit test passed —the
   test double merged. **A live run is what caught it.** Now merges, with two tests.
3. **A finished run reported `phases: []` and `percent: null`.** Also caught by a live run, also
   invisible to the suite. Phases are not persisted, so a completed run repopulates them from the
   registered importer.
4. **`toString()` in Cypher takes a scalar** — not a list and not a map. A snapshot helper comparing
   `toString(labels(n))` fails at runtime with a type error rather than at parse time.
5. **`UNWIND` of an empty list drops the row**, which is exactly what the property-removal statement
   needs and is a trap for whoever appends to it: the `MERGE` and `SET` have already committed, but
   **nothing may ever be added after the `REMOVE`**.
6. **Cloud refuses unbounded JQL with a 400**, so a hand-run query needs a project clause. Its error
   messages come back **in the instance's own locale** — the test instance answers in Spanish.

### The traps step 7 added to that list

7. **The placeholder label was re-derived, wrongly, and ADR 0014 point 1 is what caught it.** A
   JIRA-local `:__UNRESOLVED` was built, on the argument that the DOORS importer's unscoped
   placeholder cleanup would otherwise reach JIRA nodes. The ADR had already settled it the other
   way — one concept, one name, `Not yet imported` already in `Aliases.kt` — and the ADR wins. The
   argument that produced the wrong answer is now **recorded in point 1** so the third derivation
   does not happen. What did survive from it: JIRA's own placeholder cleanup matches the **pair**
   `:JiraIssue:__UNDEFINED`, never the shared label alone, or a JIRA import would delete DOORS
   placeholders. The live graph has 318 of them.
8. **Two pre-existing tests failed the moment the sweep existed, and both were right to.** They
   imported all 50 fixture issues with one project configured, so 41 of them are now correctly
   deleted on the way out. Fixed by configuring every project in the export; one test — "phase 3
   does not delete" — was **removed**, because with phase 5 built its claim is no longer separately
   observable and test 7 asserts the same thing where it matters.
9. **The fixture has no sub-tasks and no removable link**, so `MERGE_SUB_TASKS`,
   `DELETE_STALE_SUB_TASKS` and the delete branch of `DELETE_STALE_LINKS` would have shipped never
   having run against a database. Three tests now inject them.

### Verified this session

- `mvn verify` — **BUILD SUCCESS, 307 tests, 0 failures**.
- `mvn -Pdocker test` — **99 tests, 0 failures**, 17 of them in `JiraIssueImportTest`.
- **A real import against the live JIRA Cloud instance**, all six phases: 14 issue types, 59 fields,
  9 issues, 9 projections, 1 link (`OTS-1 -[:linkedTo {typeName:"Relates"}]-> OTS-2`), 0 deletions,
  `SUCCEEDED` at 100 %. The 318 DOORS placeholders in the same database were untouched.

### Resume here

Step 8 — `GET /api/jira/issues` (§14.4) and the Issues table with the three fixed columns. Two
things to know before starting it:

- **A placeholder carries `:JiraIssue` and `:__UNDEFINED` and has no `__JiraProjection`.** Every
  read query has to say which it means; the tests use one shared `REAL_ISSUES` fragment for exactly
  this reason. R5's wording for it is already in `Aliases.kt` — *Not yet imported*.
- **`__version` on a placeholder is the string `unresolved`**, which the Version column will render
  verbatim unless the read path maps it.

---

## State as of 2026-08-10 (session 15) — deleted in DOORS, the expandable card, JIRA in the nav

**Written retrospectively.** The work landed on 2026-08-09 as **PR #2** and **PR #3** and neither
updated this file, so what follows was reconstructed from the three commits and re-verified against
the tree on 2026-08-10. **`git status` is clean**; all three feature branches are merged and still
exist locally and on the remote.

| # | Change | Where |
|---|---|---|
| 1 | **An object deleted in DOORS is labelled `:__DELETED`, not removed** — and every link still pointing at it is shown as the defect it is | `doors/importer.py` phase 6, ADR 0012, and every review/statistics projection |
| 2 | **`__inputLinks` are imported**, which closes `incomingComplete` and **deletes a standing caveat from two views** | `doors/importer.py`, `ReviewDtos.kt`, the graph dialog |
| 3 | **A graph node can show its whole statement** — a chevron under the clamped text, drawn only where the clamp actually hides something | `shared/requirement-card/`, `graph-canvas.ts` |
| 4 | **JIRA is a fourth source family in the sidenav**, with Issues and KIDS as empty states | `application.yaml`, `nav-group.ts`, `features/jira/` |

### 1 and 2 are one decision, and ADR 0012 is the thing to read

Not summarised here beyond the shape of it, because the ADR is 199 lines and argues every branch it
did not take. The shape: **DOORS deletes an object and leaves the links pointing at it**, so a
requirement goes on refining something that no longer exists. That is a defect nothing else in the
toolchain shows — and the obvious implementation destroys the evidence, because deleting the object
here too makes the referencing module look correct again.

So it keeps its id, its attributes and its type labels, gains `:__DELETED`, and leaves the tree and
every module listing. It is reachable **only** from the links that point at it, and those are drawn
struck through in error red, counted in Issues, filterable in the review table, and totalled in the
Statistics traceability band. Every one of them says the fix is **in DOORS** — this application
holds no copy of the link to remove.

Three things about it that are load-bearing and easy to undo by accident:

- **`:__DELETED` is the one Tier-1 name that is not a function of a single export.** It is a
  function of two — the export in hand and what the graph already held — which is a real departure
  from R1's "re-run the import, get byte-identical results", taken with open eyes because the fact
  being recorded *is* a difference between two imports.
- **Re-import deletes Tier 2, and this is the only place an importer ever may.** Annotations go
  with the object they were written on. Root `CLAUDE.md` R2 now names the exception rather than
  leaving it undocumented; a second one needs its own ADR.
- **There is no prune guard, deliberately.** A truncated export costs annotations and nothing else —
  re-importing the complete export restores the source data in full. The mitigation is
  `objects_newly_deleted` in the run report, and a guard was rejected because it buys a confirmation
  prompt at the price of a `--force` flag people learn to always pass.

The reconciliation is **seven set-based statements** driven by an `__importedAt` run stamp, with no
parameter that grows with the module. They are asserted clause by clause, without a database, by
`importers/src/sec_import/doors/tests/test_reconcile_cypher.py` — 15 tests, each named after the
thing that breaks if the clause goes missing. That file earns its place: several of the clauses are
one word long, and dropping one leaves an importer that still reports success while destroying the
links the whole design exists to expose. The two that would go first are
`coalesce(n.__importedAt, '')` (`NULL <> $ts` is NULL, which matches nothing, so the whole module
survives its first reconciliation) and the `NOT s:__DELETED` in the stale-`refersTo` prune (without
it, the first thing deleted is exactly the evidence).

**Change 2 is why a caveat disappeared rather than moved.** A module's export states every link
pointing *at* it, so importing `__inputLinks` makes an incoming link visible before the referencing
module exists here at all. The old *"only outgoing links are imported"* sentence would now tell a
reviewer to distrust an emptiness that carries real information, so it is gone from the review table
header and the dependency graph. What remains is the unresolved-modules banner, which names modules
and appears only when there are some.

### 3 is a canvas change wearing a card's clothes

The clamp to six lines is right — one forty-line requirement would make its band taller than the
screen. Having no way past it is not.

**The expansion state lives in `GraphCanvas`, not in the card, and that is the whole of the work.**
Every position on screen was computed from a height measured *before* the click, so a card that
grows where it stands overlaps the ones beneath it. The canvas keeps a ref-keyed set, feeds it into
the off-screen measure pass, and the existing measure → lay out → compress → draw pipeline treats
the expansion as the height change it is. The card exposes it as a `model`, so the Breakdown tab
gets a working toggle while binding nothing.

The chevron is drawn **only where the clamp is actually hiding something, measured rather than
assumed** — an `afterRenderEffect` compares `scrollHeight` against `clientHeight` after every
render. A graph node with a long statement gets the control, a two-line one does not, and a
Breakdown row never does, without either view being asked which it is. Two consequences were handled
rather than left: expanding marks the viewport dirty (auto-fit answers a taller diagram by scaling
down, and on a large graph that crosses the 50% compact threshold, which drops every card's body and
takes the text that was just asked for with it), and double-clicking the chevron no longer re-seeds
the graph.

**jsdom reports every height as 0**, so the arithmetic is covered on numbers by an exported
`isClamped` and the specs cover the binding around it. That is the shape to copy for anything else
that measures.

### 4 is small, and has one thing to keep in sync by hand

Two lazy routes rendering the same titled empty state Windchill and the Cameo views render. Order
lives in the backend's `application.yaml`, which owns it for every user; the frontend's
`DEFAULT_NAV_GROUPS` fallback was updated in step with it, and the pair is kept in sync **by hand**,
which a comment says at the site.

**Today the fallback is what actually renders** — `GET /api/v1/config/navigation` is still unwired
(it remains a comment in `api/Routes.kt`), so the YAML takes effect only when that lands. Verified
this session, not assumed: the endpoint has been listed as a 404 since session 9 and still is.

*KIDS* is left as the label given. Nothing claims to know what it expands to and the empty-state
sentence is deliberately thin rather than invented.

### ⚠ Three documentation defects — two found and fixed, one still open

**1. `importers/CLAUDE.md` contradicted itself and the code about annotations — FIXED this session.**
In the load-bearing-clauses list it said the ghost keeps *"`refersTo` only to other `:DOORSObject`s,
**and `:__Meta`** — R2 forbids leaving a note hanging off nothing"*. The code does the opposite:
`_DELETE_GHOST_META` detach-deletes them, and a later bullet in that same file said so correctly.
The stale clause was a fragment of the **superseded first version** of ADR 0012, whose reasoning
that same ADR now lists under rejected alternatives — R2 forbids the *application* writing to
imported nodes and forbids a meta node hanging off nothing; it does not require an annotation to
outlive its subject. It was the dangerous one of the three: nothing enforces it, and it read as
licence to spare `:__Meta` from the strip.

Clause 3 now reads *"the ghost keeps `refersTo` to other `:DOORSObject`s and **nothing else** — its
annotations are deleted outright and every other edge is stripped, because those are the only edges
a reviewer can act on"*, which is what statements 4a and 4b do and what the bullet further down that
file already said.

**2. Six statements or seven — FIXED this session.** The code is seven
(`RECONCILE_STATEMENTS`), numbered as six steps because 4a and 4b are one decision. Four places said
six: ADR 0012, `importers/CLAUDE.md`, and two comments in `importer.py` itself. All four now say
seven **and say why they are numbered to six**, so the count and the numbering can no longer be read
as disagreeing. Comments only — `pytest` re-run, 64 green.

**3. Session 14's flagged inconsistency is still open, unchanged.** The Breakdown tab's standing
*"read here as refines"* banner was removed at the user's request, but root `CLAUDE.md` R5 (*"a
display convention of that one tab, stated visibly in it"*) and `docs/requirement-breakdown-tree.md`
§2 (*"Say so once, visibly, in the tab…"*) both still describe it as present. Confirmed by reading
both files today. Still a product call: amend the documents, or give the wording a new home.

### Verified

Re-run today against the merged tree, not copied from the commit messages.

| | Status |
|---|---|
| `mvn verify` | **green — 94 backend tests** (was 90; +4, all in `DoorsChecksTest`) |
| `mvn -Pdocker test` | **NOT RUN — the Docker daemon is not running on this machine.** See the warning below |
| `npm run lint` | **green** |
| `npm test` | **green — 199 specs in 16 files** (was 177; +9 from the expandable card, the rest from the deleted-in-DOORS views) |
| `npm run build` | **green** |
| The importer suite | **green — 64 tests**, including the 15 in `test_reconcile_cypher.py`. Run it with `importers/.venv/Scripts/python.exe -m pytest`; bare `python` fails to import `sec_import` |
| The graph | **untouched this session.** Nothing was written to it, no importer was run, and no browser was opened |

**⚠ The container suite has not been run against any of this work.** The newest reports in
`backend/target/surefire-reports` for the six `*FeatureTest` classes are timestamped **2026-08-09
02:36**, which is before all three commits (14:12, 14:12 and 20:12 the same day). They still say 82,
which is session 14's number. `ReviewFeatureTest` **was modified** by the deleted-in-DOORS commit, so
that number is stale in the one place it matters most — the reconciliation makes its decisions in
Cypher, and Cypher is what container tests are for. **Start Docker and run `mvn -Pdocker test` before
trusting anything about this feature.** A trap while you are there: reading a total out of
`surefire-reports` sums fresh and stale reports indiscriminately — 176 looks like a real number and
is two runs added together.

What the deleted-in-DOORS commit *does* record as verified, end to end against Neo4j 2026.06
Community with the two reference exports: deleting `SRD-131` from the SRD export leaves
`SEG-REQ-1264` and `SEG-REQ-1096` showing a link to a deleted object, a second identical import
changes nothing, and re-importing the complete export puts `SRD-131` back in the tree. That was not
reproduced today.

### Still open, carried forward

Everything under **⚠ Resume here** in the session 9 section below is unchanged and none of it was
touched: the **TBD / TBC `Object Type` widening** (79 hits, still the user's call), the **review
settings dialog that lost data once and was never explained** — still suspect, still *do not verify
dialogs against live modules* — and the unbuilt `/api/v1/cypher/run`, `/checks/attribute-policy` and
`/config/navigation` endpoints. From session 14: the dependency graph's **step 8** (list tab,
keyboard navigation) and **§9 question 1** (draw `__child` as faint containment edges, off by
default) are both still undecided and unbuilt.

---

## State as of 2026-08-09 (session 14) — the dependency graph, and CLAUDE.md split in three

Two commits, merged as **PR #1** from `feature/requirement-dependency-graph`. **`git status` is
clean.** Every "still uncommitted" line further down this file is now stale — sessions 9 through 13
all went in, and nothing is carried.

| # | Change | Where |
|---|---|---|
| 1 | **The requirement dependency graph** — a node-and-edge view of `refersTo` opened from the Breakdown tab, laid out top-to-bottom so that **y encodes system level** | `features/requirements/graph/`, `DependencyGraphProjection.kt` |
| 2 | **One card, two views.** `RequirementCardDto` + `sec-requirement-card` render as a Breakdown row *or* as a graph node | `shared/requirement-card/`, `RequirementCardProjection.kt` |
| 3 | **`GET /api/v1/items/{ref}/graph`** — scoped, capped, collects unresolved modules | `ReviewRoutes.kt`, `DependencyGraphCypher.kt`, `GraphScope.kt` |
| 4 | **elkjs 0.11.0** added, pinned exact, worker-only | `layout/elk.worker.ts`, §4 of `CLAUDE.md`, ADR 0011 |
| 5 | **CLAUDE.md split**: §5 → `backend/`, §6/§8/§9 → `frontend/`, §10 → `importers/`. Root 83,532 → 39,974 chars (~20.9k → ~10.0k tokens) | four `CLAUDE.md` files |

`docs/REQ_BREAKDOWN_GRAPH_VIEW` is the spec; **steps 1–7 of its build order are done, step 8 is
not** (list tab, keyboard navigation).

### The card is the point, and it constrains both views from now on

§5.1 asks for the same component in both places, so one projection builds the DTO for the Breakdown
tab and the graph alike. Padding and clamping differ; **the field set cannot**. A column added to
one is added to both or to neither.

What stayed behind in `breakdown-row` is only the tree's own chrome: the depth rail, the twisty, the
parent it refines, the loop markers.

### Three places the spec was overruled — all in ADR 0011, read it before changing any of them

- **Bands come from the module's existing L0–L4 `:__Classification`**, not from §4.1's regex over
  `moduleFullPath`. That regex was a guess from one example path, written before this product had a
  per-module system level. It has one, a human sets it in the Modules dialog, and it already draws
  the badge on every row — so the regex would have produced a **second, differently-numbered** level
  on the same screen as the first: an `L2` badge inside a band reading *Level 1*. `OUTLINE_LEVEL` and
  `GRAPH_RANK` sit beside it in the overflow menu. An unclassified module gets its own band at the
  bottom, never folded into a real level.
- **Direction is `OUTGOING` / `INCOMING`**, shown as *What these refine* / *What refines these*. The
  spec calls the outgoing direction `DOWNSTREAM`, which is the opposite of what this product means —
  an outgoing `refersTo` reads as *refines*, so following it goes **up** the decomposition. Two words
  for one arrow pointing opposite ways is how a reviewer reads a traceability picture backwards.
- **`GraphNodeDto` drops the spec's `moduleUrl`** (an internal name whose value is an internal id —
  R5), **`itemId` and `isPlaceholder`** (the card already carries `ref` and `resolved`; stating
  either twice is how the two come to disagree).

### The trap that cost an hour: ELK owns the worker, we do not

Our own worker importing `elk.bundled.js` looks right and **cannot work**. The in-thread path
constructs `require('./elk-worker.min.js').Worker`, and in a worker context that module has already
taken its self-install branch — so there is no `Worker` on it. `TypeError: _Worker is not a
constructor`, thrown *inside* the worker, surfacing as **a layout that never completes**.

So: the pure functions run on the main thread, where they cost microseconds, and `elk-api`'s
`workerFactory` points at a worker that is one import of `elk-worker.min.js`.

### Everything decision-shaped is a pure function, deliberately

`partitionOf`'s dense renumbering, `compressBands`' sub-lane compression and bend-point remapping,
edge dedup, self-loop retention, feedback detection, the local router — all in `layout/`, unit-tested
with no ELK types in sight. **ELK supplies coordinates; it does not supply meaning.** If it is ever
replaced, the pure half and its tests survive. That is the containment for the accepted risk of a
1.4 MB compiled Java-to-JavaScript bundle (ADR 0011).

`compressBands` **departs from §4.3's sketch**, which places sub-lane *k* at `bandTop + k *
SUBLANE_GAP` and would stack every card on the one above it. Each sub-lane sits below the previous
one's *bottom* instead.

Two more that are rules, not preferences: **edges are handed to ELK reversed and the arrowhead is
never reversed to match** (bands run top-down from L0, so a "refines" arrow runs against the layer
flow; feeding ELK the data direction makes it report almost every edge as feedback and the whole
picture goes dashed), and **the §1.1 incompleteness caveat is unconditional** — "no incoming arrows"
must never be read as "nothing depends on this" when only outgoing links are imported.

### ⚠ One documentation inconsistency was created knowingly — close it

The Breakdown tab's standing *"read here as refines"* banner was **removed at the user's request**.
The per-row wording is unchanged. But two documents still say that convention is stated visibly in
the tab:

- `CLAUDE.md` R5, the `refersTo` **in the Breakdown tab only** alias row — *"a display convention of
  that one tab, stated visibly in it"*;
- `docs/requirement-breakdown-tree.md` §2 — *"Say so once, visibly, in the tab (a small info
  affordance next to the tab label…)"*.

**Both need amending, or the wording needs a new home.** Left open because deciding which is a
product call, not an edit.

### Still open from the spec

- **§9 question 1 was never answered:** should `__child` also be drawn, as faint containment edges
  behind the `refersTo` ones? The spec asks for it as a toggle, **off by default**. Not implemented,
  not decided.
- **Step 8** — list tab and keyboard navigation.
- Nit: the spec file's own title reads `# REquirement Brea Graph View`, and it was committed that
  way. The file also has no extension, unlike every other doc.

### Why the CLAUDE.md split, and what did *not* move

The root file was 83,532 characters — about 20.9k tokens on every session, more than twice the
~40,000 at which Claude Code warns a single memory file is too large, paid for whether or not the
session went near the code it described.

**This is a move, not a cull.** Almost none of it was dead weight: the directory trees are annotated
line by line with facts `ls` cannot show, and the version table's payload is its rationale column,
not its numbers. Fifteen genuinely derivable lines went from §3's tree.

**Section numbers are unchanged on purpose** — code comments and `docs/` say "CLAUDE.md §6" and those
references still resolve. Staying in the root: §1, §2 (R1–R7 and the state-location table), §3, §4,
§7, §11. **Every hard prohibition and every cross-cutting rule is still always-loaded; nothing that
says "never do X" moved.**

The honest caveat: a session touching backend *and* frontend loads both files and saves nothing. The
win is on single-stack work, which is most of it.

### Verified

| | Status |
|---|---|
| `mvn verify` | **90 backend tests** |
| `mvn -Pdocker test` | **82 container tests** (+14; `DependencyGraphFeatureTest` is new) |
| `npm run lint` / `npm test` / `npm run build` | **green — 177 frontend specs** |
| The graph, in the browser | **against the live Segment module** — `SEG-REQ-1249` + 2 hops draws **12 objects** across L1, L2 and a *No system level set* band, with the two dangling links as ghost cards and the banner naming what to import |
| The CLAUDE.md split | **checked section by section against a pre-edit copy** — the five moved sections byte-identical in their destinations, the six kept ones untouched but for §3's trim |
| The graph | **read-only.** This feature writes nothing; ADR 0011 notes it needed no new `:__Meta` kind, so there is no new write path and nothing for `MATCH (m:__Meta) DETACH DELETE m` to have missed |

---

## State as of 2026-08-08 (session 13) — graph names, the two settings dialogs, the Modules table

Four requests. All verified in the browser against the live SRD and Segment modules, and against
the API for the read paths. **Nothing was written to the graph** — both settings dialogs were opened
read-only and closed with Cancel, and Save stayed disabled throughout.

| # | Change | Where |
|---|---|---|
| 1 | **Every graph name is now interpolated into the Cypher**, not just the DOORS attribute names. 234 literals gone from eight statement files | `graph/cypher/*`, `meta/MetaSchema.kt`, ADR 0010 amendment |
| 2.1 | **`__version` reads *Version*, not *Baseline*** — one edit, in `Aliases.kt` | `domain/Aliases.kt` |
| 2.2 | **The Modules dialog's *Object attributes* tab is the review dialog's list**, extracted to a shared component: search box, count, bulk All/None — minus *Shown in table* | `shared/attribute-settings/` |
| 3 | **The Modules table opens sorted by system level**, L0 first, unset last in both directions | `modules.ts` |
| 4 | **Two new columns: Word export title and number** (`wordDocTitle` / `wordDocNumber`) | `ModuleCypher`, `ModuleRowDto`, `modules.ts` |

### 1 is a reversal of ADR 0010, on purpose

The first pass interpolated only `DoorsAttr` and left `__id` and `:DOORSRequirement` spelled out,
because renaming those is gated on a Python change and a re-import anyway. That priced the rename
and ignored the price of finding it — 58 occurrences of `__id` alone.

**What made it readable is single-name imports**, which the original write-up never considered:
`$MODULE_URL`, not `${Prop.MODULE_URL}`. The rejected-alternatives section of ADR 0010 rejected the
qualified form, and it was right to. The amendment at the foot of that ADR records both.

**`GraphNamesTest` changed direction and this is the part to keep.** It now also reads the statement
*source* and fails on any graph name written as a literal, comments stripped first. The forward
check cannot see a hand-written `__id` — it compiles to the same string — so without the inverse
check the interpolation would erode one statement at a time. It caught a real one immediately:
`__Meta` in a `MetaSchema` log message.

One bug worth remembering, found while writing that test: **`Path` implements `Iterable<Path>`**, so
`listOfPaths + aPath` picks the `Iterable` overload and appends `src`, `main`, `kotlin`, … instead
of the file. It surfaced as `AccessDeniedException: src`, which names a directory and explains
nothing. `+ listOf(path)`.

### 2.2 changed what the Modules dialog's Save does, and that is a real trade

It used to post a mandatory-only **diff**, so an untouched policy kept its original `__updatedAt`.
It now posts the **absolute** `attributeSettings` list, like the review dialog — so a save rewrites
`__updatedAt` on every currently-mandatory attribute. That property is genuinely lost.

It was the cheaper thing to lose: two write shapes for one stored rule, edited through one shared
component, is how two dialogs come to mean different things by Save. If the audit timestamps ever
matter, the fix is server-side — skip a write whose values are unchanged — not a second payload.

**The hazard this created, and the spec that guards it:** the Modules dialog cannot show
*Shown in table*, but it posts every attribute. If it sent `visible: false` for the flag it does not
render, opening Module settings to change a system level would silently clear the review table's
columns. It carries the loaded value back untouched, and
`module-settings-dialog.spec.ts` asserts the exact posted body.

That file is also **the first spec the Modules settings dialog has ever had** — worth knowing given
the unexplained data loss recorded further down, which involved the other settings dialog.

### What the browser found, and what it changed

The 88vh dialog with the flex-filling `mat-tab-group` works, both tabs. The flex row layout lines
up at two columns and at three. Both new Modules columns carry real values.

**One thing only the browser could have shown.** With `Module` as an explicit sort tie-break,
ag-grid draws its multi-sort *position badges* in the headers — the table read `MODULE 2 ↑` and
`SYSTEM LEVEL 1 ↑`, and "Module 2" looks like the column's name. The tie-break is gone: the server
already returns modules ordered by `__name` and `Array.prototype.sort` is stable, so within a level
the order is alphabetical without asking for it. `modules.spec.ts` now carries two same-level
modules so that dependency is pinned rather than assumed.

The API side was checked separately, without a browser, because it is the real test of the Cypher
rewrite — a mis-spelled interpolated name returns **zero rows silently** rather than failing. Every
read endpoint answers, and the statistics numbers are unchanged from session 12: 903 items, 516
requirements, 147 orphans, 461 links. The **write** statements were deliberately not exercised
against the live graph; the 68 container tests cover those.

### Deliberately not done

- `MandatoryAttributesDiffDto` and the `mandatoryAttributes` field are still on the endpoint and no
  client sends them any more. Left in place rather than removed in the same change — the API shape
  is documented in `CLAUDE.md` §5 and deleting it is its own decision.
- Query **parameter** names (`row.attributeName`, `${'$'}moduleUrl`) are still literals on both
  sides. They are not graph names; they are a contract between one statement and its one call site.
  Worth a pass one day, not this one.

---

## State as of 2026-08-08 (session 12) — Req review table, six UI changes

All six verified **in the browser against the live Segment module**, not only by spec. Nothing was
written to the graph: the comment buffer was emptied before leaving, and the settings dialog was not
opened (see the warning further down — that dialog is still suspect).

| # | Change | Where |
|---|---|---|
| 1 | **Issues and Comment are no longer pinned right.** They keep their place as the last two columns. Two pins took 470px out of the scrollable area and squeezed Description between two fixed blocks. **ID stays pinned left** — row identity may still never leave the screen | `requirement-review.ts` |
| 2 | **The detail panel is resizable**, 280–900px, pointer-drag or arrow keys on a `role="separator"`. Component state, not persisted — see below | `requirement-review.{ts,html,scss}` |
| 2.1 | **An attribute with no value reads *Empty*** instead of a blank line | `item-detail-panel.*` |
| 3 | **The comment box grows to its text and the row grows with it** | `comment-cell.*`, `_grid.scss` |
| 4 | **Column headers wrap** — `wrapHeaderText` + `autoHeaderHeight` in the shared `defaultColDef`, so every table gets it | `core/grid/sec-grid.ts` |
| 5 | **"Requirements without parents" filter** — requirement-like, no outgoing `refersTo`. 147 of Segment's 903 | `requirement-review.{ts,html}` |
| 6 | **The type scale stepped down one notch** (body 14 → 13px) | `styles/_tokens.scss` |

### The three that are more than they look

**2.1 was one line in the end, after a wrong turn worth recording.** The complaint was that empty
attribute values "were not displayed". They were — `""` means "exists and is empty" and the row was
always in the list — but the value rendered as an empty `<dd>`, a label with nothing beside it,
which reads as the panel having failed. Naming it *Empty* is the whole fix.

I first read it as "list every attribute the module has, filled or not", built that
(`ItemDetailDto.moduleAttributes`, fed by `discoverAttributeNames`), and **reverted it**: the
discovery query scans every object of the module, and measured against the running service it took
the endpoint from **8ms to 26ms on every panel open** — for attributes the object does not have.
`REQ_REVIEW.md` §7 and §8 now say so, so nobody adds it back.

**"Empty" is upright grey, not italic**, which is not what was asked for. "Never italic" is an
explicit Airbus rule with no exception for placeholders (§8), `styles.scss` enforces it globally
with `* { font-style: normal }`, and the `absent-text` mixin already made exactly this substitution
for exactly this reason. One line in `item-detail-panel.scss` if that call is ever reversed.

**3 is the one with a trap in it.** The comment column used to opt out of `autoHeight` for a real
reason, and the reason still holds: under `autoHeight` ag-grid nests cell content in wrappers sized
to that content, and a textarea's intrinsic width is its `cols` — 20 characters. The fix is the pair
`DOORS_TABLES.md` §6.6 already paid for on the table cell: `display: block` on the cell,
`inline-size: 100%` on the renderer's host. The editor also had to come **in flow** — it was
`position: absolute; inset: 0`, which contributes no height, so `autoHeight` would have collapsed
the row to nothing. Both are commented at the site.

### Deliberately not done

- **The panel width is not persisted.** It outlives opening and closing the panel and dies with the
  view. Persisting means browser storage, which CLAUDE.md §2 *does* sanction for this kind of
  preference — but no view writes there yet, and starting is a decision of its own.
### Verified

`mvn verify` 88 ✓ · `mvn -Pdocker test` 67 ✓ (+1) · `npm run lint` ✓ · `npm test` 119 ✓ (+3) ·
`npm run build` ✓.

---

## State as of 2026-08-08 (end of session 11) — backend refactor, items 1, 2 and 6

`docs/REFACTOR_BACKEND.md` items **1, 2 and 6 are done**.
Items 3, 7, 8, 9 and 10 are still open; 4, 5a and 5b stay decided-but-unimplemented.

**Read `docs/adr/0010-graph-names-as-constants.md` before touching any name in the backend.**
`CLAUDE.md` §5 now carries the rule as a non-negotiable.

### What changed

Three new files, and every call site rewired to them:

```
backend/src/main/kotlin/com/sec/
  domain/GraphNames.kt        ← Prop, Rel, NodeLabel, MetaKind, MetaProp, MetaValue
  source/doors/DoorsNames.kt  ← DoorsAttr, DoorsModuleAttr, DoorsProp, DoorsRel, DoorsLabel
  api/ApiPaths.kt             ← /api and /api/v1
  config/ConfigArgs.kt        ← makes -config= an overlay on the packaged application.yaml
backend/src/test/kotlin/com/sec/
  domain/GraphNamesTest.kt    ← 5 tests, the naming guard
  config/ConfigArgsTest.kt    ← 7 tests, pins Ktor's merge semantics as well as our transform
```

Three duplicate declarations are gone: `__UNDEFINED` existed twice (`DoorsChecks.UNRESOLVED_LABEL`
and a private one in `BreakdownProjection`), the `DOORSTable*` labels twice (`TableGeometry` and
`DoorsChecks.structuralTypes`), and the `['id','objectNumber','objectLevel']` exclusion list three
times — with a comment claiming they were "kept identical on purpose", which is what you write when
nothing enforces it. They are now the same object.

### The judgement call, and the reason it is safe

**Cypher interpolates the DOORS *attribute* names and nothing else.** Labels and `__` names stay
spelled out. The line is who can rename the thing: a DOORS administrator can rename `Object Text`
with no importer change at all, while `__id` is gated on a Python change and a full re-import.

What buys back the difference is **`GraphNamesTest`** — it reads every statement in `graph/cypher/`
plus `MetaSchema.statements` and fails on any name the constants do not declare. Verified by
breaking it deliberately: `:SEItem` → `:SEItm` and `__id` → `__idd` produced two named failures. If
you add a Cypher file, add its statements to that test; a completeness check fails if you forget.

### Item 6: `-config=` works, and now *merges*

No `-c` flag: Ktor 3.5.1's `EngineMain` already takes `-config=<path>`. Everything here was
verified by running the shaded jar, not read out of documentation.

Stock `-config=` **replaces** the packaged `application.yaml`, so a file without a `ktor:` block
died with *"Neither port nor sslPort specified"* — which would have forced every operator's file to
carry `com.sec.ApplicationKt.module`. Repeated `-config=` flags, though, **merge deep with the last
one winning**, and `-config=application.yaml` resolves the packaged file *from the classpath* (a
file of that name in the working directory does **not** shadow it — tested).

`config/ConfigArgs.kt` inserts that first path. Six lines, pure function on the argument array. A
deployment file now states only what its environment changes:

```
java -jar backend-0.1.0-all.jar -config=/etc/sec/sec.yaml     # no ktor: block needed
```

`-P:neo4j.uri=…` also works, as a per-key override for containers.

**The trap if you touch this:** the packaged `application.yaml` resolves `$SEC_NEO4J_USER` eagerly
and **fails to load at all** when it is unset — deliberately. That is why surefire now supplies
placeholder `SEC_NEO4J_*` values: `ConfigArgsTest` asserts against the real packaged file, not a
copy, and could not load it otherwise.

### Verified

| | Status |
|---|---|
| `mvn verify` | **green — 88 tests** (was 76; +5 `GraphNamesTest`, +7 `ConfigArgsTest`) |
| `mvn -Pdocker test` | **green — 66 container tests**, which is what actually proves the interpolated Cypher still runs |
| The guard test | **proved to fail**, not assumed to — see above |
| `-config=` | **run** against the jar: replace-not-merge proved first, then the merge, then a single-flag overlay with no `ktor:` block. No stray JVM left running — checked |
| The graph | **untouched.** Nothing was written to it and no importer ran |

The stale-`KotlinCompileDaemon` trap hit again on the first compile. Killing them fixed it
immediately, exactly as the recipe below says.

**Nothing was committed.** Sessions 9, 10 and 11 are all still uncommitted.

---

## State as of 2026-08-08 (end of session 10) — DOORS tables

**Requirements → Req review now draws a module's embedded DOORS tables**, in the Description column
of each `DOORSTable` row, which is where DOORS draws them: inside the main text column, at that
column's full width, with the display columns continuing on either side.

Verified against the live SRD module, and these are the acceptance numbers from `DOORS_TABLES.md`
§8: **6 tables, 399 cells, 0 dropped, 0 anomalies.**

Read these three before touching it:

- `docs/DOORS_TABLES.md` §11 — what was built, and the six places it departs from the rest of that
  document. §11 is new; §1–10 is the original spec and is **not** all implemented.
- `docs/adr/0009-doors-tables-in-the-flat-review-table.md` — why the departures.
- `docs/REQ_REVIEW.md` §5 — the four bullets about tables in the review table.

### A table is deliberately plain

It shows its cells' `Object Text` **and nothing else**. No object ids on screen, no other attribute
carried out beside it, no weight on the first row, and blank **ID and Type** columns on its row — as
in DOORS. That is the shape the user asked for after seeing the first version, and it is worth
knowing that three of those were built and then deliberately removed rather than never written:

- **§6.3's outer attribute columns** were built twice — stacked in the outer cell, then as trailing
  columns of the table — and then dropped end to end. There is no `outerColumnValues` on the wire,
  no `MULTIPLE_OUTER_COLUMN_VALUES` anomaly, and no `attrs` parameter on either endpoint. If a
  module ever turns up where those values are content, the spec and the deleted implementation are
  one commit back.
- **The bold header row** and the **"Table object IDs" checkbox** are gone for the same reason: a
  table is a figure, and a figure that argues with the document around it is worse than a plain one.
  The first row keeps its `columnheader` role for a screen reader, and each cell keeps its DOORS id
  on `title` — invisible until hovered, and the only way to tell which object a cell is when an
  import goes wrong.

### The one thing that will bite whoever touches this next

**`autoHeight` measures an ag-grid cell once, when it is created.** The tables request answers after
the rows do, so the cell is measured while its content is nothing and the row stays 46px with a
41-row table spilling over every requirement beneath it. `resetRowHeights()` is *not* the fix —
ag-grid rejects it for an auto-height column, in as many words, but only when `ValidationModule` is
registered (`AllCommunityModule` alone prints the bare number `AG Grid: warning #3`). Register it
temporarily to read any such message.

`TableCell` measures its own content and states it: a `ResizeObserver` calling `node.setRowHeight()`
then `api.onRowHeightChanged()`, doing nothing when the height is unchanged and deferring to the
next frame. It also handles the §6.6 case — dragging the Description column narrower re-wraps every
cell and the row has to follow. Both this and "a cell that must fill its width cannot be a flex box"
are now in CLAUDE.md §6.

**Every bug in this session was found in the browser and only in the browser.** All of them look
correct in the stylesheet and pass every spec — jsdom has no layout. Two more, both since removed
with the feature that caused them: one attribute cell measured 9 000 pixels tall (247 values, one
distinct), and a table reported 41 findings that were all wrong (the anomaly counted sources rather
than distinct values).

### What is where

```
backend/src/main/kotlin/com/sec/
  source/doors/TableGeometry.kt        ← every rule in §3. Pure: no driver, no Ktor, 27 unit tests
  source/doors/DoorsTableProjection.kt ← the I/O half: one round trip, folds triples to a hierarchy
  graph/cypher/TableCypher.kt          ← MODULE_TABLES, RESOLVE_TABLE
  api/dto/TableDtos.kt, api/routes/TableRoutes.kt
backend/src/test/kotlin/com/sec/TablesFeatureTest.kt   ← 13 tests, @Tag("docker")
frontend/src/app/shared/doors-table/   ← the standalone component, dumb, fed by the DTO
frontend/src/app/features/requirements/review/cells/table-cell.ts   ← the ag-grid renderer
```

`GET /api/v1/modules/{ref}/tables` and `GET /api/v1/items/{ref}/table`, neither taking parameters.

To see it: `scripts\win\sec-up.ps1`, then Req review → SRD. **Restart the backend after any
backend change** — the tables endpoints are new and a running backend serves the code it started
with.

### Housekeeping from this session

- The backend was restarted three times. Neo4j and the dev server were left alone, and **the graph
  was only ever read** by this work.
- Mid-session, SRD's visible attributes changed from `Compliance` + `Object Short Text` to
  `Compliance` + `Verification Requirement` — written at `12:53:44Z` through the application's own
  settings path (`__updatedBy: system`). Not this work; every call it made was a GET. Worth knowing
  because the earlier screenshots show a column that is no longer there.
- A pre-existing ag-grid deprecation is still open and unrelated: `sortingOrder` should move to
  `defaultColDef.sortingOrder` in `core/grid/sec-grid.ts` (`AG Grid: warning #306`).
- `GET /api/v1/config/navigation` still 404s and logs an error on every page load. Pre-existing,
  listed as "still to come" in `api/Routes.kt`.

### Next

`docs/REFACTOR_BACKEND.md` — **new this session, written by the user, ten items, not started.**
Backend-wide: a single source of truth for property names, a place for future Keycloak / Windchill /
CAMEO REST clients, dependency injection (they name Kodein and ask for an opinion), a JSON config
file at the repo root passed with `-c`, and Maven-built standalone jars for backend *and* frontend
with RHEL/Docker in view. Several of those touch things CLAUDE.md fixes — §4's dependency table,
§5's structure, and `application.yaml` as the config mechanism — so it needs answering before it
needs coding.

**Everything from session 9 below is unchanged and still uncommitted.**

---

## State as of 2026-08-08 (end of session 9)

Branch `master` (**not** the repo's main branch).

Session 8's large uncommitted tree is **gone** — you committed it mid-session as `8421a6e` and
`2ca7c27` ("Latest changes", both 2026-08-07 23:06). The staged importer work that had been
carried for three sessions went in with it. `git status` is now small and readable again.

**Seven files and one new directory are uncommitted**, all from the second half of this session:

```
 M backend/src/main/kotlin/com/sec/source/doors/DoorsChecks.kt        ← the Object Type exemption
 M backend/src/main/kotlin/com/sec/source/doors/StatisticsProjection.kt
 M backend/src/test/kotlin/com/sec/StatisticsFeatureTest.kt
 M docs/features/requirements-statistics.md                           ← §3.3 records the exemption
 M frontend/src/app/shared/charts/bar-chart.scss                      ← the scroll fix
 M frontend/src/app/shared/charts/stacked-bar-chart.scss              ← the scroll fix
 M frontend/src/styles/_mixins.scss                                   ← one paragraph on the mixin
?? backend/src/test/kotlin/com/sec/source/                            ← DoorsChecksTest.kt
```

Everything else this session describes is already committed.

---

## What this session was

**Bug-fixing on the Statistics view, driven from the browser.** No new features, no schema
change, no graph write. The graph was left exactly as found.

Every fix below was found or confirmed by measuring the running page in Chrome rather than by
reading the code — several of them look correct in the source and are wrong on screen, and two
were *caused* by an earlier fix in the same session. If you change layout here, open it.

### 1. Duplicate captions floating over the page — Firefox only

Each chart carries a visually-hidden data table (ADR 0008). The sr-only rules were applied to the
`<table>` itself. A table generates **two** boxes: the anonymous *wrapper* box, which takes
`position` and `margin`, and the *grid* box, which takes `width`, `height`, `overflow` and
`clip-path` — and `<caption>` belongs to the **wrapper**. So the clip removed the rows and left
the caption behind at full size.

Verified rather than assumed: a probe reproducing the old shape in Chromium shows the caption laid
out at 138×22 far outside the 1px box **in Chromium too**. That half is spec behaviour everywhere.
What differs is painting — Chromium clips it, Firefox does not. That is the whole of the
"Firefox only" part.

Fixed by moving the rules to a wrapping `<div>`, via a new `visually-hidden` mixin in
`_mixins.scss` that documents both traps.

### 2. Census tiles were different sizes — and the obvious fix was not the fix

`.sec-census` was a wrapping flex row, so each tile sized to its own text and `align-items:
stretch` equalised only within one line. Changed to
`grid-template-columns: repeat(auto-fit, minmax(150px, 1fr))` + `grid-auto-rows: 1fr`.

**That was not enough, and measuring is what caught it.** Every grid cell then measured exactly
242×100 while the tiles still rendered ragged, because the children of `.sec-census` are
`<sec-kpi-tile>` *host* elements and **an Angular component host is `display: inline` until told
otherwise**. The cell was right; the button inside it was sized by its own text. `kpi-tile.scss`
now sets `:host { display: flex }` and `.sec-kpi { flex: 1 }`, plus `margin-top: auto` on the hint
so the tiles that have one line up with the ones that do not.

Generalise this: **`getBoundingClientRect()` on `container.children` in an Angular app measures
hosts, not the elements you styled.**

### 3. Chart legends over the axis labels

`grid.containLabel` measures axis **tick labels** and nothing else, while a legend and an axis
name are positioned against the *container*. Nothing in echarts reserves that strip, so
`legend: { bottom: 0 }` against `grid: { bottom: 8 }` drew the legend straight over the value
axis's numbers on both stacked charts.

One `AXIS_STRIP = 30` constant in `chart-options.ts` is now the reservation, used as `grid.bottom`
on both builders and as the bar chart's `nameGap`, so the space reserved and the distance the text
is placed at cannot drift apart. Specs assert the constant, not a literal. The legend is also
`type: 'scroll'` — it was free to wrap, and a second line grows straight back into a fixed strip.

The bar charts' axis name was a second instance of the same thing: `nameLocation: 'end'` put it
level with the axis line past the last tick, in that same unmeasured strip, and `grid.right: 44`
was not enough for it, so "Violations" was being clipped at the right edge. It is a centred axis
title now.

### 4. Two scrollbars — caused by fix 1

The view was already scrolling correctly in its own bounded panel, but `mat-sidenav-content` had
`scrollHeight` 1139 against `clientHeight` 889.

The sr-only wrapper from fix 1 is `position: absolute` with **no positioned ancestor**, so its
containing block was the initial containing block and its static position resolved against the
*page*. That put a 1×1 box at y≈1194 — three hundred pixels below the shell — and the shell grew a
scrollbar to reach a box nobody can see. Found by scanning for elements whose rect extended past
the shell's bottom; it was at depth 8.

`position: relative` on `.sec-chart` makes the figure the containing block. Injecting that rule
alone took `scrollHeight` from 1139 to 833, exactly `clientHeight`. `/requirements/modules` and
`/requirements/review` were checked and have no second scroller.

**An out-of-flow box still has a position, and "invisible" is not "absent from layout."**

### 5. "A module that has not been imported", three times

Not a rendering bug: three genuinely different DOORS modules (`M-0009630e`, `M-0009630f`,
`M-00096314`), none imported, so `DANGLING_TARGET_MODULES`' `OPTIONAL MATCH` yields null for each
and the template printed the same fallback per row. There is nothing else to name them with — the
importer's placeholder stores the linked *object*'s name, not its module's, and the only
module-level identifier is the `doors://` URL, which R5 keeps off screen.

`traceability-band.ts` now splits `namedTargets` from `unnamedTargetCount`; named ones stay
bullets, the rest collapse to one line. The count is the useful part — it is how many imports
would clear those 373 links.

### 6. TBD / TBC was counting DOORS's own table scaffolding — 552 → 79

**The headline finding of the session.** The open-point scan reads every non-`__` string
attribute, `Object Type` included. DOORS does not type the parts of an embedded table, so every
cell, row and table arrives with `Object Type` reading the literal string `TBD`. In SRD that was
327 cells + 92 rows + 6 tables = 425 — and **425 was the whole metric**. Not one hit in any other
attribute, none on a requirement or heading.

It was also an inconsistency, not just noise: `DoorsChecks.tbdCheckExclusions` already excuses
table structure from the fixed "Object Type shall not be TBD" check, so Req review reported
nothing on those 425 objects while Statistics counted every one. Two views disagreeing about one
module is what `DoorsChecks` exists to prevent.

`DoorsChecks.openPointAttributes(labels, props)` now wraps the source-agnostic scan and drops
`Object Type` on table structure only. The DOORS-specific rule sits in the DOORS package;
`domain/TextMarkers.kt` stays generic. Census TBD / TBC went 552 → 79; SRD is now 0.

---

## ⚠ Resume here

### 1. The open question this session ends on — decide this first

All **79** surviving TBD / TBC hits are still `Object Type`, all in Segment, all on objects
labelled `DOORSTBD` with no `DOORSRequirement` and no table label: untyped requirements whose
`Object Type` is literally `TBD`.

The rule as scoped keeps them, and there is a fair argument that it should — a real requirement
DOORS never typed is a genuine open point. **But** those same 79 are already the Req review Issues
column's fixed check, and `requirements-statistics.md` §3.3 says in as many words: *"The
`DOORSTBD` label is not reported here. It remains the fixed check in the Req review Issues
column."* Counting them here reports one fact twice, through the value instead of the label —
which is the same shape of bug as §6 above, one level up.

Excluding `Object Type` from the scan outright takes the count to **0** and leaves the chart
measuring open points in prose, which is what it is named for. It is a one-line change to
`openPointAttributes`. **It was deliberately not made** — the exemption was scoped to table items
and widening it is the user's call.

### 2. The review settings dialog lost data once, and it is still unexplained

**Unchanged from sessions 6, 7 and 8. Nothing this session touched it.**

Opening the Req review attribute dialog, un-ticking one *Mandatory* checkbox and saving deleted
**all 9 mandatory policies and 8 of the 10 visible flags** on Segment. Restored by hand, verified,
never reproduced. A container test posts the dialog's exact shape and keeps the others; the real
payload was intercepted in the browser and was correct.

**A confirmed data loss with no identified cause. Treat the dialog as suspect.** The lead worth
pulling is whether the dialog can seed its Signal Forms model from a *stale* `moduleAttributes`
resource — created per dialog open from `ModulesApiService`, and a mandatory list arriving empty
would produce exactly this payload while still displaying correctly if the display read a
different source.

**Do not verify dialogs against live modules.** Seed a scratch module first; that mistake is what
caused the loss.

### 3. Not implemented (verified by hitting them this session)

| Endpoint | Status |
|---|---|
| `GET /api/v1/config/navigation` | **404** — still the one standing console error, still expected. The sidenav's hardcoded fallback masks it. |
| `GET /api/v1/modules/{ref}/checks/attribute-policy` | **404** — specified, unbuilt. The review table does not need it; it computes per row inside `/objects`. |
| `POST /api/v1/cypher/run` and `/explain` | **404** — unbuilt. Worth knowing: there is **no way to run ad-hoc Cypher through the API**, so graph inspection this session went through `/modules/{ref}/objects` and PowerShell. |
| `GET /api/v1/config/system-levels` | 200 |
| `GET /api/v1/statistics/requirements/cycles` | 200 |

Also: the Modules **settings dialog** still has the pre-rework shape (mandatory-only tab, no
search); the pattern to copy is `review-settings-dialog.*`, ~2 h. Windchill, SOI views and
Functions are still empty states. **Statistics is no longer one** — it is built and working.

### 4. Older open questions, still unsettled

- **Should mandatory attributes be definable once and applied to every module?** Today per module
  (R2, Shape B). Modules do not share an attribute schema — SRD 78, Segment 53 — so a global rule
  would flag requirements in modules that do not have the attribute. A middle option was offered
  and not taken up: keep per-module policies, add "copy mandatory settings from another module".
- **The system-level colour ramp reuses two semantic hues** (`#009F4D` "verified", `#0077C8`
  Tier-2). `CLAUDE.md` §8 records the exception and its fence.

---

## ⚠ The editor is still corrupting files

**It happened again, in this very file.** Line 3 read `TrLetsansient session-to-session note` —
the word `Lets` injected into `Transient`. Repaired in this rewrite.

Session 8 saw three instances (`sec-backend.ps1` truncated to the single word `For`, `pom.xml`
prefixed with `i`, `maven-settings.xml.example` prefixed with `Ok,`). The pattern is chat text
being typed into the editor window. **A file that suddenly will not parse: check its first line
before suspecting the change you just made.**

---

## Verified / not verified

| | Status |
|---|---|
| `mvn verify` | **green — 49 tests**, including 4 new `DoorsChecksTest` cases |
| `mvn -Pdocker test` | **green — 53 container tests**; `StatisticsFeatureTest` up to 21 with a new case pinning the cell exemption |
| `npm run lint` / `npm test` / `npm run build` | **green — 98 tests**, all three re-run after the last change |
| The Statistics view | **driven end to end in Chrome against live data** — census strip measured tile by tile, both stacked legends checked clear of the axis, the scroll containers enumerated, the dangling-target line read on real data, TBD / TBC recomputed after a backend restart |
| The Firefox caption leak | **mechanism proved in Chromium, not observed in Firefox** — no Firefox available here. The layout half is confirmed by probe; the paint half is inference from the user's report |
| Every number in §6 | **taken from the live graph**, not from fixtures |
| The review **settings dialog** | **still suspect** — see Resume §2 |
| Cycle handling, truncation / the 500-row cap | **tests only** — real data reaches neither |

The graph was left exactly as found. Nothing was written to it.

---

## Environment

- Backend `:8080`, frontend `:4200`, Neo4j native from
  `C:\Users\juanm\neo4j\neo4j-community-2026.06.0`. **Start everything with
  `scripts\win\sec-up.ps1`**; `-Status` says what is up.
- **`sec-up.ps1 -Stop` stops the dev server too**, including one you started separately. Restart
  it after any `-Stop`.
- **Restart the backend after any backend change** — it serves the code it started with. The TBD
  numbers do not move until you do.
- **Credentials are not written down here.** `scripts\win\sec-env.local.ps1` holds them and is
  git-ignored. Unlike session 8, **that file now exists**.
- Maven is not installed; `mvnw.cmd` works.

### New traps, in the order they cost time

1. **`mvn verify` failing with `Failed connecting to the daemon in 4 retries` is not your code.**
   Three stale `KotlinCompileDaemon` processes were running, one under `temurin-22` rather than the
   build's JDK 21. `-Dkotlin.compiler.execution.strategy=in-process` did **not** help. Killing all
   three fixed it immediately and they respawn on demand:
   ```powershell
   Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
     Where-Object { $_.CommandLine -like '*KotlinCompileDaemon*' } |
     ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
   ```
2. **An Angular component host is `display: inline`** until styled. Layout applied to a container
   reaches the hosts, not the elements inside them. See §2 above.
3. **`grid.containLabel` covers tick labels only** — not the legend, not the axis name. Reserve the
   strip yourself.
4. **An `@if` inline in a sentence leaves a space on each side of the block.** HTML collapses the
   pair on render, so it is invisible to a reader and to a screen reader, but `textContent` keeps
   both and a `toContain` assertion fails on prose that looks correct. The spec's `text()` helper
   normalises whitespace for that reason.
5. **`npm` is not on the Bash tool's PATH here** — it exits 127. Use the PowerShell tool for npm.
6. **`ng serve` binds `::1` only**, so an IPv4 probe of 4200 reports it down while it is running.
   `sec-ports.ps1` holds the one dual-stack probe; do not re-introduce a local copy.

### Carried forward, still true

7. **A component inside an `@if` on a resource unmounts while that resource reloads.** Hoist
   anything holding UI state outside every `@if`.
8. **`doc.twisty` draws a triangle from three borders and says nothing about the fourth.** On a
   `<button>` the UA's `border-right` survives and it renders as an hourglass. Zero the fourth side
   *after* the include.
9. **`outline-chip` uppercases** — set `text-transform: none` when the chip carries prose.
10. **The driver's `Node` has a member `id()`.** An extension function of that name is silently
    shadowed. `BreakdownProjection.kt` calls its one `nodeKey()` — do not "tidy" it back.
11. **jsdom has no layout** (`scrollIntoView` does not exist) and **cannot mount a chart** — add
    `provideEchartsTesting()` to any spec mounting a component containing one.
12. **`resource.value()` throws in an error state** — guard every read with `hasValue()`. An
    unguarded read inside a `computed` the template consumes tears down the whole view.
13. **A backgrounded Chrome tab measures the grid wrong** — screenshot to force a paint and trust
    that over the measurement.
14. **Never set `position` on an ag-grid cell**; **ag-grid's stylesheet is injected after ours**,
    so overriding a structural rule needs two of our own classes, never an `.ag-*` name.
15. **In specs:** `whenStable()` never resolves with an `httpResource` in flight — it times the
    spec out rather than failing it. `TestBed.resetTestingModule()` inside a test corrupts the rest
    of the suite. `reload()` schedules a refetch rather than issuing it.
16. **Clicking a `mat-select` option by screenshot coordinates is unreliable** — navigate by the
    `?module=<ref>` query parameter instead.

---

## Decisions

`docs/adr/` — 0002 errors and log format, 0003 the paper visual style, 0004 the frontend quality
gate, 0005 the Req review backend, 0006 ag-grid Community, 0007 Maven over Gradle, 0008 echarts.
Not to be re-litigated without changing the ADR.

`docs/requirement-breakdown-tree.md` §10 is the same kind of record for the Breakdown tab.
`docs/features/requirements-statistics.md` §3.3 now carries this session's one rule change.
