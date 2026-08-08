# Feature spec — Requirements → Statistics

> Status: specified and **built** 2026-08-07. The four questions this view opened were put to the
> user and answered before a line was written; §12 records the answers and what they cost. §15
> records what the implementation found that this spec did not anticipate.

The third dynamic view, after Modules and Req review. It is the first view that reads **across**
modules rather than inside one, and the first whose entire output is derived — nothing here is
ever written to the graph (R2).

---

## 1. Scope

Answers, for all requirements modules together or for one module at a time:

| Question | Band |
|---|---|
| How much is there? | 1 — Census |
| How much of it is unfinished? | 2 — Completeness |
| How much of it is connected? | 3 — Traceability |
| Does the trace graph contain closed loops? | 4 — Circular references |

Out of scope for this iteration, and deliberately: Windchill and Cameo statistics (no data yet),
trends over time (§3.6), and the aggregate `checks/attribute-policy` endpoint, which remains
unbuilt and is not needed — this view computes the same numbers from the same shared rule (§3.2).

---

## 2. Layout and the scope selector

```
┌──────────────────────────────────────────────────────────────────────┐
│ Statistics                                    [ All modules      ▾ ] │  ← eyebrow + scope
├──────────────────────────────────────────────────────────────────────┤
│ ▏Census                                                              │
│  ┌────────┐┌────────┐┌────────┐┌────────┐┌────────┐┌────────┐        │
│  │ Modules││ Items  ││ Requi- ││TBD/TBC ││ Links  ││ Loops  │        │
│  │   6    ││ 4 812  ││ 3 097  ││   214  ││ 2 640  ││   3 ●  │        │
│  └────────┘└────────┘└────────┘└────────┘└────────┘└────────┘        │
├──────────────────────────────────────────────────────────────────────┤
│ ▏Completeness                          [abs|%] [sort ▾] [log]        │
│  Mandatory attribute empty — by attribute      ▄▄▄▄▄▄▄▄▄▄▄▄▄         │
│  Verification attribute empty                  ▄▄▄▄▄                 │
│  TBD / TBC in text                             ▄▄▄▄▄▄▄               │
│  (All modules scope only) stacked bar, one row per module            │
├──────────────────────────────────────────────────────────────────────┤
│ ▏Traceability                                                        │
│  Requirements above L0 by parent state ▕████████░░▏  bar per module  │
│  Links into not-yet-imported objects                                 │
├──────────────────────────────────────────────────────────────────────┤
│ ▏Circular references                                                 │
│  A finding list, one sheet per loop — never a bare number            │
└──────────────────────────────────────────────────────────────────────┘
```

- Each band is a **sheet** in the paper style (`sec.page-shell`, hairline edge, 3px navy top rule
  on the lead sheet only). Bands do not collapse; a band with nothing to report renders its own
  empty state rather than disappearing, so the view has a constant shape.
- The scope selector is a `mat-select` holding **All modules** plus one entry per module, ordered
  as the Modules view orders them. It is the only control that reloads the whole page.
- The selected module lives in the URL as `?module=<ref>`, base64url per R5, so a finding is
  shareable. `Ref` decoding is total; a hand-edited handle is a 400 (§9).
- **No global save, nothing editable.** This view writes nothing, so R7 has nothing to guard and
  the view needs no exit guard.

---

## 3. Metric definitions

The definitions are the valuable part of this document. Every one of them must have exactly one
implementation — see §3.2.

### 3.1 The universe

| Term | Definition |
|---|---|
| **module** | `(:DOORSModule)` |
| **item** | `(:DOORSObject)` that is not a `:DOORSModule` — identical to `COUNT_MODULE_OBJECTS` |
| **requirement** | an item carrying `DOORSRequirement` or `DOORSTBD` and none of `DOORSTable`, `DOORSTableRow`, `DOORSTableCell` — identical to `ReviewProjection.requirementLike` |
| **placeholder** | `:__UNDEFINED` — an object some module refers to that no import has reached |

**Placeholders are never counted as items or requirements.** They are the importer's own
bookkeeping, not content, and counting them would inflate every total in proportion to how much is
*missing*. They are reported once, on their own, in Band 3.

### 3.2 One rule, one implementation

`issuesFor`, `missingMandatory`, `requirementLike` and `structuralTypes` were private inside
`ReviewProjection.kt`. They now live in `source/doors/DoorsChecks.kt` and **both views call it**.

They are deliberately *not* re-expressed as Cypher aggregates. Cypher can do it — `o[k]` accepts a
variable key — and it would be faster. It would also mean the same module could report 41 missing
mandatory values in Req review and 43 here, and no one would ever be able to say which was right.
A statistics view that disagrees with the table it summarises is worse than no statistics view.

The cost is that Band 2 streams every object of every module in scope rather than aggregating in
the database. Measured against the reference data that is ~5 000 nodes in one pass, which is
acceptable and is bounded by the read transaction timeout that `graph/Read.kt` already applies.

### 3.3 TBD / TBC — a text scan, and it is a heuristic

Per the decision in §12.2 this metric is the **literal text**, not the `DOORSTBD` label.

- Every non-`__` **string** attribute value on an item is scanned, with **one exemption**:
  `Object Type` is not scanned on table structure (`DOORSTable`, `DOORSTableRow`,
  `DOORSTableCell`). DOORS does not type the parts of an embedded table, so that attribute reads
  the literal string `TBD` on every one of them — on the reference module, 425 objects, which was
  the entire metric. It is the same fact `DoorsChecks.tbdCheckExclusions` already excuses from the
  fixed check in the Req review Issues column, and scanning the *value* was letting it back in
  through a second door, so the two views disagreed about the same module.
  The exemption is that attribute on those labels and nothing wider: every other attribute on a
  table object is still scanned, and `Object Type` is still scanned everywhere else — a
  requirement DOORS never typed is a real open point.
- Match is case-insensitive, bounded by non-letters on both sides, and tolerates a plural:
  `TBD`, `TBC`, `TBDs`, `tbd`. It does not match inside a word.
- An item counts **once** towards the Census tile however many markers it carries, and **once per
  attribute** towards the by-attribute ranking, so the ranking answers "which field is the one
  people leave open".
- The band states in words that this is a text search over source data, not a graph fact. It will
  match the sentence "no TBD items remain". That is accepted: the alternative is a curated marker
  list that is wrong for the next project.

The `DOORSTBD` label is not reported here. It remains the fixed check in the Req review Issues
column, where it is already actionable per row.

### 3.4 Mandatory attribute empty

Straight from the shared `missingMandatory`: the module's `:__Policy` nodes with
`rule: 'mandatory'`, scoped by each policy's own `appliesToLabels` (read, never assumed), table
structure excluded whatever the policy says, and "empty" meaning absent **or** blank.

Two numbers, because they answer different questions:

- **violations** — (item × attribute) pairs. Drives the by-attribute ranking.
- **items affected** — items with at least one violation. Drives the Census and the per-module bar.

A module with no mandatory policies contributes zero and is reported as **not configured**, not as
clean. The two look identical in a number and are opposite in meaning.

### 3.5 Verification attribute empty

For each module, the attributes flagged `verification: true` on its `:__AttributeSetting` nodes. A
requirement whose value for such an attribute is absent or blank counts, using the same
absent-or-blank rule as §3.4.

A module with **no verification attribute configured** is reported separately and **quietly** —
outlined, in `--sec-ink-3`, never in an error colour. Per the R5 alias map this is an absence of
configuration, not a finding, and the Breakdown tab already words it that way.

### 3.6 There is no time series, and there will not be one here

Nothing in the graph records when an import happened, and R2 forbids storing a derived value to
build history from. Every number in this view is a snapshot of now. Adding trends means adding a
timestamped snapshot store, which is a new persistence mechanism and therefore a decision for its
own ADR — not something to slip in behind a chart. **Do not add a date axis to this view.**

---

## 4. Band 1 — Census

Six KPI tiles. Numbers in tabular figures, label in the 10px uppercase non-content style.

| Tile | Value | Notes |
|---|---|---|
| Modules | count | in module scope, always 1 — the tile stays, so the row does not reflow |
| Items | §3.1 | |
| Requirements | §3.1 | |
| TBD / TBC | items matching §3.3 | `--sec-highlight-tbd` accent |
| Links | `refersTo` edges out of the scope | |
| Circular references | loops found (§7) | **`--sec-highlight-error` and a filled dot when > 0**, plain when 0 |

The loops tile is here only so a non-zero count is visible without scrolling; it scrolls to Band 4
on click. Every other tile scrolls to the band that explains it.

---

## 5. Band 2 — Completeness

**Primary chart, all scopes:** a ranked horizontal bar of *mandatory attribute empty by attribute
name*. Which attribute is unfilled is the actionable fact; the total is not.

**Second chart, All modules scope only:** one stacked bar per module — clean / TBD-TBC /
mandatory-empty / verification-empty — sorted worst-first by default. This is the "which module do
I fix first" chart and it is the reason the view exists.

In module scope the second chart is replaced by the same breakdown for that module alone, as a
single stacked bar plus its numbers, so the band keeps its shape.

Toggles (§8): absolute ↔ percentage, sort by value ↔ by name, linear ↔ log.

---

## 6. Band 3 — Traceability

### 6.1 Requirements above L0 without a parent

"Above L0" is read from the **module's** `:__Classification` (`scheme: 'systemLevel'`), because
that is where the level is anchored — a requirement has no level of its own.

Four states, not two:

| State | Meaning | Colour |
|---|---|---|
| has a parent | ≥ 1 outgoing `refersTo` to a resolved `:SEItem` | `--sec-highlight-verified` |
| parent not yet imported | has outgoing `refersTo`, every target is `:__UNDEFINED` | `--sec-highlight-undefined` |
| **orphan** | no outgoing `refersTo` at all | `--sec-highlight-error` |
| level not set | its module has no system level | outlined, no fill |

The third bucket is §12.4's answer: a link to a placeholder is an *import-scope* problem and a
missing link is a *data* problem, and different people fix them.

The fourth exists because "above L0" is undefined for a module with no level. Such modules are
excluded from the ratio and named, so the number is never quietly computed over a subset. Per the
alias map the badge stays, empty and outlined, with *No system level set for this module*.

`A -[:refersTo]-> B` reads as *A refines B*, so the **parent is the outgoing target** — the same
convention as the Breakdown tab (`requirement-breakdown-tree.md` §2), and it must not be inverted
here.

### 6.2 Links into not-yet-imported objects

`refersTo` edges whose target is `:__UNDEFINED`, with the distinct target modules named where the
module node exists. This is the one place placeholders are counted, and it measures *how much is
missing*, which is a different question from *how bad is what is here*.

---

## 7. Band 4 — Circular references

### 7.1 How it is computed

**Tarjan's strongly-connected-components over the whole `refersTo` edge set, in Kotlin.** Not a
variable-length Cypher pattern.

- `MATCH (a)-[:refersTo]->(b)` returns the edge list in one bounded read. On the reference data
  that is a few thousand edges.
- SCC is exact: no depth bound to be exceeded, and every loop is reported **once** rather than once
  per member, which a `*1..n` match would do.
- A component of size > 1 is a loop. A self-edge is a loop of size 1 and is reported — a
  requirement that refines itself is real and a depth-bounded walk finds it by accident at best.
- Within each component one concrete cycle is recovered by DFS and shown as the **ring**, in order,
  so it reads as the loop it is. Any remaining members are listed under *also in this loop*.

### 7.2 Scope, and why the module filter is applied last

The SCC always runs over the **whole** graph, and only the *reported* loops are then filtered to
those touching the selected module.

Filtering the edge set first would be faster and would hide exactly the loops worth finding: a
cycle that leaves a module and comes back is the most likely kind and the hardest to see by hand.

### 7.3 How it is shown

A finding list, never a bare number. One sheet per loop:

- the ring, each member as *id — name*, with its module name and system-level badge;
- each hop reading *refines* in the Breakdown tab's wording, with the closing hop marked so the
  loop is visible as a loop;
- a link opening that requirement's Breakdown tab, which is where it gets fixed.

**Zero loops renders a positive empty state** — *No circular references found* — not a blank sheet.
That is the answer you want most of the time and it should look like an answer.

### 7.4 It loads separately

Band 4 has its own endpoint and its own resource, so the other three bands paint without waiting on
the edge scan, and a timeout here degrades one band instead of the page.

---

## 8. Interaction

Interactivity is for **re-ranking, rescaling and drilling through**, because there is no time axis
to zoom (§3.6).

| Control | Scope | Why |
|---|---|---|
| Module selector | whole view | §2 |
| absolute ↔ percentage | Band 2 | a 900-object module and a 30-object module are not comparable in absolutes |
| sort by value ↔ name | Bands 2, 3 | worst-first to triage, by name to look one up |
| linear ↔ log | Bands 2, 3 | the reference data spans 977 objects to 30; linear makes the small modules invisible |
| **click a bar or tile** | everywhere | **the important one** |

Click-through targets:

| From | To |
|---|---|
| a module segment | `/requirements/review?module=<ref>` |
| a mandatory-attribute bar | Req review for that module, Issues filter on |
| an orphan bar | Req review for that module, requirements-only filter on |
| a loop member | that item's Breakdown tab |

A statistic you cannot act on is decoration. Every number in this view reaches the rows behind it
in one click, and that is an acceptance criterion (§13), not a nicety.

---

## 9. API

```
GET /api/v1/statistics/requirements[?module={ref}]
GET /api/v1/statistics/requirements/cycles[?module={ref}]
```

- `{ref}` is base64url `__id` (R5), decoded through the existing route-parameter converter.
  A malformed handle is a **400**, an unknown module a **404** — never an uncaught exception.
- Both are pure reads through `session.executeRead`. **Neither endpoint writes anything**, and
  §13 asserts that.
- Every statement carries a `LIMIT`; the transaction timeout comes from `graph/Read.kt`.
- Responses are `@Serializable` DTOs built from `Aliases`. **No `__`-prefixed name crosses the
  wire**, with the existing `labels` exception unused here — this view needs no state channel.
- Truncation is reported, never silent: if the edge scan or the object scan hits its cap the
  response says so and the band shows it, in the same spirit as the review table's `truncated`.

New files, following the existing shape:

```
api/routes/StatisticsRoutes.kt      ← registered from Routes.kt
api/dto/StatisticsDtos.kt
graph/cypher/StatisticsCypher.kt
source/doors/StatisticsProjection.kt
domain/Cycles.kt                    ← Tarjan, pure, source-agnostic, unit-tested alone
domain/TextMarkers.kt               ← the TBD/TBC scan, pure, source-agnostic
source/doors/DoorsChecks.kt         ← §3.2, shared with ReviewProjection
```

`DoorsChecks` sits in the DOORS package and not in `domain/`, unlike the two above it: it names
DOORS label strings, and nothing DOORS-specific may live outside that package (CLAUDE.md §1).
`Cycles` and `TextMarkers` name nothing source-specific, so they do not.

`Cycles.kt` sits in `domain/` and not in `source/doors/` on purpose: an SCC over an edge list knows
nothing about DOORS, and the day Cameo asserts its own links this is the code that finds loops in
them (R3).

---

## 10. Charting

`ngx-echarts` 22 + `echarts` 6, pinned exactly. ADR 0008 records why, and why not hand-rolled SVG
and not AG Charts Community. Two consequences bind every chart in this view:

1. **No colour, size or radius is written in TypeScript.** `shared/charts/chart-theme.ts` reads the
   `--sec-*` custom properties once via `getComputedStyle` and is the only place that bridges them
   into an echarts option. This is the `_grid.scss` rule for ag-grid, applied to a canvas.
2. **Every chart carries a visually-hidden data table** holding the same numbers. It is what makes
   the view usable with a screen reader, and what makes the numbers assertable in jsdom — specs
   assert the option object and the table, never rendered pixels.

Only the chart types actually used are imported, through `echarts/core` and `provideEchartsCore`.
`NgxEchartsModule` exists in the package and is never imported; the standalone `NgxEchartsDirective`
is the only entry point.

---

## 11. Frontend notes

```
features/requirements/statistics/
├── requirements-statistics.{ts,html,scss}   ← replaces the empty state
├── statistics-api.service.ts
├── statistics.model.ts
├── bands/{census,completeness,traceability,cycles}.{ts,html,scss}
└── shared/charts/{chart-theme.ts, bar-chart.*, stacked-bar-chart.*, kpi-tile.*}
```

- `httpResource()` per endpoint, two resources — the cycles one independent (§7.4). No hand-rolled
  loading/error/data triple.
- Signals throughout; `OnPush` is the v22 default and is not declared.
- Three files per component, `templateUrl` + `styleUrl`; no inline `styles:`.
- The charts are `shared/`, not feature-local — Windchill and Cameo statistics will want them.
- Lazy `loadComponent`, as every route already is.

---

## 12. Decisions taken before coding

### 12.1 Charts — ngx-echarts, changing an earlier recommendation

Hand-rolled SVG was recommended first and withdrawn. The deciding fact was the user's own framing:
*"we will add more statistics as we have more data."* Hand-rolling is cheapest for the five charts
specified here and worst for the ones that are not. The wrapper also supplies resize handling and
`chartClick`, and §8's drill-through would otherwise mean hit-testing a canvas by hand.

Accepted costs: canvas is not real DOM (mitigated by §10.2), and colours must be read into
TypeScript (mitigated by §10.1). Both are seams, both are documented, neither is a workaround.

### 12.2 TBD / TBC is the literal text, not the `DOORSTBD` label

Asked and answered: the text scan. The concern raised at the time and overruled is recorded here
because it will resurface — on a **sanitised export every object imports as `DOORSTBD`**
(CLAUDE.md §10), so on shared fixtures this metric reports near zero while every object is in fact
untyped. That is not a defect in this view: the label case stays visible in the Req review Issues
column, per row, where it is actionable. §3.3 states the heuristic in the band itself.

### 12.3 Cycles cover every `refersTo` edge, not requirements only

A loop routed through a heading or an information object is still a loop and still breaks the
Breakdown tree. The edge set is identical either way, so restricting it would have bought nothing
but blind spots.

### 12.4 A placeholder parent is its own bucket

Three states, not two — §6.1. Distinguishes an import-scope problem from a data problem.

---

## 13. Acceptance criteria

1. The view renders four bands in both scopes; a band with nothing to report shows an empty state
   and does not disappear.
2. Selecting a module updates every band and puts `?module=<ref>` in the URL; reloading that URL
   restores the same view.
3. A malformed `?module=` value produces a readable error, not a stack trace and not a blank page.
4. Item and requirement counts for a single module **equal** the Req review table's own total for
   that module. A discrepancy is a bug in §3.2, not a rounding difference.
5. Mandatory-empty and verification-empty counts for a module equal what the Req review Issues
   column reports over the same module — same shared rule, asserted by a test that runs both.
6. Placeholders appear in Band 3 and in **no** other count.
7. A module with no mandatory policies reads as *not configured*, never as clean. Same for
   verification, and quietly.
8. A module with no system level is excluded from the orphan ratio and named.
9. Cycle detection finds: a self-loop; a two-node loop; a six-node loop; two disjoint loops in one
   graph; a loop passing through a non-requirement; and reports **none** for a DAG that contains a
   diamond. Each loop is reported once.
10. Cycles touching the selected module are found when the loop leaves the module and returns.
11. Zero cycles renders *No circular references found*, not a blank sheet.
12. Every bar and tile reaches the rows behind it in one click (§8).
13. Every chart has a visually-hidden table carrying the same numbers.
14. **Neither endpoint writes to the graph** — asserted directly, by comparing a full property and
    relationship census before and after a call to both.
15. No `__`-prefixed name appears in any response, template or export header.
    `sec/no-internal-namespace` already fails the build on the template half.
16. `mvn verify`, `mvn -Pdocker test`, and from `frontend/`: `npm run lint && npm test && npm run build`.

---

## 14. Build order

1. §3.2 — extract the shared check rules; existing Req review tests stay green, unchanged.
2. `domain/Cycles.kt` + its unit tests. Pure, no database, fastest feedback in the whole feature.
3. `StatisticsCypher` + `StatisticsProjection` + DTOs; container tests against a **seeded scratch
   module** (never a live one — `HANDOVER.md` §1).
4. `StatisticsRoutes`, registered from `Routes.kt`.
5. Frontend: dependencies, `chart-theme.ts`, the shared chart components.
6. The four bands, Census first — it proves the scope selector and the resource wiring with the
   least machinery.
7. Band 4 last: it is the most valuable and the least like the others.

---

## 15. What the implementation found

### 15.1 `resource.value()` throws in an error state, and that nearly undid §7.4

Band 4 loads from its own endpoint precisely so a slow or failed edge scan degrades one band
instead of the page. The first implementation read `this.cycles.value()` inside a `computed` the
template consumes — and an Angular resource in an error state **throws** from `value()`. A failed
cycles request therefore tore down the whole view, which is the exact failure the split endpoint
exists to prevent.

Every resource read is now guarded with `hasValue()`. The spec that found it asserts something
stronger than "the message appears": it asserts that **nothing reaches the global `ErrorHandler`**,
because that is what distinguishes a handled failure from one that merely looks handled.

Now in `CLAUDE.md` §6.

### 15.2 Verification is asked of requirements only

§3.5 says "a requirement whose value…", but the first cut of `missingVerification` only excluded
table structure — so every heading in a module counted as unverified. A heading has nothing to
verify. It is now scoped to requirement-like objects, which is deliberately stricter than
`missingMandatory`: a mandatory policy carries its own `appliesToLabels` and can legitimately be
aimed at headings, while verification cannot.

### 15.3 A log axis is refused rather than degraded, in two different ways

An echarts log axis silently drops non-positive values, so a module with zero violations would
vanish from the chart rather than sit at the bottom of it. Two different answers, for two reasons:

- **Ranked bars** degrade to linear when any value is zero, and the toggle is disabled with the
  reason shown. Clamping to an epsilon would invent a value the data does not have.
- **Stacked bars** refuse a log axis outright, whatever is asked for. Segments are summed, and on a
  logarithmic axis the sum of two segments is not the length of both — the picture would be quietly
  false rather than merely hard to read.

### 15.4 Loop order is by `__id`, and a heading has a real DOORS id

Loops come back sorted by their smallest member `__id` so two identical requests read identically.
That is internal ordering over internal identifiers, and it does not match the displayed ids — a
loop containing `L1-H2` sorts before one containing `L1-4`. Only a `:__UNDEFINED` placeholder has
no source id; a heading has one and shows it.
