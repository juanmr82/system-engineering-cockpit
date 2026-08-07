# ADR 0008: ngx-echarts for the Statistics view charts

Status: accepted
Date: 2026-08-07

## Context

The Statistics view (`docs/features/requirements-statistics.md`) is the first view in the product
that draws anything. Nothing in `CLAUDE.md` §4 covers charting, so this is a dependency decision
that had to be taken explicitly rather than absorbed.

Two facts about the data shaped it, and both are unusual:

**There is no time axis.** Nothing in the graph records when an import happened, and R2 forbids
storing a derived value, so there is no history to plot. Every number the view shows is a snapshot.
That removes the single most common reason to reach for a charting library — zoomable time series —
and it means "interactive" here has to mean re-ranking, rescaling and drilling through to rows.

**The shapes are modest.** Everything the first iteration needs is a KPI tile, a ranked horizontal
bar, or a stacked bar. Not one scatter plot, box plot, or line.

Against that, one fact about the *future* shaped it more:

> "we will add more statistics as we are more data" — the user, specifying the view

The visual constraints are real and specific. `CLAUDE.md` §8 pins a closed neutral ramp, a
semantic highlight palette with one-highlight-per-view discipline, and a rule that colour is a rail
or a rule and never a fill. ADR 0003's paper style and ADR 0006's ag-grid theming rule both say the
same thing in different words: **no colour, size or radius is written in TypeScript.**

Three options were weighed.

**Hand-rolled SVG/CSS components.** A small `shared/charts/` set: bar-rank, stacked-bar, KPI tile.
No dependency. Exact `--sec-*` tokens with no bridge, because it is CSS all the way down. Real DOM,
so it is accessible by construction and assertable in jsdom. Log/linear and sort toggles are ~30
lines each. This was the first recommendation, and on the five charts specified it is the best
answer on every axis.

**Apache ECharts, via `ngx-echarts`.** `ngx-echarts` 22.0.0 peers `@angular/core >=22.0.0` — a
matched major, the same discipline already applied to angular-eslint. It exports a **standalone**
`NgxEchartsDirective`; the legacy `NgxEchartsModule` is in the bundle and is simply never imported.
The wrapper is 24 KB — the weight is `echarts` 6.1.0 itself, tree-shakeable through `echarts/core`
(measured at 152 KB transferred for the chart types actually used, see Consequences). Both are
Apache-2.0.

**AG Charts Community.** Same vendor as ag-grid, MIT, and it would keep one vendor vocabulary in
the codebase, which ADR 0006 valued. But zoom, the navigator and range selection are
Enterprise-only, so the interactivity that motivated looking past hand-rolling is largely absent
from the free tier. It loses to both of the others on the thing being decided.

## Decision

**`ngx-echarts` 22.0.0 + `echarts` 6.1.0, both pinned exactly**, not with a caret — the same
reasoning ADR 0006 applied to ag-grid: charting libraries ship majors fast and an upgrade is a
deliberate act.

The hand-rolled recommendation was made first and withdrawn. The deciding factor was not the five
charts in the spec but the ones that are not in it. Hand-rolling is cheapest for a known set and
worst for an open one, and the user's framing says the set is open. Two secondary factors settled
it: `ngx-echarts` supplies resize handling, and it supplies `chartClick` — and click-to-drill-through
is the interaction the spec cares most about (§8), so the alternative was hit-testing a canvas by
hand, which is worse than 24 KB by any measure.

Two mitigations are part of the decision, not follow-up work:

1. **`shared/charts/chart-theme.ts` is the only place `--sec-*` tokens cross into TypeScript.** It
   reads them once via `getComputedStyle` and hands back an echarts theme. No hex literal appears
   in any `.ts` file. This is ADR 0006's `_grid.scss` rule restated for a canvas.
2. **Every chart carries a visually-hidden data table** holding the same numbers, adjacent in the
   DOM. Specs assert the option object and that table; never rendered pixels.

Only the chart types and components actually used are imported, through `echarts/core` and
`provideEchartsCore`.

## Consequences

**Easier.** A new statistic is a new option object, not a new component. The interactions the spec
asks for — log scale, percentage stacking, sort, click-through, tooltips — are configuration rather
than code. When Windchill and Cameo statistics arrive they reuse `shared/charts/` unchanged, which
is why those components are shared and not feature-local.

**Harder.** Canvas is not real DOM: charts are invisible to jsdom, to screen readers, and to the
`sec/no-internal-namespace` ESLint rule, which cannot see inside an option object. Mitigation 2
covers the first two; the third means an alias-map violation inside a chart label is caught by
review and by the option-object specs rather than by the linter, and that is a genuine reduction in
the safety net. Colour also now has a second path into the app, and mitigation 1 is the only thing
keeping it single-source — a component that calls `getComputedStyle` itself, or writes `#00205b`
into an option, defeats the whole arrangement and must be caught in review.

**Bundle.** Measured after the fact rather than estimated: `echarts-core` builds as its own lazy
chunk of **526 KB raw / 152 KB transferred**, and the initial bundle is unchanged at 150 KB —
because only the Statistics route draws anything and `provideEchartsCore` loads the chunk on
demand. This app is internal and behind auth on a LAN, so the cost is acceptable; it would not be
on a public site.

**Foreclosed, for now.** A second charting library. If something genuinely needs a shape echarts
cannot draw, that is a new ADR, not a second dependency added quietly.

**Not foreclosed.** Hand-rolled components remain correct for anything that is really a *layout*
rather than a chart — the system-level badges, the depth rails, a progress rule. Reaching for
echarts to draw a two-segment bar inside a table cell would be the wrong reading of this ADR.
