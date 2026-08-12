# ADR 0013: The JIRA importer runs in the backend, and deletes what leaves its scope

Status: accepted
Date: 2026-08-10, carried forward 2026-08-11

> **Note, 2026-08-11.** This ADR was written against `docs/jira-issues-dynamic-view-design.md`, an
> earlier and shorter design that `docs/JIRA_ISSUES_FEATURE_SPEC.md` has since superseded. **Both
> decisions below survive that replacement unchanged** — the new spec assumes an in-backend importer
> (§14.2) and states the delete-on-leaving-scope rule as its own R4 — so the ADR is carried forward
> rather than rewritten, and its argument is the one still in force. Where it cites section numbers
> of the old design, read the new spec. The implementation it describes was never merged; the code
> on `feature/jira-issues-dynamic-view` is a fresh build against the new spec, and what it inherits
> from that branch is this decision, not that code.

## Context

The design of record specifies importing JIRA issues into Neo4j and exposing them as a configurable
table. Two things about it do not fit the shape this product already has, and both were settled
deliberately rather than discovered later.

**Every importer so far is a Python program run from a command line.** `CLAUDE.md` §1 lists them
that way, §3 puts them under `importers/`, and R1 leans on the split: *the application is read-only
on imported data*, which is easy to guarantee when the thing that writes imported data is a
different process in a different language. DOORS has to work like that — the importer needs a DOORS
client and runs only on Windows 11.

JIRA does not. It is a REST API reachable from wherever the backend runs, and the requested flow is
explicitly interactive: **a user clicks in the UI, the frontend calls the backend, the backend reads
JIRA and writes Neo4j, and the user gets an import report back in a dialog.** A Python CLI cannot be
in that path without inventing a job runner, a way for Ktor to start it, and a way to get its report
back — three mechanisms this product does not have, in service of a language boundary that exists
for a Windows dependency JIRA does not share.

**The second question is what to do with an issue that stops coming back.** ADR 0012 settled that
for DOORS three days ago: keep the object, label it `:__DELETED`, and show every link still pointing
at it, because DOORS deletes an object and *leaves the links*, so the ghost is evidence of a real
defect that nothing else in the toolchain surfaces.

## Decision

### 1. The JIRA importer is Kotlin, in the backend, behind one endpoint

~~`POST /api/v1/jira/import`, synchronous, returning the run's report as its response.~~

**Superseded 2026-08-11 by `docs/JIRA_ISSUES_FEATURE_SPEC.md` §11.4**, which starts a run with
`POST /api/v1/import/jira/runs` → `202 {runId}` and streams progress over SSE. The consequence
recorded at the foot of this ADR — that a synchronous import has a ceiling, and that the answer at
that point is a run resource the client subscribes to — is exactly what the new spec builds; that
paragraph now describes what was chosen rather than what was deferred. **Everything else in this
decision is unchanged: the importer is Kotlin, it runs in this process, and there are two writers
that cannot reach each other.**

**R1 stays true by structure, not by convention.** There are two writers and they cannot reach each
other:

| | writes | reached from |
|---|---|---|
| `meta/MetaWriter` | `:__Meta` and its `__` relationships, and nothing else | every dialog and table save |
| `source/jira/JiraGraphWriter` | `:SEItem:Jira*` and their source relationships | `JiraImporter`, and only it |

Neither is generic. `JiraGraphWriter` has no `setProperty`, no `update`, and no map parameter whose
keys a caller chooses; a route that wanted to "just change one field on an issue" would have to add
a method to a file whose name says *importer*, which is the point. The `importers/` tree keeps every
Python source and gains nothing JIRA-shaped.

### 2. An issue that leaves the import scope is deleted

Not marked. This is a deliberate departure from ADR 0012 and the reasoning that makes ADR 0012 right
is what makes it wrong here:

- **JIRA removes an issue's links when it removes the issue.** There is no dangling reference left
  behind, so there is no evidence to preserve — the thing that made a DOORS ghost worth keeping does
  not exist.
- **The reconciled set is a JQL scope an admin edits**, not a document a tool owns. Narrowing a
  project's filter, or taking a project out of scope, is the ordinary case, and it is not a
  deletion at all. A graph that kept a ghost for every de-scoped issue would be recording the
  administrator's changes of mind rather than JIRA's data, and the population would grow with every
  edit rather than with every genuine deletion.
- **`:__DELETED` would need different words here**, and they would be a lie half the time. The R5
  alias map renders it *Deleted in DOORS* precisely because that state means one thing; a second
  meaning covering "deleted, or moved, or filtered out, or the admin changed the JQL" is a state
  nobody can act on.

A link into an issue that is *outside* the scope is a different case and is not dropped: it becomes
a `:__UNDEFINED` placeholder, which this product already renders as *Not yet imported* and already
collects once nothing points at it. That is the design doc's §8 option (a), reusing a state that
exists rather than adding one.

### 3. The graph shape follows R1–R6 rather than the design doc's §4

The design doc's model — `(:Issue {key, ...})`, `MERGE (i:Issue {key: row.key})` — was written
outside this repository's rules. What was built instead:

```
(:SEItem:JiraSource   { __id: 'jira:source' })
(:SEItem:JiraProject  { __id: 'jira:project:PROJ', key, name, … })
(:SEItem:JiraIssue    { __id: 'jira:issue:PROJ-42', key, summary, status.name, …, __rawFields })
(:SEItem:JiraIssueType), (:SEItem:JiraField)

(:JiraSource)-[:__child]->(:JiraProject)-[:__child]->(:JiraIssue)-[:__child]->(:JiraIssue)
(:JiraIssue)-[:hasType]->(:JiraIssueType)
(:JiraIssue)-[:issueLink { linkTypeId, linkTypeName, inward, outward }]->(:JiraIssue)

(:JiraProject)-[:__importScopeFor]->(:__Meta:__ImportScope { enabled, jql })
(:JiraSource)-[:__attributeSettingFor]->(:__Meta:__AttributeSetting { attributeName, visible, order })
```

Four points in that are decisions rather than transcription:

- **Every node carries `:SEItem` and the Tier-1 four** (R6). A new source joins on `:SEItem` and
  nothing else (§1), and the catalogue nodes carry it too — a label that skipped it would need its
  own uniqueness constraint, its own identity rule and its own answer to "what is this called".
- **Containment is `__child`, for both project→issue and issue→sub-task** (R3). There is no
  `:IN_PROJECT` and no `:HAS_SUBTASK`; one relationship type is what lets one tree component walk
  DOORS modules and JIRA projects without knowing which it is looking at.
- **One `issueLink` type, not one per JIRA link type.** The design doc's §8 proposes sanitising
  `type.name` into `:BLOCKS`, `:RELATES_TO`. Those would be graph names invented from
  administrator-defined source data at runtime: undeclarable under ADR 0010, invisible to
  `GraphNamesTest`, unsearchable and unrenamable. The link type travels as relationship properties
  instead, with **both** phrases, so either end can be read without a second lookup.
- **`:JiraSource` exists to be an anchor.** The column selection is global to the Issues table, so
  under R2 Shape B it needs a set-owner, and there is no per-project column list to attach it to. It
  doubles as the tree root for the JIRA branch, which the projects hang off.

### 4. Every field is flattened, and the *selection* happens on read

The design doc's §3 proposes flattening only the fields an admin selected for display. That makes
imported data a function of application configuration: a column added in the dialog would render
blank until somebody re-imported, and the graph would hold different properties on Monday than on
Friday for reasons no export explains.

So the importer flattens everything — `status` becomes `status.name`, `status.iconUrl`, … to a depth
of three — keeps the raw block in `__rawFields`, and the *columns* are resolved on read from
`:__AttributeSetting`. That is exactly the mechanism the DOORS review table already runs on: the
importer copies every attribute, a runtime query discovers them, and Tier 2 decides what is shown.
One mechanism, two sources.

**But the data alone is not an authoritative list of fields, and this is where JIRA differs from
DOORS.** A field unset on every issue is `null`, the flattener emits that null on purpose — `+=`
removes a property whose value is null, which is what clears a field a user cleared — and so the key
exists on no node and `UNWIND keys(i)` cannot see it. The field is still *defined in JIRA*.

The field catalogue from `GET /rest/api/2/field` is therefore imported on every run and is a first
source for the selection tree, not merely a label lookup. The tree is the **union** of the catalogue
and the discovered paths, and what the catalogue can promise depends on the declared schema: for a
scalar (or an array of scalars) it states the exact path the flattener will write, so the column is
offered before anybody fills the field in; for an object it knows only that sub-keys will appear and
not which, so the field is shown and **not** selectable until data names them. Guessing `name` would
hand somebody a column blank for ever. `JiraFields.flattensToOwnPath` is that rule, it is pure, and
its test asserts it against what the flattener actually does so the two cannot drift.

That also narrowed §6.4's stale-column warning: it fires when *neither* source knows a path, not
when the data alone does not — otherwise every correctly-chosen column of an empty field is reported
as stale.

### 5. Settings is a routed feature reached from the toolbar

`/settings`, with a tab per integration. Two amendments to `frontend/CLAUDE.md` §9 come with it, and
both are recorded there:

- **The toolbar has two actions now**, not one. The sentence it replaces gave its own reason —
  *"there is no global save (R7)"* — and that rule is about a control that **writes** across views.
  A link to a settings route writes nothing.
- **Settings is not in the sidenav**, because the sidenav's groups are source families and
  administration is not one.

There is deliberately **no `isAdmin` guard**, which §9 of the design doc asks for. This application
has no authentication at all — the backend stamps `CurrentUser.PLACEHOLDER` on every write — so such
a guard would be a component reading a constant `true`. That is not a seam for RBAC, it is a thing
to delete when RBAC arrives, and to the next reader it would look like access control existed. The
route is the seam; the guard goes on it the day there is an identity to ask about.

## Consequences

**The backend now has an HTTP client**, `ktor-client-okhttp`, pinned in the root `pom.xml` and added
to `CLAUDE.md` §4. There is exactly one, and the Windchill and CAMEO clients the refactor document
anticipates use it. OkHttp rather than CIO because an import is a long series of requests to one
rate-limiting host, which is what its connection pool and timeout handling are for.

**JIRA is optional and its absence is a first-class state.** An unset host or token leaves the
integration unconfigured, the application starts normally, and the JIRA endpoints answer 503 with a
sentence. This is deliberately unlike `neo4j.*`, which fails startup when unset: the product cannot
work without a graph and works fine without JIRA, and a packaged `"$SEC_JIRA_TOKEN"` would have made
a token mandatory on every developer machine and in every container test.

**A synchronous import has a ceiling, and this records where it is.** A run over a few thousand
issues is seconds and the user gets a report; a run over a hundred thousand would outlive a proxy
timeout. The answer at that point is a job id the client polls — which needs a job store, a progress
channel, and a way to show a report for a run the user has navigated away from. None of that is
worth building before the number that requires it is known. A `Mutex` already refuses a second
concurrent run, because two runs would reconcile against each other's half-written state.

> **2026-08-11: the number turned out to be known, and it is 784 issues at ~7 MB a page.** The new
> spec builds all three of the things this paragraph deferred — a run resource (`:__ImportRun`), a
> progress channel (SSE), and a console that shows a run the user has navigated away from. The
> `Mutex` survives verbatim, now scoped per `importerId` so DOORS and JIRA can run at once.

**A run that fails part way through does not reconcile the project it failed on.** Reconciliation
deletes what the run did not re-stamp, so running it over a project whose fetch died half way would
delete the half that had not arrived. Projects are reconciled one at a time and only when their own
fetch completed; a failed one keeps everything it had and says so in the report. An authentication
failure aborts the whole run instead, because every remaining project would fail identically.

**A truncated JQL costs issues, and there is no prune guard** — the same trade ADR 0012 made and for
a stronger reason: here the data comes back on the next correct import, because JIRA is still the
source of truth for all of it. What does not come back is annotations on the deleted issues, and
`issuesDeleted` in the run report is the number that makes a mistake visible.

**Two things are now stated in three places and kept in step by hand**: the fixed columns
(`JiraFieldId.fixedColumns`, and the two constants in `jira-issues.ts`), and the sidenav fallback
that already had this problem. Both are commented at the site.

## Rejected alternatives

**A Python JIRA importer under `importers/`, consistent with DOORS.** It keeps one importer story
and keeps R1's guarantee behind a process boundary. It cannot serve the requested flow without a job
runner, a way for Ktor to launch it, and a channel to carry its report back to a dialog — three new
mechanisms whose only purpose would be to preserve a boundary that exists because DOORS needs a
Windows client. The structural split between `MetaWriter` and `JiraGraphWriter` buys the same
guarantee for the cost of a code review.

**Marking de-scoped issues `:__DELETED`, consistent with ADR 0012.** Rejected for the reasons in
Decision 2. The short form: DOORS leaves dangling links and JIRA does not, so there is no evidence
to preserve — and the common case here is an admin narrowing a filter, which is not a deletion.

**Relationship types generated from JIRA's link-type names.** The design doc's §8. It reads
beautifully in Cypher — `MATCH (a)-[:BLOCKS]->(b)` — and it puts administrator-editable strings into
the graph's vocabulary, where ADR 0010 cannot declare them, `GraphNamesTest` cannot check them, and
renaming a link type in JIRA silently creates a second relationship type meaning the same thing.

**Storing the column label on the `:__AttributeSetting` node**, as §6.4 suggests with
`{jsonPath, label, order}`. The label is resolved from the JIRA field catalogue on read instead, for
the same reason a `:__Classification` never stores "L2 – Segment": a field renamed in JIRA then
renames its column on the next import, rather than needing a migration of live user data.

**Icons.** §7 wants issue-type and status icons downloaded once and served from a disk cache behind
`GET /api/icons/{hash}`. That is a second persistence mechanism, which root `CLAUDE.md` forbids
without asking, and it needs a cache directory that works under the user profile on the offline
Windows workstation. Deferred with the user's agreement; issue type renders as text. If it is built,
the proportionate first version is a bounded in-process cache in front of a proxy endpoint, which
keeps the token off the frontend — §7's actual point — without introducing a store.
