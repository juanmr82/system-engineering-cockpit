# 0019 — DOORS imports from an uploaded export too, in the backend

Status: accepted
Date: 2026-08-18

## Context

The DOORS importer is Python, driven by a `.bat` wrapper, and it needs a live DOORS client on
Windows 11 (`CLAUDE.md` §1). An external exporter that pushes a module's export straight to this
backend is planned but not yet built. Until it exists, an admin who already has an export file —
handed to them, or produced by the Python importer's own DXL step without its Neo4j half having
been run — has no way to get it into the graph except by running the whole Windows pipeline.

This adds a second, admin-triggered path: upload the export file in the browser, import it from the
backend, same as Windchill (ADR 0015). The Python importer is **not** replaced. It is the only thing
that can talk to a live DOORS client, so it stays the way a module's export is *produced*; this adds
a way to get an already-produced export *in*. The two are expected to coexist for a transition
period, and `CLAUDE.md` §1 already says the Python one may disappear later — this ADR does not
schedule that.

Three things shaped the design beyond ADR 0015's precedent:

- **The uploaded file must not be written to disk, at all, even temporarily.** It is parsed in
  memory and discarded; only its checksum outlives the request.
- **The checksum is not decoration.** It gates a re-import: uploading the same file twice must not
  re-run the whole reconciliation pipeline for nothing, and the planned push importer needs the same
  gate — a service pushing on a schedule will often push nothing new.
- **A re-import is a write to a node that may already carry someone else's access grant, or none at
  all**, and R8's visibility predicate has never before had to answer a question about a *write*.

## Decision

### 1. The importer runs in the backend, fed by an upload — same shape as Windchill

`POST /api/v1/doors/import` takes the file's text as its body, parses it, and starts a run of the
existing source-agnostic import framework (`ImportRunService`, `ImportJob`, SSE progress) with the
parsed export as the run's `ImportRequest`. `DoorsGraphWriter` is the only class that writes
imported DOORS data from this path — the same structural stand-in for R1 that `WindchillGraphWriter`
and `JiraGraphWriter` already are (ADR 0013, ADR 0015).

`ImportRequest` was built empty, in `importer/`, specifically so DOORS would get this seam "for
free" when its export moved in-process (ADR 0015 §2). It does.

The Python importer's logic — derivation, MERGE, and the seven-statement ADR-0012 reconciliation —
is **ported, not reimplemented from a blank page**: `DoorsDerivations.kt` mirrors
`doors/derivations.py` function for function, and `DoorsImportCypher.kt` mirrors
`doors/importer.py`'s statements clause for clause, including the run-stamp comparison that makes
reconciliation cost nothing that grows with the module. Both importers therefore write the same
shape of data for the same file, which is what lets them coexist without the graph disagreeing with
itself about what a DOORS module looks like.

### 2. The file is never persisted, in memory only for the request that carries it

`call.receiveText()` reads the body; `DoorsExportParser.parse(text)` returns a `DoorsExport` value
holding the parsed objects and their derived Tier-1 fields. Nothing between the route and the run
holds a reference to the raw text after parsing returns, and the run itself never reads it again —
same discipline Windchill's upload already has, stated here because a reviewer asked for it by name
rather than inheriting it silently.

### 3. `__exportChecksum` — a Tier-1 property, and a gate on the write

SHA-256 of the raw uploaded bytes, hex-encoded, stored on the `:DOORSModule` node. It passes R1's
own test cleanly: the same bytes produce the same checksum every time, so it is exactly as
regenerable as `__sortKey` or `__id` — a fresh import of the same file reproduces it. It is declared
in `domain/GraphNames.kt`'s source-agnostic `Prop`, not in `DoorsProp`: nothing about the concept is
DOORS-specific — Windchill's upload could carry the same property the day someone needs the same
answer for it — and CLAUDE.md's R3 philosophy for shared Tier-1 vocabulary (`__moduleUrl`,
`__sortKey`) is exactly this reasoning applied once already.

**It gates the write, not just the display.** Before starting a run, the route reads whether a
module with this file's `__id` already exists and, if it does, whether its stored
`__exportChecksum` equals the checksum just computed. Equal means this exact file was already fully
and successfully imported — the request answers `200` with the existing module's summary and starts
no run. This is why the checksum is stamped **last**, after reconciliation succeeds, rather than
alongside the module's other properties early in the run: a run that fails partway must not leave a
checksum behind that makes the next, corrected attempt look like a no-op.

The gate lives behind a small `DoorsModuleGate` interface the route depends on rather than calling
`DoorsGraphWriter` directly, so a route-level test can fake it the same way `WindchillRoutesTest`
fakes the whole `ImportJob` — this route needs no database to prove its status codes.

This is deliberately the **same gate the planned push importer will call**. It is not upload-page
logic; it is import logic that happens to have two front doors.

### 4. A re-import is a write, and R8's predicate now decides one

Every other place `AccessCypher.visible()` runs, it decides what a *read* returns. This is the first
write it gates, and the shape carries over exactly: the gate query reads whether a module with this
`__id` exists **at all**, and — independently — whether it is visible to the caller's own
`AccessSet`. Three outcomes:

- **The module does not exist.** No visibility question to ask; the import proceeds and creates it,
  landing with whatever category `:__AccessDefault` assigns for `(sourceId: "doors", containerLabel:
  DOORSModule)` — nothing, if none is configured, which is the ordinary "not yet assigned" state
  every other container starts in (R8, `docs/features/access-control.md` §8.3).
- **The module exists and is visible** to the caller. The import proceeds as a re-import — the
  ordinary case, most of the time the person who imported it the first time.
- **The module exists and is not visible** — whether because nobody has categorised it yet, or
  because it is categorised into groups the caller is not in. The import is refused, `404`, and does
  not run.

That last outcome is the one worth stating plainly, because it looks at first glance like exactly
the leak R8 spends a whole section refusing: a `404` that reveals a module exists. It is a
deliberate, narrow exception, and it is narrow for a specific reason — the caller **already
possesses the fact** the response would otherwise be protecting. They hold the export file; they
already know the module's identity, its name, and everything in it. What the response adds is not
"this exists", it is "someone already imported this, and you personally cannot act on it yet" —
which is the one thing a self-service import capability cannot work at all without being able to
say. The alternative, silently accepting the upload and doing nothing, is not neutral: it is a lie
about what happened, and it is worse than the narrow leak it would avoid. Every read path in this
application keeps R8's stricter rule unchanged; this one write path does not, and the reason is
written here so it is never mistaken for the general case.

The message itself stays deliberately uninformative about *why* — "not currently visible to your
account, ask an access manager to assign it a category" covers both the unassigned case and the
wrong-group case with one honest sentence, because from the gate's own predicate they are the same
fact.

### 5. The upload is open to every signed-in user in the frontend; the backend is what actually gates it

Neither the JIRA settings page nor the Windchill one is hidden from a non-admin today — there is no
`canActivate` guard on `/settings/jira` or `/settings/windchill`, and the settings menu shows both to
everyone (`frontend/CLAUDE.md` §6: "route guards are convenience, never enforcement"). `/settings/doors`
follows the same shape: no guard, listed in the same menu. What actually stops a non-admin is
`requireRole(Role.ADMIN)` wrapped around `POST /doors/import` alone, inside `doorsRoutes()` — the
same per-file placement Windchill's own admin guard already uses, rather than a blanket wrapper in
`Routes.kt` that would take `GET`-shaped routes with it if this file ever grows one. A `sec-user`
who is not `sec-admin` can open the page and choose a file; the upload itself answers `403`, which
the frontend renders in place rather than hiding the page pre-emptively — the same asymmetry R8
already draws between a capability and an object.

### 6. Duplicate JSON keys are detected, not reproduced key-for-key

The Python parser installs an `object_pairs_hook` that keeps the *first* value for a repeated key
and stashes the duplicate under `attr::<key>`, at `ERROR` level. `kotlinx.serialization` has no
equivalent hook, and building a full custom JSON parser to get identical tie-break behaviour was
judged not worth it for a defect this rare: `DoorsExportParser` runs its own lightweight scan of the
raw text — a hand-written tokenizer that walks strings and brackets without building values, just to
find `"key":` positions at each nesting depth — and reports every repeat at `ERROR`, same severity
Python uses. The value kotlinx.serialization's own parse keeps for a repeated key is whichever the
**last** occurrence set (the opposite tie-break from Python's), and the warning says so. What both
implementations guarantee is the property that matters: a duplicate key is never silently invisible.
Which value wins is arbitrary either way, and an operator seeing the `ERROR` fixes it at the source
rather than trusting either.

### 7. Everything else is a direct port

Object identity, `__sortKey`, `__child`, `refersTo` (both directions), table-set detection, and the
seven-statement reconciliation are translated from `derivations.py` / `importer.py` with no behaviour
changes — `DoorsDerivationsTest.kt` runs the same cases `test_derivations.py` does. The one structural
difference is batching: Python batches 1 000–5 000 rows per transaction (`importers/CLAUDE.md` §10);
`DoorsGraphWriter` uses 500, matching `WindchillGraphWriter`'s existing constant, since Community's
lack of a query governor is the same constraint on both writers and there is no reason for this
importer's number to be a second, independently-chosen one.

## Consequences

- A third in-process importer. The framework needed nothing new — `ImportRequest`, `ImportJob`, and
  the checksum/visibility gate pattern are now proven across two independent sources.
- `AccessContainment.doors` and `doorsPlaceholders` already existed, written for the out-of-process
  Python importer's own call to `POST /access/reconcile`. Registering `DoorsImporter` under the same
  `importerId` ("doors") means `ImportRunService`'s post-run reconcile hook (`access-control.md`
  §8.3) applies to a run started from this route automatically, with no new wiring.
- Two DOORS importers now write the same module identity space. Nothing here reconciles or locks
  between them — they already agree on `__id`, `__moduleUrl`, and `__sortKey`, so whichever one runs
  last wins, exactly as two runs of the same importer would.
- The visibility gate on re-import is new *behaviour*, not just new code: a module that was visible
  to its importer yesterday and is not today (a category was revoked) genuinely stops being
  re-importable by that person until access is restored. That is R8 working as designed, not a bug
  a support ticket should "fix" by loosening the gate.
- The duplicate-key tie-break difference between the two importers (first-wins vs last-wins) means
  a file with this specific defect can, in principle, import a different value for the affected
  attribute depending on which importer ran. This is judged acceptable because the defect is already
  flagged loudly by both, and is expected to be fixed in the source export rather than relied upon.
