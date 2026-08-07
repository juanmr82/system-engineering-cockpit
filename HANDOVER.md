# Handover

TrLetsansient session-to-session note — not project documentation. Delete once its content is
absorbed into commits or superseded.

## State as of 2026-08-07 (end of session 8)

Branch `master` (**not** the repo's main branch).

> **NOTHING FROM THIS SESSION IS COMMITTED.** Around forty files are changed, added or deleted
> in the working tree. Read this file before `git status` confuses you, and commit before doing
> anything else — a working tree this size is not a safe place to start new work from.

Session 7's work (`6fe5450`, the Breakdown tab) is committed. Session 6's warning about the
review settings dialog (§1 below) is carried forward again, unchanged and still unexplained —
nothing this session touched it.

**There is still uncommitted, staged work in `importers/` that belongs to someone else** — the
DOORS importer refactor plus `docs/DOORS_IMPORTER_INFO`. Left staged and untouched for the third
session running.

---

## What was built

This session was **build and tooling only**. No feature work, no graph writes, no UI changes.
The application behaves exactly as it did, with one addition: the backend can now serve the
built frontend.

### 1. The build moved from Gradle to Maven

Recorded in `docs/adr/0007-maven-over-gradle.md` — read that before questioning any of it. The
short version: Gradle could not be made to work on the locked-down workstation, and it failed
three different ways, all of them "Gradle has to download Gradle first". Maven's mirror, proxy
and credentials all live in `%USERPROFILE%\.m2\settings.xml` — user-scoped, no admin, and not a
committed file, which is the thing Gradle could not offer.

`settings.gradle.kts`, `build.gradle.kts`, `backend/build.gradle.kts`, `gradle/libs.versions.toml`
and the wrapper are **deleted**. `pom.xml` (aggregator, all versions) + `backend/pom.xml` replace
them.

| Was | Is |
|---|---|
| `./gradlew check` | `mvn verify` |
| `./gradlew :backend:integrationTest` | `mvn -Pdocker test` |
| `./gradlew :backend:run` | `mvn -pl backend exec:java` |
| `gradle/libs.versions.toml` | root `pom.xml` `<properties>` + `<dependencyManagement>` |

**The one trap that will bite again:** Gradle resolved version conflicts by taking the *highest*
version, Maven takes the *nearest*. The catalogue said `kotlinx-coroutines` 1.10.1 while Ktor
3.5.1 needs 1.11.0, so Gradle had been silently upgrading it and the build was running 1.11.0 all
along. Under Maven the pin stuck and six tests died with `NoSuchMethodError`. **Any version the
old catalogue named may have been fiction.** A `NoSuchMethodError` or `NoClassDefFoundError`
after this migration is that, until proven otherwise.

Also: Ktor and kotlinx need the **`-jvm` artifact suffix** under Maven. The unsuffixed artifacts
carry Gradle module metadata Maven cannot read and resolve to empty jars — the failure is a
compile error about a missing package, not a resolution error.

### 2. One command runs everything: `scripts\win\sec-up.ps1`

Starts Neo4j, the backend and the dev server, each in its own window, waiting for each before
starting the next. `-Status`, `-Stop`, `-Jar`, `-NoFrontend`, `-NoBrowser`. Everything is checked
before any window opens.

**A latent bug was found and fixed doing this**, and it predates the script: `ng serve` binds to
**`::1` only**, and every port probe in these scripts connected to `127.0.0.1`. A running frontend
therefore read as down — `sec-doctor.ps1` said "Frontend running: Not running" while the site was
open in a browser. `scripts/win/sec-ports.ps1` now holds the one dual-stack probe all three
scripts use. Do not re-introduce a local copy.

`-Stop` finds the window to close by walking the process tree from the listening process, not by
window title: a console started with `Start-Process` reports an empty `MainWindowTitle`, so
title matching silently finds nothing.

### 3. One deployable jar: `scripts\win\sec-package.ps1`

`npm run build`, then `mvn -Pui package`, producing `backend/target/backend-0.1.0-all.jar` — 21 MB,
API **and** user interface, served on :8080. Deployment is that one file plus a JDK 21 and a
reachable Neo4j. `sec-up.ps1 -Jar` runs it.

`backend/.../api/routes/UiRoutes.kt` serves it, and `CLAUDE.md` §5 records the two rules that must
not drift (both covered by `PackagedUiTest`):

- `/api/**` is never answered with a page — an unknown endpoint stays a problem detail.
- A missing *file* is a 404, not `index.html` — otherwise a stale hashed bundle after a redeploy
  hands the browser HTML with status 200 and it reports a syntax error in it.

Ktor's own `staticResources("/", …)` cannot do this job: mounted at the root it installs a
catch-all that answers 404 itself and takes both rules with it. That is why the fallback is
hand-rolled.

---

## ⚠ Something is corrupting files in the editor

**Three times this session**, a few stray characters appeared at the very start of a file that was
open in the IDE, each time breaking it:

| File | Became |
|---|---|
| `scripts/win/sec-backend.ps1` | truncated to the single word `For` — the whole 65-line script gone |
| `pom.xml` | `i<?xml version…` — the build stopped parsing |
| `scripts/win/maven-settings.xml.example` | `Ok,<?xml version…` |

All three are repaired. The pattern looks like chat text being typed into the editor window. It
is worth finding the cause before it lands somewhere subtler than a file that refuses to parse —
`sec-backend.ps1` was only noticed because its content vanished entirely.

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
| `mvn verify` | **green** — **22 tests** (15 carried over + 7 new `PackagedUiTest`) |
| `mvn -Pdocker test` | **green** — **32 container tests**, unchanged by the migration |
| `npm run lint` / `npm test` / `npm run build` | **green as of session 7** — 62 tests. **Not re-run this session**; only `npm run build` was, via `sec-package.ps1`. |
| The packaged jar | **driven end to end** — run against a throwaway Neo4j container, then checked over HTTP: `/` and `/requirements/modules` serve the app, hashed JS and CSS come back with the right content types, `/api/v1/modules` is JSON, and `/favicon.ico` and a stale bundle name are 404 problem details rather than the index page |
| `sec-up.ps1` `-Status`, preflight refusal, already-running detection, Neo4j cold start, `-Stop`'s window discovery | **driven** |
| `sec-up.ps1` cold start of the **backend and frontend windows** | **NOT verified** — both were already running from session 7 and the Neo4j password was not available, so stopping them was a one-way door. The waits themselves are exercised by the Neo4j path. |
| Breakdown against SEG-REQ-1247 | **driven end to end** — two parents, both roots, the placeholder leaf, collapse/expand, the subject marker on every copy |
| A real verification attribute | **driven** — SRD-1158 shows a Verification Requirement value, so that path is exercised with live data, not only in tests |
| Level badge alignment, set vs unset | **measured** in the browser — 24 px empty, 25 px with `L1`/`L2` |
| Cycle handling | **tests only** — the imported modules contain no `refersTo` loop, so the fixture is the only place it has been seen |
| Truncation / the 500-row cap | **tests only** — real data does not reach either bound |
| The review **settings dialog** | **still suspect** — see §1 |

The graph was left exactly as found. Nothing was written to it this session.

Neo4j was started during testing and left running. The throwaway container used to drive the
jar was removed.

---

## Environment

- Backend `:8080`, frontend `:4200`, Neo4j native from
  `C:\Users\juanm\neo4j\neo4j-community-2026.06.0`. **Start everything with
  `scripts\win\sec-up.ps1`**; `-Status` says what is up.
- **Maven is not installed on this machine.** `mvnw.cmd` works and downloads one on first use;
  unzipping a real Maven and setting `$SecMavenHome` avoids that download permanently
  (`docs/RUNNING.md` §1.2). The wrapper is the `only-script` flavour — there is no wrapper jar.
- **Credentials are not written down here.** `scripts\win\sec-env.local.ps1` holds them, is
  git-ignored, and is what `sec-env.ps1` reads — see `docs/RUNNING.md` §2.1. **That file does not
  currently exist**, so `sec-doctor.ps1` reports one failure until it is created from the
  `.example` beside it.
- **Restart the backend after any backend change** — it serves the code it started with.

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
