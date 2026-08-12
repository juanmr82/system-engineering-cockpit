# Handover

Transient session-to-session note — not project documentation. Delete once its content is
absorbed into commits or superseded.

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
