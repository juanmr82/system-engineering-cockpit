# CLAUDE.md — System Engineering Cockpit frontend

Guidance for Claude Code working under `frontend/`. This file loads **in addition to** the
root `CLAUDE.md`, which stays always-loaded and holds the rules that bind every stack: the `__`
namespace and Tier 1 / Tier 2 model (R1–R3, R5–R7), where each kind of state lives, the pinned
versions, the Neo4j Community limits, and the working agreements. Read that first; nothing here
overrides it.

Section numbers below are the root file's own and are deliberately unchanged — code comments and
`docs/` reference them as "CLAUDE.md §6", and those references still resolve.

## 6. Frontend — Angular 22 + Material

### Angular 22 idioms — this codebase is signal-first

- **Standalone components only.** No `NgModule`, ever.
- **`inject()`**, not constructor parameter injection.
- **Signals for all state.** `input()`, `output()`, `model()`, `computed()`, `linkedSignal()`.
- **`httpResource()` / `resource()`** for server data. Not bare `HttpClient` subscriptions,
  not a hand-rolled loading/error/data triple.
- **Signal Forms** (stable in v22) for anything with user input. No `FormGroup`/`FormControl`.
- **Built-in control flow** `@if` / `@for` / `@switch` / `@defer`. `*ngIf` and `*ngFor` are
  forbidden in new templates.
- **`OnPush` is the v22 default — do not declare `changeDetection` at all.** If you ever
  genuinely need the old behaviour it is now `ChangeDetectionStrategy.Eager`, and it needs
  a comment justifying it.
- **Zoneless.** No `zone.js`, no `NgZone` injection, no `setTimeout` to "make change
  detection run".
- **`@angular/build` application builder.** Webpack builders are deprecated in v22 — never
  add `@angular-devkit/build-angular`.
- **Angular Aria** (stable in v22) for menus, listboxes and disclosure widgets that
  Material does not cover.
- Lazy-load every feature route with `loadComponent`.
- Client-side rendering only. This is an internal tool behind auth; do not add SSR.
- Tests use the workspace's scaffolded runner (Vitest via `@angular/build`). Do not
  re-introduce Karma or Jasmine.

### Folder layout

```
frontend/src/app/
├── app.config.ts               ← providers: router, httpClient(withFetch), material
├── app.routes.ts
├── core/                       ← singletons: api client, error handling
│   └── auth/                   ← AuthStore (the /auth/me signal), route guards, CSRF interceptor
├── shared/                     ← reusable dumb components, pipes, directives
├── layout/
│   ├── shell/                  ← the skeleton: toolbar + sidenav + router outlet
│   ├── sidenav/
│   └── toolbar/
├── features/
│   ├── requirements/{statistics,modules,review,graph}/
│   ├── documents/windchill/
│   └── mbse/{soi-views,functions}/
└── styles/                     ← theme, tokens, typography
```
core/auth/ holds no token and no OIDC library (ADR 0017). The backend is the OIDC client; the browser makes same-origin requests with credentials and reacts to 401. A pull request adding angular-auth-oidc-client, keycloak-js or a JWT decoder has misread the architecture — and §4 of the root file says the same thing from the dependency side.

### Component file layout — the project standard

**A component is three files: `name.ts`, `name.html`, `name.scss`.** Wire them with
`templateUrl` and `styleUrl` (singular — `styles:`/`styleUrls:` are not used anywhere).

- **No inline `styles:` block, ever.** A component's CSS goes in its `.scss` file. This is
  what keeps a `.ts` file readable as logic and lets the stylesheet be edited, searched and
  reviewed as a stylesheet.
- **Templates move out too**, with one exception: a component whose entire template is a
  single element (`layout/sidenav/logo.ts`) may keep it inline, because a separate file for
  one tag adds noise, not clarity. It still gets its `.scss` file.
- **No `.component.ts` / `.service.ts` suffix**, matching the Angular v20+ style guide and
  the existing files: the class is `Modules`, the file is `modules.ts`. Feature specs in
  `docs/features/` written before this rule use the old `*.component.ts` spelling; the file
  *split* they ask for is the binding part, the suffix is not.

### Shared styles — `src/styles/_mixins.scss`

`src/styles` is on the Sass load path (`stylePreprocessorOptions.includePaths` in
`angular.json`), so any component imports shared patterns without `../../..` climbing:

```scss
@use 'mixins' as sec;

.sec-modules { @include sec.page-shell; }
.sec-modules__table-scroll { @include sec.scroll-panel; }
table { @include sec.data-table; }
```

- **Recurring UI patterns are mixins, not global utility classes.** Component styles stay
  scoped and semantically named, while the values that must not drift — table density, the
  Tier-2 accent, the bounded scroll container `position: sticky` depends on — live in one
  place. Add to `_mixins.scss` the second time a pattern appears; do not copy it.
- **A value two rules must agree on is a Sass variable in `_mixins.scss`, not a number in each.**
  `$compact-icon-hit-size` is the case that prompted it: the Modules gear is positioned out of
  flow, so a sibling's padding has to hold its width open, and the button's size and the
  reservation were two independent `30px`. Nothing tests that kind of coupling — jsdom has no
  layout — so a drift between them is invisible until someone looks at the page. Export the
  variable, use it as the mixin's default, and let the other rule read it.
- **Colour tokens are never `@use`d.** `_tokens.scss` emits the `--sec-*` custom properties
  once, globally, from `styles.scss`. Components reference `var(--sec-blue)` and never
  redeclare a token or hardcode a hex.
- **Material is adjusted only through M3 token overrides**, all of them in `_theme.scss`
  (`mat.table-overrides`, `mat.dialog-overrides`, …). No `::ng-deep`, and no rule targeting a
  `.mat-mdc-*` or `.mdc-*` class — those are internals and they move between minor versions.
  Styling `th`, `tr` or `table` from a component's own stylesheet is fine: that is the
  template's own markup.
- `_document.scss` holds the requirement-tree vocabulary (depth rails, object cards,
  verification and extended-attribute panels) from `docs/proposed_new_style.md`. **The Breakdown
  tab is its first consumer** (`docs/requirement-breakdown-tree.md` §5) — reuse it there rather
  than writing a parallel tree vocabulary, and extend it in place when a shape is genuinely
  missing, as `verification-panel($accent)` was. Mixins emit nothing until included, so the
  still-unused half costs no bytes.
- One trap from that first use, and it generalises: `twisty` draws a CSS triangle out of three
  borders and says nothing about the fourth. On a `<span>` that is fine; on a `<button>` the
  browser's own `border-right` survives and the triangle renders as an hourglass. Zero the fourth
  side *after* the include — `border: 0` before it takes the triangle with it.
- Changing `angular.json` requires a **dev-server restart** — it is build configuration, not
  watched source, and a running `ng serve` will silently keep the old Sass load path.

### Dialogs

A dialog owns its own presentation. Give it a **static `open()`** and let call sites pass
data only, so no caller can size it wrongly or forget the modal contract:

```ts
static open(dialog: MatDialog, data: ModuleSettingsDialogData) {
  return dialog.open<ModuleSettingsDialog, ModuleSettingsDialogData, boolean>(
    ModuleSettingsDialog,
    { ...SEC_MODAL_DIALOG, width: '760px', height: '620px', data },
  );
}
```

`SEC_MODAL_DIALOG` (`shared/dialog/modal-dialog.config.ts`) carries the R7 contract —
`disableClose`, `autoFocus`, `restoreFocus`. Spread it into every dialog; never re-declare
`disableClose` per call site and never set it to `false`. The three type arguments to
`open<T, D, R>` are what make `afterClosed()` return a typed result instead of `any`.

### Icons — `core/icons/sec-icons.ts`

Custom icons are real `.svg` assets in `public/icons/`, registered once by
`provideSecIcons()` and used as `<mat-icon svgIcon="gearbox" />`. Add an icon by dropping the
file in and adding one line to the `SEC_ICONS` map — never paste an SVG path into a
component.

Paths in that map are **root-absolute** (`/icons/x.svg`): a relative path resolves against
the current route and 404s on anything deeper than the root.

This deliberately avoids the Material icon *font*, which §8 requires be self-hosted (no
Google Fonts CDN, GDPR) and which is not shipped yet — a ligature such as
`<mat-icon>settings</mat-icon>` renders as the raw text "settings" until it is.

### Tables — ag-grid Community (ADR 0006)

**Every data table in the application is ag-grid.** Not `mat-table`, not a hand-rolled CSS grid.
One table system is the point of the decision; a second one would mean a reviewer has to know
which vocabulary a given view speaks. `mat-table` remains fine for a fixed, short, non-scrolling
list inside a dialog — it is *data* tables this rule is about.

- **Never `field`. Always `colId` + `valueGetter`.** ag-grid reads a dot in `field` as a property
  path, so `field: 'REQ. Priorität'` looks up `row['REQ']['Priorität']` and renders **blank, with
  no error**. Attribute names carry dots, spaces, slashes and umlauts, so this is not an edge case.
  Synthetic ids (`attr-0`, `attr-1`, …) and a `valueGetter` are the only correct shape — the same
  rule §11 already states, arriving through a different door.
- **The grid is registered once**, `ModuleRegistry.registerModules([AllCommunityModule])` in
  `core/grid/`. Never per component.
- **Theming is `styles/_grid.scss` and nothing else.** ag-grid emits its parameters as `--ag-*`
  custom properties inside a zero-specificity `:where(...)` rule, so a plain class selector setting
  `--ag-background-color: var(--sec-paper)` overrides it. Every value comes from the `--sec-*`
  ramp; **no colour, size or radius is written in TypeScript**, and no rule ever targets an `.ag-*`
  internal class. This is exactly the M3-token discipline of `_theme.scss`, applied to the grid.
- **A grid needs a bounded height**, the same as a sticky header does — a concrete
  `height`/`max-height`, not `flex: 1` alone.
- **Never set `position` on a cell.** ag-grid lays every cell out with `position: absolute` plus an
  inline `left`/`right` offset, so overriding it to `relative` silently discards that offset and
  the cell renders at its static position. It is invisible while a column is the only one pinned to
  its side — the offset is 0 — and drops the cell on top of its neighbour the moment a second one
  joins it. No override is needed anyway: an absolutely-positioned element is already a containing
  block, so a renderer can pin itself to the cell with `position: absolute; inset: 0`.
- **ag-grid's stylesheet is injected at runtime, after ours.** At equal specificity it wins, so
  overriding one of its structural rules (cell padding, for instance) needs two of our own classes
  — `.sec-grid .sec-grid__cell--x`, never an `.ag-*` name.
- **`autoHeight` measures a cell once, when it is created.** A renderer whose content arrives later
  — from a second request, say — is measured while it is still empty, and the row stays at its
  default height with the content spilling over every row beneath it. `resetRowHeights()` is *not*
  the fix: ag-grid rejects it for an auto-height column, in as many words, in the console. Measure
  the content and state it — a `ResizeObserver` calling `node.setRowHeight()` then
  `api.onRowHeightChanged()` — and guard against the observer feeding itself by doing nothing when
  the height is unchanged, and deferring the write to the next frame.
- **A cell that must fill its width cannot be a flex box.** `--custom` makes a cell `display: flex`
  so a chip sits at the top of a tall row, and a flex item is sized to its content — so a grid whose
  tracks are fractions collapses to its longest word. `display: block` on the cell, via the
  two-of-our-own-classes override, plus `inline-size: 100%` on the renderer's host. **A `<textarea>`
  hits this too and harder**: its intrinsic width is its `cols` — 20 characters — so it shrinks to a
  fraction of its column whatever `width: 100%` says. The same pair fixes both.
- **A renderer whose height must set the row's has to be in flow.** `position: absolute; inset: 0`
  is the right shape for a control filling a fixed-height cell, and the wrong one the moment the
  column carries `autoHeight`: an out-of-flow element contributes no height, so the row collapses.
  Pick one — a fixed row height and an escaping renderer, or `autoHeight` and a renderer in flow.
- **A renderer never fills its cell, and cannot be made to from the outside.** ag-grid puts two
  shrink-to-content wrappers — `.ag-cell-wrapper` and `.ag-cell-value` — between the cell and the
  component, on *every* column, and both are `position: static`. So `flex: 1` or `inline-size: 100%`
  on the host stretches it only inside a wrapper that is already the width of the text, and an
  in-flow `margin-inline-start: auto` has nothing to push against. There is no our-class handle on
  either wrapper. **To align anything to the cell's edge, pin it to the cell**: the cell is
  `position: absolute`, so it is already a containing block, and `position: absolute` +
  `inset-inline-end` on the element resolves against the full cell width. An out-of-flow element is
  invisible to `autoSizeColumns()`, so reserve its width with padding on an in-flow sibling.
- **`autoHeight` silently disables `autoSizeColumns()` for that column.** With `autoHeight` on, a
  cell's width stops being a function of its content, so ag-grid has nothing to measure — and it
  says nothing: no warning, no error, no exception, the column simply keeps the width it had. Since
  `autoHeight` is on in `SEC_GRID_DEFAULT_COL_DEF`, **a column that is to be sized to its content
  must turn it off**, together with `wrapText`. Measured on the Modules table: 200px with
  `autoHeight` on, 128px with it off, same rows. The trap is that 200px is also ag-grid's default
  column width, so the call looks like it worked.
- **`autoSizeColumns()` measures rendered rows only.** A longer value below the fold is not
  accounted for until it is scrolled into view. Fine for a list of modules; not a sizing strategy
  for a table of requirements. Pair it with a `maxWidth` so one pathological value cannot push
  every other column off screen, and leave the renderer's `text-overflow: ellipsis` as the floor.
- **Headers wrap by default** (`wrapHeaderText` + `autoHeaderHeight` in `SEC_GRID_DEFAULT_COL_DEF`).
  A DOORS attribute name is a phrase, and several of a module's differ only past the point a
  one-line header clips them. Set the header cell's `line-height` too: a header row centres a single
  line by setting `line-height` to the header height, and that inherits into the wrapped one.
- **Pin only what a reviewer reads *from* while scrolled elsewhere.** A pinned column takes its
  width out of the scrollable area permanently. Row identity qualifies; a cell that is empty most of
  the time does not — Issues and Comment were pinned right and are not any more
  (`docs/REQ_REVIEW.md` §5).
- **Do not use ag-grid's cell editing for Tier-2 data.** An editable cell is a second staging
  concept sitting next to the view's own buffer, and R7 allows exactly one. A custom cell renderer
  holding a real control, writing to the component's own `ref`-keyed buffer, is the shape.

### Charts — echarts via ngx-echarts (ADR 0008)

**Every chart in the application is echarts.** Not a hand-rolled SVG, not a second library. The
same one-implementation rule ADR 0006 applies to tables.

- **A number is not a chart.** A KPI tile, a system-level badge, a progress rule, a depth rail —
  these are layout, and they stay real DOM with their colour in CSS. Reaching for echarts to draw
  a two-segment bar inside a table cell is the wrong reading of the ADR.
- **`shared/charts/chart-theme.ts` is the only place a `--sec-*` token crosses into TypeScript.**
  It reads them once via `getComputedStyle`. **No hex literal belongs in any `.ts` file** — a
  component that calls `getComputedStyle` itself, or writes `#00205b` into an option, defeats the
  whole arrangement. This is the `_grid.scss` rule, restated for a canvas.
- **Every chart carries a visually-hidden data table** holding the same numbers, adjacent in the
  DOM. It is what a screen reader reads and what a jsdom spec asserts. It is not a fallback and is
  never conditional.
- **Option objects are built by pure functions** in `shared/charts/chart-options.ts`, and specs
  assert what those return — never rendered pixels. A canvas is invisible to jsdom, to screen
  readers, and to `sec/no-internal-namespace`, which cannot see inside an option object; the
  linter's cover does not extend here, so an alias-map violation in a chart label is caught by
  review and by those specs or not at all.
- **Chart types are registered once**, in `shared/charts/echarts-core.ts`, and that file is what
  `provideEchartsCore` loads — lazily, so echarts stays out of the initial bundle. Importing from
  `'echarts'` anywhere else silently undoes the tree-shaking.
- **jsdom cannot mount a chart**: it has no canvas and no `ResizeObserver`, and
  `NgxEchartsDirective.ngOnInit` throws outright without the latter. A spec that mounts a component
  containing a chart adds `provideEchartsTesting()` from `shared/charts/echarts-testing.ts`.
- **`resource.value()` throws when the resource is in an error state.** Guard every read with
  `hasValue()`. An unguarded read inside a `computed` the template consumes tears down the whole
  view — which is how one failed request took down a page that was specifically designed so it
  could not.
- **There is no time axis in this product.** Nothing in the graph is timestamped and R2 forbids
  storing a derived value to build history from, so "interactive" means re-ranking, rescaling and
  drilling through — never a date range. Adding trends means a timestamped snapshot store, which is
  a new persistence mechanism and needs its own ADR.

### Material pitfalls already paid for

- **Sticky table headers inside `mat-tab-group`.** Tabs measure lazily; a sticky header
  rendered while its tab was hidden gets wrong offsets. Set `[preserveContent]="true"` and
  call `table.updateStickyHeaderRowStyles()` on `(selectedTabChange)` for the newly shown
  table.
- **`position: sticky` needs a bounded scroll container.** Give the panel a concrete
  `height`/`max-height` in SCSS — `flex: 1` alone is not enough — and put `overflow: auto`
  on the wrapper, not on the table.
- **Modal dialogs are not movable or minimisable, by default and by intent.** Do not add
  `cdkDrag`. `disableClose: true` plus explicit Save/Cancel is the shape (R7).

---

---

## 8. Visual design — Airbus house style

The brief is to match `airbus.com`. Follow it exactly; this is not an axis to be creative on.

### Colour

| Token | Hex | Use |
|---|---|---|
| `--sec-blue` | `#00205B` | **Airbus blue.** Primary. Toolbar, sidenav, headings, primary actions. |
| `--sec-blue-deep` | `#005670` | hover/pressed on dark surfaces |
| `--sec-blue-mid` | `#0085AD` | secondary emphasis |
| `--sec-blue-light` | `#48A9C5` | selected states, chart series |
| `--sec-blue-pale` | `#74D2E7` | tints, hover backgrounds |
| `--sec-grey-blue` | `#8DB9CA` | disabled, dividers on blue |

Highlight colours (`#009F4D` green, `#84BD00` pistachio, `#EFDF00` yellow, `#FE5000`
orange, `#E4002B` red, `#DA1884`, `#A51890`, `#0077C8`, `#008EAA`) are **accents, and the
brand rule is one highlight per view**. Reserve them semantically and document the mapping
once in `styles/_tokens.scss`:

- `#009F4D` — verified / imported cleanly
- `#EFDF00` — TBD / unclassified (`DOORSTBD` without `__typeRaw`)
- `#FE5000` — unresolved placeholder (`__UNDEFINED`)
- `#E4002B` — import error, dangling link
- `#0077C8` — Tier-2 application data (R2), so a user annotation is instantly
  distinguishable from imported truth. This mapping matters: **a user must never mistake
  something the app added for something DOORS said.**

**One bounded exception: the system-level scale.** `--sec-level-0` … `--sec-level-4` run
green → teal → blue → purple → magenta across the L0–L4 vocabulary, and two of those stops are
`#009F4D` and `#0077C8` — colours the list above has already spent. The reuse is deliberate and
it is fenced:

- it is a **sequential scale over one closed vocabulary**, not a status signal, and a level is a
  position in a hierarchy — never good or bad;
- the tokens are only ever the fill of a **system-level chip**, which is a shape a user learns
  once and then reads by position, not by hue;
- the chip is still Tier-2 data, and still says so by being a filled chip — the thing `#0077C8`
  was carrying is carried by the *form*, not by that particular blue.

The risk this accepts, stated plainly: a green chip can read as "good" and a magenta one as
"bad", which is not what L0 and L4 mean. That is the cost of a hue ramp over an ordered
vocabulary, and it was chosen knowingly. **Do not extend this exception** — a second sequential
scale sharing semantic hues would leave neither meaning legible.

### Surfaces and neutrals — the paper style

The product is styled as **paper on a desk**, specified in `docs/proposed_new_style.md` and
implemented in `styles/_tokens.scss`. Content sits on white sheets with hairline edges over a
light blue-grey shell. Four rules generate the whole look, and a new pattern is derived from
them rather than invented:

1. **Separate with a hairline, not a shadow or a fill.** `--sec-line` at sheet and table edges,
   `--sec-line-soft` inside them. There is exactly one shadow token, for the sticky bar.
2. **Squared corners** — `--sec-radius` (2px) on a control, `--sec-radius-sheet` (3px) on a
   sheet. Never a pill; M3 defaults to pills and is overridden per component in `_theme.scss`.
3. **Colour is a rail or a rule, never a background** — the 3px navy top rule on a lead sheet,
   the depth rail down a card, the left rule on an accent panel. **Four** deliberate exceptions:
   the navy application toolbar; the filled Tier-2 chip, because that distinction must never need
   a second look; **a heading row in a requirements table**, which carries a light blue ground
   deepening towards outline level 1 (`--sec-heading-1` … `--sec-heading-6`); and **the row a view
   is about** (`--sec-subject`), the requirement whose breakdown is on screen.

   The last two are real amendments, not loopholes, and they share one shape: a rule or a rail
   marks *an edge*, and neither of these is an edge — each is a whole row that has to be findable
   among rows that look exactly like it. A heading has to be found while scrolling past nine
   hundred requirements in a flat list; the subject of a breakdown has to be found in a forest
   where it is drawn once per parent it refines, so it appears more than once and never at a
   predictable place. Both tints stay in near-white territory for exactly the reason the original
   rule exists — paper with a wash over it, never a fill competing with the Tier-2 chip — and the
   subject row **also says so in words**, so the colour is never the only thing carrying it.
   Anything wanting a background that is *not* one of these four is still wrong.
4. **Non-content text is 10px uppercase, letter-spaced, in `--sec-ink-3`** — column headers,
   field labels, the view eyebrow.

The neutral ramp is **cooled towards the blue** rather than taken from percentages of black:
against Airbus blue a pure-grey ramp reads as dirty, and white sheets on a neutral grey shell
read as holes rather than as paper. This is the one deliberate departure from the "use
percentages of black" guidance, and it is why the ramp is a closed set of tokens
(`--sec-shell`, `--sec-paper`, `--sec-wash`, `--sec-line`, `--sec-ink`…) — extend it in
`_tokens.scss` or not at all. Never hardcode a hex in a component.

Sizes, tracking and geometry are tokens too (`--sec-text-*`, `--sec-tracking-*`,
`--sec-radius*`). A component that needs a value not in the scale is a signal the scale is
wrong, not that the component is special.

### Typography

- **Inter** for all web UI — this is the Airbus web typeface.
- **Self-host it.** Airbus's own guidance requires self-hosted integration for GDPR
  compliance: no Google Fonts CDN, no `<link>` to `fonts.googleapis.com`. Ship woff2 in
  `frontend/public/fonts/` with `font-display: swap`.
- **Sentence case everywhere.** Capital only at sentence start.
- **Never italic.** Not for emphasis, not for captions, not for placeholder text.
- ALL CAPS only for headlines of three words or fewer, or where type is a design element.
- Left-align long copy.
- Use a tabular-figures variant (`font-variant-numeric: tabular-nums`) for all counts,
  IDs and object numbers — requirement tables read as data, not prose.

### Material theming

Angular Material's M3 palettes do not include Airbus blue. Generate a custom palette from
the seed rather than eyeballing one:

```
ng generate @angular/material:theme-color
# seed: #00205B ; secondary: #0085AD ; tertiary: #009F4D
```

Commit the generated `_theme-colors.scss` and apply it through `mat.theme()` in
`styles/_theme.scss`. **Do not override Material component internals with `::ng-deep`.**
Every visual adjustment goes through M3 system tokens or the component's own token
overrides (`mat.button-overrides(...)` and friends).

### Logo

Do **not** ship the Airbus logo or wordmark — it is a trademark with its own clear-space
and usage rules and this is not an Airbus-branded product. The sidenav logo block holds
the **System Engineering Cockpit** wordmark: set in Inter, sentence case, `#00205B` on
white, sized to the 64px sidenav header. Leave it as a single swappable
`layout/sidenav/logo.component.ts` so a real mark can drop in later.

### Density and motion

This is a data tool. Use Material's compact density (`-2`) for tables and lists,
default density for the toolbar and dialogs. Motion is functional only: sidenav
expand/collapse, menu open, route transition. No decorative animation. Respect
`prefers-reduced-motion` — this is part of the quality floor, not a nice-to-have.

---

---

### Auth in the browser — three rules

R8 in the root file is enforced in the backend. These three are what the frontend owes it.

- **`401` navigates, `403` renders.** A `401` is a **full browser navigation** to
  `/api/v1/auth/login?redirect=<route>` — not `router.navigate`, because the browser has to follow
  a redirect to Keycloak and an Angular route cannot. A `403` renders an in-app refusal naming the
  capability required. Conflating the two produces a redirect loop, and a redirect loop is close to
  unreadable from a screenshot.
- **Route guards are convenience, never enforcement.** Every guard has a matching backend test. A
  guard that is the only thing between a user and an endpoint is a defect in the backend, not a
  feature of the frontend.
- **Hide what the user cannot reach; never disable it.** A disabled sidenav item advertises a
  feature, and a greyed-out module name in a picker advertises a module. Same reasoning as R8's rule
  about counts: an absence must be indistinguishable from a nothing.

Two mechanics that follow from ADR 0017 and are easy to get wrong:

- **Credentials, not headers.** One provider change in `app.config.ts`; there is no token
  interceptor because there is no token. The CSRF token from `/auth/me` is attached to every
  non-`GET` by **one** interceptor.
- **`ng serve`'s proxy is load-bearing, not a convenience.** The cookie only travels same-origin, so
  a hardcoded `http://localhost:8080` anywhere in a service makes that request cross-origin and
  cookieless. `frontend/proxy.conf.json` is what keeps development and the packaged jar identical.

The Access views (`/access`) are a `sec-access-manager` feature and follow every rule in §6 and §8:
ag-grid for the grants matrix and the not-yet-assigned queue, Signal Forms for the inputs, and
**R5 holds without exception** — `sec/no-internal-namespace` will fail the build on a `__` name in
one of those templates, which is the point. The user-facing words are declared in `Aliases.kt` and
listed in the root file's R5 table.

## 9. The UI shell

**Status: built.** The current milestone is the first dynamic-content view,
**Requirements → Modules**, specified in `docs/features/requirements-modules.md`. Keep this
section as the contract the shell must continue to satisfy.

```
┌───────────────────────────────────────────────────────────────────┐
│ ░░░ toolbar (Airbus blue, fixed, 56px)                    [👤]    │
├──────────────────┬────────────────────────────────────────────────┤
│  LOGO            │                                                │
│  (64px header)   │                                                │
├──────────────────┤                                                │
│  Requirements    │                                                │
│   · Statistics   │            <router-outlet />                   │
│   · Modules      │            dynamic content                     │
│   · Req review   │                                                │
│                  │                                                │
│  JIRA            │                                                │
│   · Issues       │                                                │
│   · KIDS         │                                                │
│                  │                                                │
│  Documents       │                                                │
│   · Windchill    │                                                │
│                  │                                                │
│  CAMEO           │                                                │
│   · SOI views    │                                                │
│   · Functions    │                                                │
│                  │                                                │
│  Access ⚑       │  ← sec-access-manager only; ⚑ is the count of │
│   · Categories   │     containers not yet assigned                │
│   · Grants       │                                                │
│   · Containers   │     change any container's grant on demand    │
│   · Not assigned │                                                │
│   · Defaults     │                                                │
└──────────────────┴────────────────────────────────────────────────┘
```

### Behaviour

- `mat-sidenav-container` filling the viewport; sidenav `mode="side"` and opened on
  desktop, `mode="over"` below 960px with a hamburger in the toolbar.
- Sidenav width 280px expanded. A collapse control rails it to 64px (icons only, labels
  as tooltips). Collapsed state is a **per-user browser preference** — client-side only,
  never the graph, never the backend.
- The groups are **source families**, not arbitrary sections. The sidenav is
  rendered from a typed `NavGroup[]` fetched from `GET /api/v1/config/navigation` — never
  from hand-written markup, and never from the graph. Order is defined in the backend
  config file and is therefore identical for every user.
- **The backend config owns order; the frontend owns the items.** Config stores stable
  keys and their sequence; the route and component for each key are compiled in. A key
  present in code but absent from config falls back to its default position rather than
  disappearing — otherwise shipping a new view silently hides it until someone edits
  YAML.
- Ship a hardcoded default `NavGroup[]` in the frontend as the fallback when the config
  endpoint fails. A broken config file must not produce an app with no navigation.
- Group headers (Requirements, Documents, CAMEO) are the prominent level: `--sec-text-body`,
  semibold, Airbus blue, on a faint tinted background (`color-mix(in srgb, var(--sec-blue)
  8%, white)` — stays in near-white territory, never a saturated fill). Not clickable
  accordions, unless the group grows past ~6 items.
- Sub-items are the quiet level: `--sec-text-sm`, via the `--mat-list-list-item-label-text-size`
  override in `_theme.scss`, one step down the scale from the group header.

  Both were fixed `rem` values (`0.9375rem` / `0.8125rem`) and are tokens now, which is the general
  rule: **no component states a font size of its own.** Every size in the application comes from
  `--sec-text-*`, which is what made stepping the whole scale down one notch a single edit rather
  than a sweep. The one size deliberately *not* on the ramp is `--sec-text-label`, held at 10px by
  rule 4 of §8.
- Active route is marked with a 3px left rule in `--sec-blue-mid` plus a pale background —
  not a filled pill. M3 nav-list items default `--mat-list-active-indicator-shape` to
  `corner-full` (a pill); the sidenav overrides it to `0` so hover/focus/active states are
  square, matching this rule.
- **User icon** (right of toolbar): opens a `mat-menu` with display name, email, roles,
  **the groups the user is in**, connected graph/database name, and a sign-out item. It is the
  **only** toolbar action — there is no global save (R7). All of it comes from `GET /auth/me` and
  nothing is decoded in the browser (ADR 0017). The groups are shown because "why can I not see
  this module" is answered by that list nine times out of ten; a user in **no** group sees an
  application with nothing in it, by design (R8), and the menu is where they find out why.
- Every route is lazy (`loadComponent`) and renders a titled empty state naming what will
  live there. Empty states are an invitation to act, not an apology.
- Keyboard: visible focus rings on toolbar buttons and every nav item; the sidenav is a
  `<nav>` with a proper landmark label; skip-to-content link as the first focusable element.

### Routes

| Path | Component | Group |
|---|---|---|
| `/requirements/statistics` | `RequirementsStatisticsComponent` | Requirements |
| `/requirements/modules` | `ModulesComponent` | Requirements |
| `/requirements/review` | `RequirementReviewComponent` | Requirements |
| `/jira/issues` | `JiraIssues` | JIRA |
| `/jira/kids` | `JiraKids` | JIRA |
| `/documents/windchill` | `WindchillDocumentsComponent` | Documents |
| `/mbse/soi-views` | `SoiViewsComponent` | CAMEO |
| `/mbse/functions` | `FunctionsComponent` | CAMEO |

`/` redirects to `/requirements/statistics`. Unknown paths render a not-found component
inside the shell, not a bare page.

---
