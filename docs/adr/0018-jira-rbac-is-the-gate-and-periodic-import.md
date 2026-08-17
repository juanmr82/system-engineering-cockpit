# ADR 0018: JIRA drops its project allow-list — RBAC is the gate — and gains a periodic import

Status: accepted
Date: 2026-08-16

## Context

Every other source in this product imports unconditionally and lets access categories (ADR 0016,
R8) decide who may read what. DOORS modules and Windchill documents are all pulled in; visibility
is a graph decision made after the fact, by a `sec-access-manager` assigning categories to
containers.

JIRA was the one exception. `JiraSettingsStore` held a project-key allow-list — `JiraJql.build`
filtered the search by `project in (...)`, and phase 5's sweep (`SWEEP_DECONFIGURED`) actively
deleted issues of a project a user unticked. Two mechanisms were deciding visibility at once: the
allow-list decided what got *imported*, and access categories decided what a signed-in user could
*see* among what was imported. Session 26's `AccessContainment.jira` (which propagates a category
from a `:JiraProject` to its issues) was built correctly against this, but the gap was named rather
than closed at the time — see that session's `HANDOVER.md` entry and ADR 0014's decisions #2, #5
and #18, all of which assumed the allow-list would stay.

The trigger to close it: this deployment's actual operating model is one service-account token
that can see every project it has been granted in JIRA, with no per-user project selection at all.
Under that model the allow-list was not adding safety — a JIRA-side grant already bounds what the
token can fetch — it was only adding a second, redundant configuration surface that had to be kept
in sync with reality by hand, and a settings-page picker that implied a kind of control (choosing
*which* of the token's projects to trust) the rest of the product does not offer anywhere else.

Removing the picker has one real consequence: it was the only thing that ever re-triggered an
import of a project that became newly relevant. Nothing else made JIRA data go stale on a schedule.
So this decision ships with a periodic importer as its other half, not as an unrelated addition.

## Decision

### 1. No project allow-list, anywhere

`JiraJql.build` no longer takes project keys and no longer has anything to validate — the query is
now `created <= "<bound>" ORDER BY key ASC`, fixed. `JiraSettingsStore`, `:__JiraSettings`,
`JiraFailure.NoProjectsConfigured`, `JiraFailure.InvalidProjectKey`, and `GET`/`PUT
/api/v1/jira/settings` are deleted outright, not deprecated — there is nothing left for them to
guard. `GET /api/v1/jira/projects` (the live proxy to JIRA's own project list) stays, repurposed
from picker data source to a read-only diagnostic on the settings page: "what this connection can
currently see," useful for confirming the token's actual reach without cross-referencing JIRA
itself.

### 2. The sweep collapses to one statement

Phase 5 used to run two sweeps: `SWEEP_DELETED` (an issue within a configured project that this run
didn't see) and `SWEEP_DECONFIGURED` (an issue whose project fell out of the configured list,
regardless of whether it was seen this run). With no allow-list, the second statement has nothing
to check against, and the first loses its `__projectKey IN $configuredKeys` clause. What remains is
exactly `NOT i.__id IN $seenIds` — an issue not returned by this run's unfiltered search is gone,
whether because JIRA deleted it or because the token's own JIRA-side grants changed. The importer
cannot tell those two apart and, under RBAC-is-the-gate, does not need to: either way, the issue is
no longer something this import can vouch for.

### 3. A source-agnostic coroutine scheduler, not Quartz

Considered independent of CLAUDE.md §4's "prefer fewer dependencies" framing, on pure technical
merits: Quartz's real value is a persistent JDBC job store, clustering, and cron/misfire/retry
semantics. None of that applies to a single-instance backend running one idempotent import per
source — and pursuing durability via `JobStore` would directly fight root `CLAUDE.md`'s "no second
persistence mechanism" rule, since `ImportRunService` already owns run state. `RAMJobStore` avoids
that fight but then buys nothing beyond a coroutine loop, at the cost of Quartz's blocking-thread
scheduling model sitting next to this codebase's `Dispatchers.IO` coroutine style everywhere else.

`ImportScheduler` (`backend/src/main/kotlin/com/sec/importer/ImportScheduler.kt`) mirrors
`ImportRunService`'s own shape: a `SupervisorJob` scope it owns, closed on `ApplicationStopping`.
It is source-agnostic — `importerId` is a string, nothing in the class names JIRA — and ticks are
delay-first: the loop waits a full interval before its first run rather than firing on construction,
specifically because a backend restart happens on every recompile in development, and ticking on
boot would mean every restart triggers a real import against a real JIRA host. The existing manual
"Import JIRA issues" trigger already covers "I want data now"; the scheduler only ever covers
"keep it fresh unattended." A tick that lands on an already-running import is a no-op by
construction — `ImportRunService.start()` already returns `StartResult.AlreadyRunning` rather than
throwing, so no extra guard was needed.

Configured via `jira.scheduleMinutes` (default **60**, present in `application.yaml`), on by
default once JIRA itself is configured — with no project picker left, periodic import is now the
*primary* way JIRA data in this cockpit ever changes, so leaving it off by default would mean a
fresh deployment silently never re-imports until someone finds the config key. `0` disables it
explicitly; this is the one knob in `JiraSettings` not read through the shared `intOr` helper,
because that helper treats any value `<= 0` as "not set, use the fallback" — wrong here, since `0`
has to be reachable to turn off a feature that defaults on.

`GET /api/v1/import/{importerId}/schedule` exposes `{ scheduled, nextRunAt, intervalMinutes }` for
the frontend, source-agnostic like the rest of `ImportRoutes.kt` — `scheduled: false` for an
importer with no scheduler is an ordinary answer, not a 404.

### 4. No restart mechanism, because none was needed

Considered and rejected: a control to restart the scheduler from the frontend if it "hangs." It
cannot hang in the sense that would need one — a tick either starts a run (which has its own
`DELETE /import/runs/{runId}` cancel path, already built) or gets `AlreadyRunning` and waits for
the next interval. The useful thing to expose was visibility, not control: "next scheduled import
at ⟨time⟩," reusing the existing manual "run now" button for anyone who does not want to wait.

## Consequences

**The settings page loses its most interactive section.** The project chip list, the "add a
project" picker, and the JQL preview are gone; the page is now two diagnostics (connection health,
what the token can see) and two controls (column choice, manual import). This is a net
simplification in the UI, matching the backend's.

**`docs/JIRA_ISSUES_FEATURE_SPEC.md` §8, §10.1, §12 and §13.5 point 2 are superseded by this ADR.**
Its own §17 already anticipated unconditional periodic import as a future direction; this decision
is that direction arriving, not a departure from the spec's own trajectory.

**A deployment upgrading in place carries an orphaned `:__JiraSettings` node.** Neo4j Community has
no migrations (root `CLAUDE.md` §7); the node simply sits inert once nothing reads or writes it —
an accepted characteristic of the platform, not a defect to fix here.

**Machine-auth for `POST /access/reconcile` remains a separate, still-open gap** (named in
`AccessRoutes.kt`'s own doc comment and in `HANDOVER.md`), unaffected by this decision — the
scheduler starts an *import*, and the existing in-process reconcile hook in `ImportRunService`
already runs after a successful JIRA run without needing a session.

## Rejected alternatives

**Keeping the allow-list as an optional narrowing, defaulting to "everything."** Rejected because it
keeps two visibility mechanisms in play for no benefit under the stated operating model — a token
scoped in JIRA already bounds what can be fetched, and a second, independently-drifting allow-list
in this application's own graph is a configuration surface nobody asked for and nobody would keep
in sync.

**A restart/health-check control for the scheduler.** Rejected per Decision 4 — nothing about this
design can get stuck in a state the existing cancel endpoint and the next scheduled tick cannot
already recover from.
