# 0015 — Windchill imports from an uploaded export, in the backend

Status: accepted
Date: 2026-08-12

## Context

Windchill was planned as a Python importer (`CLAUDE.md` §1, and stubs under
`importers/src/sec_import/windchill/`) on the same shape as DOORS: a program on a workstation reads
an export and writes to Neo4j.

That is not how the data can be obtained. There is no machine-to-machine OAuth registration for
this Windchill instance, so the OData service cannot be called from a service account at all. What
exists is a script driving a browser session, which produces the response body as a file. Somebody
has to hand that file to the cockpit.

Two further facts shaped everything below:

- The export is **one OData page**. It may carry `@odata.nextLink`, and the user's decision is that
  a paged file still imports.
- **Several documents share a `Number`.** They are versions of one document, and the view has to
  show that.

## Decision

### 1. The importer runs in the backend, fed by an upload

`POST /api/v1/windchill/import` takes the file's text as its body, parses it, and starts a run of
the source-agnostic import framework with the parsed export as the run's input.

This is the second in-process importer, after JIRA (ADR 0013), and it inherits that decision's
guarantee: `WindchillGraphWriter` is the only class that writes imported Windchill data, which is
the structural stand-in for R1's "the application never writes imported data" that an out-of-process
importer gets for free.

The Python stubs are deleted rather than left. Two unimplemented importers for one source is exactly
the second way to do something this codebase forbids, and the file the exporter produces is read the
same way wherever it is read from. When machine-to-machine access arrives, this importer gains a
phase that fetches pages and loses nothing else — the parse, the write and the sweep are already
independent of where the bytes came from.

### 2. `ImportRequest` — a typed input for a run

The framework's `start(importerId)` took no payload, because every importer before this one is
self-driving: JIRA reads its host from configuration and its projects from the graph.

`ImportRequest` is an **empty marker interface** in `importer/`, carried from `start` to
`ImportContext.request` untouched. The framework never reads one, so it has nothing to declare —
which is what keeps "nothing in this package may name a source" true.

The alternative, a slot on the importer that the upload route fills in before starting the run,
works exactly as long as nothing races it. That is the same "works by coincidence" the JIRA
importer's `RunState` note already refuses: the per-importer mutex makes it true today and stops
making it true the moment runs are scoped per project. DOORS gets this seam for free when its export
moves in-process.

### 3. Valid JSON only

The exporter emitted Python dict syntax at one point. That is refused, with a `400` naming the parse
position. A lenient reader for a format nobody intends to produce is a permanent liability taken on
for a transitional mistake.

### 4. Identity is the OData resource URL; `Version` is not `__version`

`__id` is the document's `@odata.id`, which keeps "identity is the resource URL" true for every node
in the graph without exception (R6). A row without one falls back to Windchill's `ID` and **warns**,
because that is a different identity and a later export carrying `@odata.id` will re-key it.

Windchill's `Version` — `01 [2]` — stays **source data under its own name**. It is deliberately not
written to `__version`, which is the application's word for "the item as the source holds it now"
and whose value is `current` for every node in the graph. Conflating them would make `__version`
mean two things, which is the mistake it already refuses for a DOORS baseline (R5).

### 5. `State` is flattened to two scalars

`{"Value":"RELEASED","Display":"Released"}` becomes `StateValue` and `StateDisplay`, **both values
untouched**. Neo4j has no nested property; the alternative is JSON text, which would make the one
column a reviewer filters and sorts on a parse per row. A structural flattening that alters no value
is what R1 permits, and it is what the JIRA importer already does to `schema`.

### 6. `__sortKey` groups ascending and versions descending

The contract is R3's, unchanged: a plain string sort reproduces the order the view wants. The
derivation is `Number` ascending, then the version **complemented** (`999999999 - n` per digit run,
zero-padded) so that a larger version sorts first.

The separator is **U+0001**, and that is not decoration. A separator only works if it sorts below
every character a `Number` can contain; `|` sorts *above* every letter and digit, which puts `ABC-1`
before `ABC` — groups stayed adjacent, which is what made it look right, and the groups came out in
an order no plain sort of `Number` would produce. A test pins it.

A version carrying no digits is imported and reported: it cannot be ordered by anything but its own
text, and the run says how many were like that.

### 7. The export is the whole truth, and the guard is a report

Anything in the graph and not in the file is deleted, whatever folder it is in. Its failure mode is
exact: an export covering one Windchill context removes every other context's documents, and nothing
in the file states what it was *meant* to cover that could be checked against.

So the guard is the JIRA importer's mass-deletion warning, reused: the run ends
`SUCCEEDED_WITH_WARNINGS` and says how much went out of how many. A refusal would need a dry-run
count of the same statement and would block the legitimate case behind a flag nobody would find.

Two refusals sit in front of it, at the door rather than in the run:

- **An export with no usable documents is a `400`**, not a run. That file is what a *failed* export
  produces, and importing it would delete every Windchill document in the graph. Emptying the source
  is done by emptying it in Windchill and importing a file that proves it.
- **A file that is not an OData collection is a `400`.**

An `@odata.nextLink` is a **warning, not a refusal** — the user's explicit decision. It is said
twice: once by the upload response, while the user is still looking at the page, and once as a run
warning, because the sweep is about to treat one page as everything.

### 8. Annotations go with the document

The sweep deletes any `:__Meta` attached to a document it removes, in either direction — an
annotation hangs off the item, and a reified `:__Link` points at it. This is ADR 0012's rule applied
to a second source. The mandatory re-import test (R2) is in `WindchillImportTest`, and it protects a
note that nothing writes yet, on purpose: what is being tested is that `MERGE … SET` leaves
relationships alone, and that has to keep being true as the source grows.

### 9. The Documents view loads everything and works in the browser

The opposite call from the JIRA Issues table, and for opposite reasons. Production starts at ~1 500
documents; the whole set is a few hundred kilobytes, the search is instant with no round trip, and —
decisively — **grouping needs every version of a `Number` in hand at once**, which a page boundary
falling inside a group makes impossible to guarantee server-side.

The server caps the response at 20 000 rows and says when the cap was reached. Crossing it is the
signal to move to server-side paging, which is a design change and not a bigger number.

### 10. Group headers are synthesised rows, and the grid does not sort

ag-grid Community has no row grouping (Enterprise only), so the header over a document's versions is
an ordinary row the view puts in the row array. That has one consequence worth stating: **the grid
must not sort**, because a sort that moved rows would separate a header from its versions. Every
column carries `comparator: () => 0`, the header still shows its indicator, and the click becomes a
reorder of the array — groups move, versions stay inside their group, ordered newest-first.

A number with one version gets no header. A header is a finding aid for rows that are otherwise
indistinguishable, and one row is not that.

The header's band is `--sec-heading-1`, the same ground as a DOORS heading row and for the identical
reason (§8's third exception: a whole row that has to be findable among rows that look exactly like
it). It is a **different class** — `sec-grid__row--group` — because the heading scale above it is an
outline depth, which a document group does not have.

Version counts are taken from the **whole** set, never from the filtered one. Counting after the
search would make headers appear and disappear as a reader typed, which reads as the data changing
rather than as the view narrowing.

### 11. There is no Windchill credential, and the host is configuration

`windchill.host` in `application.yaml` decides exactly one thing: where a document row links to.
Absent means the link column is empty and the view says so; importing still works, because the file
is the source and the host is not.

The link is `<host>/app/#ptc1/tcomp/infoPage?oid=<ID>`, derived on every read and never stored (R2).
Windchill's own `ID` is stored and never shown — it exists to build that link.

## Consequences

- A second in-process importer, and the framework now has a typed way to be fed. DOORS is the next
  candidate and needs no new mechanism.
- `/api/v1/windchill/import` deletes documents and is unguarded, like every other route in this
  backend. That is the standing authorization gap (ADR 0014, point 9), not a new one.
- The user-visible failure mode of a partial export is a warning, not a refusal. If that proves too
  weak in practice, the next step is a confirm-before-delete on the upload response — the count is
  already known before the sweep runs.
- Folder hierarchy is not modelled. `FolderLocation` is a string on the document, as Windchill sends
  it; when folders become nodes they hang off `__child` like every other source's hierarchy (R3),
  and nothing in the current shape has to change for that.
