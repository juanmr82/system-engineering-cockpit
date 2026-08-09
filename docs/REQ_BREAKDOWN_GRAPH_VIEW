# REquirement Brea Graph View — Implementation Spec

**Audience:** Claude Code, working in the *System Engineering Cockpit* monorepo
(Angular 22 + Material frontend, Ktor backend, Neo4j 2026.x Community, Python/batch importers).

**Scope:** a graphical, node-and-edge rendering of requirement dependencies, opened from a
button in the requirement breakdown tree's toolbar.

**Related documents (authoritative, do not contradict):**
- `SEItem Data Schema` — the data contract.
- `DOORS_TO_NEO4J_IMPORTER_SPEC.md` — how the data got into the graph.
- `DOORS_TABLE_RENDERING.md` — the sibling view; reuse its card component and its rules.

**Terminology used here:** *node* = one requirement, drawn as a card. *edge* = one arrow
between two nodes. Edges carry **no label**, only a direction (arrowhead).

**Prime directive from the project rules:** imported data stays exactly as imported. Layout
results, manual positions and user preferences are **never** written as properties onto
`DOORSObject` nodes. If they must be persisted, they go into a separate settings node linked
by a `__`-prefixed meta relationship.

---

## 1. What this view shows

- **Nodes** are `SEItem` objects — in practice mostly `DOORSRequirement`, plus whatever else
  the traversal reaches (`DOORSInformation`, `DOORSHeading`, `__UNDEFINED` placeholders).
- **Edges** are `refersTo` relationships, drawn source → target with an arrowhead.
- **Node content is identical to the requirement breakdown row.** Same data, same component,
  different layout. See §5.1 — this is a hard requirement, not a nice-to-have.
- **Edges have no text.** This is also the honest choice: per the importer spec §10, the DXL
  discards the DOORS link-module name, so satisfies / verifies / refines **do not exist in
  the graph**. There is nothing truthful to label an edge with. Do not invent one.

### 1.1 The incompleteness warning is mandatory

Only `__outputLinks` are imported (importer rule R3), so the graph is **incomplete by design
until every referencing module has been loaded**. The reference module alone has 490
in-links with no corresponding out-link yet.

A dependency view that silently shows a requirement with no incoming arrows will be read as
"nothing depends on this". That is a wrong and potentially expensive conclusion in a
requirements tool.

**Therefore the dialog must always show, above the canvas:**

- a persistent banner when any node in the current scope is `__UNDEFINED`, listing the
  distinct `__moduleUrl` values that need importing, and
- the sentence *"Only outgoing links are imported. Missing incoming arrows may mean the
  referencing module has not been imported yet."* — always visible, not behind a tooltip.

---

## 2. Entry point

Add a `mat-icon-button` to the **toolbar of the requirement breakdown tree**, right-aligned,
next to the existing view controls.

```html
<button mat-icon-button
        [disabled]="!hasScope()"
        matTooltip="Show dependency graph"
        aria-label="Show dependency graph"
        (click)="openGraph()">
  <mat-icon fontSet="material-symbols-outlined">hub</mat-icon>
</button>
```

- Icon: `hub` (Material Symbols) — a node-and-edge glyph. `account_tree` is already the
  breakdown tree's own icon; using it here would say "tree" when the point is "graph".
  `schema` is an acceptable alternative if `hub` reads badly at 20 px in your theme.
- The button is **enabled only when a scope exists** (§3.1) — a selected requirement, or a
  filtered/selected subtree. Never let it open an unbounded whole-database graph.
- The dialog inherits the tree's **current filter and displayed attribute columns**, so the
  cards look the same on both sides.

### 2.1 Dialog sizing — fixed near-fullscreen, content fits inside it

**Decision: the dialog does not size itself to the diagram, and is not drag-resizable.**
It opens at a fixed near-fullscreen size; the *canvas* fits the diagram inside it on open.
Open it from a route, not from a bare service call, so the view is deep-linkable and
shareable in a review.

```ts
this.dialog.open(DependencyGraphDialogComponent, {
  data: { seedIds, depth: 2, direction: 'BOTH' },
  width: '92vw', height: '92vh', maxWidth: 'none',
  minWidth: '960px', minHeight: '600px',
  autoFocus: 'dialog', restoreFocus: true,
  panelClass: 'sec-graph-dialog',
  ariaLabel: 'Dependency graph',
});
```

Why not size-to-content:

- Diagram extent varies from 3 nodes to the 300-node cap. Above roughly 30 nodes the diagram
  is already wider than any screen, so "fit the dialog to the diagram" collapses into
  "fullscreen" for most real scopes — the behaviour differs only for the cases that need it
  least.
- The extent changes whenever depth, direction or the level strategy changes. A
  content-sized dialog would jump and re-centre under the user's cursor on every control
  change. Panning inside a stable frame is much calmer.
- Toolbar, legend and the incompleteness banner need a predictable position. They are
  reference chrome, not decoration.

Why not drag-to-resize: `MatDialog` has no built-in resize, so it means a CDK drag handle
plus `dialogRef.updateSize()` plus re-fit logic — real code and a new focus-management edge
case, to move between "92 % of the screen" and "slightly less than that". Poor trade.

What to provide instead:

- **Maximise / restore toggle** in the dialog header (`open_in_full` / `close_fullscreen`):
  92 vw/vh ↔ 100 vw/vh with the chrome condensed. One `updateSize()` call, genuinely useful
  on a projector during a review.
- **"Open as full page"** — the dialog is already routed (§2), so link to the same view as a
  standalone route. That is the right escape hatch for a long working session; a dialog is
  for a quick look.
- **Small graphs stay small.** Fit-to-viewport is clamped to a maximum of 100 % zoom, so a
  four-node graph renders at natural size, centred, with whitespace around it. Do not blow
  cards up to fill the dialog — oversized cards look broken and the whitespace correctly
  signals "this is all there is".

Escape closes, focus returns to the toolbar button. The header carries: the scope summary
("SRD-142 + 2 hops, 87 of 214 objects"), depth and direction controls, zoom controls, the
layout/reset menu, the legend toggle, maximise and close.

---

## 3. Data

### 3.1 Scope — never load the whole graph

A module can hold 12 000 objects; the reference module alone has 409 links. A full-module
graph is an unreadable hairball and a rendering problem you do not need to have.

Scope is always `seedIds + depth + direction`:

| Control | Values | Default |
|---|---|---|
| Seeds | the selected requirement(s), or the roots of the visible breakdown subtree | selection |
| Depth | 1–5 hops | 2 |
| Direction | `DOWNSTREAM` (out), `UPSTREAM` (in), `BOTH` | `BOTH` |

Hard cap: **300 nodes**. On overflow, return the nodes closest to the seeds (breadth-first),
set `truncated: true`, and show *"Graph limited to 300 objects — reduce the depth or select
a narrower scope."* Never silently drop nodes.

### 3.2 Query

```cypher
CYPHER 25
MATCH (seed:SEItem) WHERE seed.__id IN $seedIds
CALL (seed) {
  MATCH (seed)-[:refersTo*0..2]-(n:SEItem)      // undirected for BOTH; see note below
  RETURN collect(DISTINCT n) AS reached
}
UNWIND reached AS n
WITH collect(DISTINCT n) AS nodes
UNWIND nodes AS a
OPTIONAL MATCH (a)-[:refersTo]->(b:SEItem) WHERE b IN nodes
RETURN nodes,
       collect(DISTINCT {source: a.__id, target: b.__id}) AS edges
```

Notes:

- Returning the **induced** subgraph (all `refersTo` edges *between* reached nodes, not just
  the traversal's own edges) is what makes the picture correct. A tree-only edge set would
  hide real dependencies between siblings.
- `DOWNSTREAM` uses `-[:refersTo*0..N]->`, `UPSTREAM` uses `<-[:refersTo*0..N]-`, `BOTH` uses
  the undirected form. The **edge collection stays directed in all three cases** — direction
  is data, not a view option.
- Neo4j does not accept a parameter as a variable-length upper bound. Validate `depth ∈ 1..5`
  in the API layer and interpolate the integer into the query string. This is the **only**
  string interpolation permitted in this feature, and it comes from a closed set — the same
  discipline the importer applies to its static label lists.
- Read-only session: `withDefaultAccessMode(AccessMode.READ)` + `executeRead`, off the event
  loop via `Dispatchers.IO`.
- Sort nodes by `__sortKey` before returning, so layout input is deterministic (§4.6).

### 3.3 DTOs

Reuse the breakdown row DTO for the card payload — one DTO, one component, two layouts.

```kotlin
@Serializable
data class DependencyGraphDto(
    val nodes: List<GraphNodeDto>,
    val edges: List<GraphEdgeDto>,
    val levels: List<LevelBandDto>,        // ordered, top to bottom
    val truncated: Boolean = false,
    val unresolvedModules: List<UnresolvedModuleDto> = emptyList(),  // §1.1 banner
)

@Serializable
data class GraphNodeDto(
    val itemId: String,                    // __id — the only key, never DOORS `id`
    val card: RequirementCardDto,          // SHARED with the breakdown view
    val level: Int?,                       // null == unknown, see §4.1
    val isSeed: Boolean,
    val isPlaceholder: Boolean,            // __UNDEFINED
    val moduleUrl: String?,
    val truncatedNeighbours: Int = 0,      // edges cut by the 300-node cap
)

@Serializable
data class GraphEdgeDto(
    val source: String,                    // itemId
    val target: String,                    // itemId
)

@Serializable
data class LevelBandDto(
    val level: Int?,
    val label: String,                     // e.g. "Level 1 – System", "Unresolved"
)
```

`GraphEdgeDto` deliberately has no `type`, `label` or `weight` field. Do not add one until
the DXL exporter is extended to emit link-module names — adding an empty field invites
someone to populate it with a guess.

---

## 4. Layout

Top-to-bottom layered layout. The vertical axis **is** the level axis, which is why "a bit
lower" reads as "still in the same band, but downstream".

### 4.1 What "level" means — pick a strategy, make it pluggable

The word is overloaded in this project, so implement it as a strategy interface with a
default, and put the choice in the dialog's overflow menu:

| Strategy | Source | Use when |
|---|---|---|
| `MODULE_SE_LEVEL` **(default)** | parsed from the owning module's `moduleFullPath`, e.g. `/XXX-/Level 1 - System/SRD` → 1, via a **configurable** regex | the graph spans modules — the normal case for traceability |
| `OUTLINE_LEVEL` | the object's `objectLevel` | single-module graphs, where level means outline depth |
| `GRAPH_RANK` | longest path from the source nodes of the `refersTo` DAG | no reliable level metadata at all |

Rules for `MODULE_SE_LEVEL`:

- The regex is configuration, not a hardcoded literal — module folder conventions differ per
  project and this one is guessed from one example path.
- When the path does not match, the module's level is **unknown**, not 0. Offer a manual
  per-module override in settings, persisted as a `__`-prefixed meta relationship to a
  settings node — never as a property on the `DOORSModule` node.
- `__UNDEFINED` placeholders have no module node at all (importer rule R6), only a
  `__moduleUrl` property. Resolve their level through that property if the module is known;
  otherwise level is unknown.
- **All unknown-level nodes go into one explicit band at the bottom, labelled "Unresolved".**
  Never quietly fold them into level 1.

### 4.2 Engine

Use **elkjs** (`elkjs/lib/elk.bundled.js`) with the `layered` algorithm, run in a **Web
Worker** so a 300-node layout never blocks the dialog. ELK's layered algorithm supports
externally imposed layers through partitioning, which is exactly the constraint here.

```ts
const graph = {
  id: 'root',
  layoutOptions: {
    'elk.algorithm': 'layered',
    'elk.direction': 'DOWN',
    'elk.partitioning.activate': 'true',
    'elk.layered.spacing.nodeNodeBetweenLayers': '48',
    'elk.spacing.nodeNode': '32',
    'elk.layered.crossingMinimization.strategy': 'LAYER_SWEEP',
    'elk.layered.nodePlacement.strategy': 'BRANDES_KOEPF',
    'elk.edgeRouting': 'ORTHOGONAL',
  },
  children: nodes.map(n => ({
    id: n.itemId,
    width: CARD_WIDTH,                 // fixed, see §5.2
    height: measuredHeight(n.itemId),  // measured, see §5.2
    layoutOptions: { 'elk.partitioning.partition': String(partitionOf(n)) },
  })),
  edges: edges.map((e, i) => ({ id: `e${i}`, sources: [e.source], targets: [e.target] })),
};
```

`partitionOf` maps level → a **dense, ordered** partition index (0, 1, 2, …), with the
unresolved band last. ELK requires contiguous partition numbers; a project whose levels are
1, 2 and 4 must not produce an empty partition 3.

Do **not** reach for `d3-hierarchy` — it lays out trees, and this is a DAG with cycles.
Do not use `dagre` — unmaintained, and it has no imposed-layer mechanism.

### 4.3 Same-level refinement — the sub-lane rule

*"If an object refines an object of the same level, put it a bit lower than its parent, but
in the same level."*

Partitioning gives you this for free: ELK is still free to create **several layers inside one
partition**, so an intra-level edge places its target one layer lower — but still inside the
partition. What it does *not* give you is the visual distinction between "one step down
inside a level" and "down to the next level", because layer spacing is a single global
option.

So add a deterministic post-pass on ELK's output:

```
compressBands(elkResult, partitions):
  for each partition, in order:
      collect the distinct y values of its nodes -> sub-lanes, ascending
      re-map sub-lane k to  bandTop + k * SUBLANE_GAP        // SUBLANE_GAP = 28 px
      bandTop(next) = bandBottom(current) + LEVEL_GAP        // LEVEL_GAP  = 120 px
  apply the same piecewise-linear y mapping to every edge bend point
```

`compressBands` is a **pure function** — no DOM, no ELK types beyond a minimal
`{id, x, y, w, h}` shape — and gets unit tests (§7). The x coordinates from ELK are kept
untouched; only y is remapped, and bend points are remapped by the identical function so
routing stays consistent.

Result: levels read as clearly separated horizontal bands, and same-level refinement reads as
a small step down within a band. That is the requested behaviour.

### 4.4 Level bands as background lanes

Draw each level as a full-width background lane behind the nodes, with a sticky label on the
left edge (`Level 1 – System`, `Level 2 – Subsystem`, `Unresolved`). Alternate a very low
contrast fill between adjacent bands. This is what makes "arranged by level" legible at a
glance rather than something the user has to infer from y positions.

Lane labels stay pinned during horizontal pan.

### 4.5 Cycles, self-loops, duplicates

- **Cycles exist** (`refersTo` is not guaranteed acyclic — A→B and B→A across levels is
  normal in traceability data). ELK breaks them by reversing edges internally.
  **The rendered arrowhead must always show the true data direction**, never ELK's reversed
  one. Style feedback edges with a dashed stroke so a user can see the layout had to fight
  the data.
- **Self-loops** (an object referring to itself) render as a small loop on the node's right
  edge. Do not drop them — they are usually an authoring error worth seeing.
- **Parallel duplicates** are `MERGE`d at import so they should not exist, but the importer
  spec says not to rely on that. Collapse duplicate `(source, target)` pairs to one edge.

### 4.6 Determinism

Reopening the dialog on the same scope must give the same picture. Sort the node list by
`__sortKey` and the edge list by `(source.__sortKey, target.__sortKey)` before handing them
to ELK, and pass a fixed `elk.randomSeed`. A layout that reshuffles on every open destroys
the user's spatial memory and makes screenshots in review minutes worthless.

---

## 5. Rendering

### 5.1 Nodes are the breakdown card, unchanged

Extract the breakdown tree's row into a shared standalone component and use it in both
places:

```
libs/se-cards/requirement-card.component.ts     # shared: breakdown row AND graph node
libs/dependency-graph/dependency-graph.component.ts
libs/dependency-graph/dependency-graph-dialog.component.ts
libs/dependency-graph/layout/                    # pure: partitionOf, compressBands, …
libs/dependency-graph/layout/elk.worker.ts
```

The card takes a `density` input (`'row' | 'node'`) that changes padding and clamping only —
**never which fields are shown**. Same `id`, same `__name`, same type chip, same displayed
attribute columns, same `__UNDEFINED` treatment. If the breakdown gains a column, the graph
gains it too, with no second change.

Apply the same content rules as the table spec: `Object Text` verbatim, `white-space:
pre-wrap`, `overflow-wrap: anywhere`, plain interpolation, never `[innerHTML]`, and never
fall back to `__name` where the real attribute is meant.

### 5.2 HTML nodes over an SVG edge layer

```
<div class="graph-canvas" [style.transform]="viewTransform()">
  <svg class="graph-canvas__edges"> … paths, one <defs> marker … </svg>
  @for (n of positionedNodes(); track n.itemId) {
    <sec-requirement-card class="graph-canvas__node"
                          density="node"
                          [style.translate]="n.x + 'px ' + n.y + 'px'"
                          [card]="n.card" />
  }
</div>
```

- **Do not use SVG `<foreignObject>`** for the cards. Text wrapping, Material theming,
  focus handling and printing all misbehave inside it.
- **Fixed card width** (`CARD_WIDTH`, ~260 px), **measured height**. Variable widths make
  layered layouts ragged and make the measure pass twice as expensive. This also keeps the
  wrapping behaviour identical to the table spec §6.6.
- **Measure before layout.** Render the cards once in a hidden, correctly-styled measuring
  container, collect heights with a `ResizeObserver`, wait for `document.fonts.ready`, then
  run ELK. Measuring against the fallback font produces heights that are wrong by enough to
  overlap edges.
- Position with `translate` (a composited property), not `top`/`left`.
- `contain: layout paint` on each node.

### 5.3 Edges

One `<path>` per edge, `fill: none`, `marker-end` for the arrowhead, from ELK's bend points
(remapped by `compressBands`) as an orthogonal polyline with rounded corners.

| State | Style |
|---|---|
| normal | solid, `--mat-sys-outline` |
| target is `__UNDEFINED` | dotted, muted, arrowhead outlined not filled |
| feedback edge (cycle) | dashed, arrowhead in the **true** direction |
| highlighted (hover/selection) | full-opacity accent, others dimmed to ~20 % |

No labels, no weights, no midpoint decorations. A legend in the dialog header explains the
four styles — with four line styles in play, a legend is not optional.

### 5.4 Placeholder nodes

`__UNDEFINED` nodes render as a ghost card: dashed border, muted, the text *"Not yet imported
— module X"* using `__moduleUrl`, and no attribute columns (there is no data behind them
beyond `__id`, `__moduleUrl` and `absoluteNumber`). They are not clickable through to a
detail view, because there is nothing to show.

### 5.5 Pan and zoom

The whole canvas sits under one composited transform. Zooming never re-runs layout.

```ts
readonly view = signal({ x: 0, y: 0, scale: 1 });
readonly viewTransform = computed(() => {
  const v = this.view();
  return `matrix(${v.scale}, 0, 0, ${v.scale}, ${v.x}, ${v.y})`;
});
```

| Input | Action |
|---|---|
| `Ctrl`/`⌘` + wheel, or trackpad pinch | zoom to cursor |
| plain wheel | pan vertically |
| `Shift` + wheel | pan horizontally |
| drag on empty canvas, or middle-drag anywhere | pan |
| `+` / `-` | zoom in / out one step (10 %) |
| `F` | fit to viewport |
| `Ctrl`/`⌘` + `0` | reset to 100 %, centred on the seeds |

A browser pinch gesture arrives as `wheel` with `ctrlKey === true`, so the first two rows are
one code path. Plain wheel must **not** zoom — hijacking it is the single most disliked
behaviour in diagram tools, and it breaks scrolling for anyone who lands on the canvas by
accident.

Also provide explicit zoom-out / percentage / zoom-in / fit buttons in the header. Keyboard
and wheel gestures are for people who already know the tool; the buttons are how everyone
else discovers that zoom exists, and the readout is how they get back to 100 %.

- Range 25 %–200 %, clamped.
- Below ~50 %, cards switch to a compact form (`id` + type chip only) — a level-of-detail
  switch driven by the zoom signal, not a CSS scale of the full card. Scaled-down body text
  is unreadable *and* costs the same to render.
- Fit-to-viewport on open, **clamped to a maximum of 100 %** (§2.1).
- Re-fit automatically on window resize or maximise **only while the user has not yet panned,
  zoomed or dragged**. Track a `viewportDirty` signal and stop auto-fitting once it is set —
  never yank the view after someone has arranged it.
- Respect `prefers-reduced-motion` on the fit/centre transitions.

### 5.6 Free node arrangement

Users can drag any node anywhere. The ELK result is the *starting* arrangement, not a
constraint.

**Use pointer events directly. Do not use `cdkDrag` here.** CDK drag-drop computes deltas in
page space and does not account for a `scale()` on an ancestor, so at 50 % zoom the node
travels twice as far as the cursor. This is the defining bug of hand-rolled graph editors —
divide every delta by the current scale:

```ts
onPointerMove(ev: PointerEvent) {
  const s = this.view().scale;
  const dx = (ev.clientX - this.startX) / s;
  const dy = (ev.clientY - this.startY) / s;
  // …write to the drag layer, not to component state, until pointerup
}
```

Mechanics:

- `pointerdown` on a card → `setPointerCapture`, `touch-action: none` on the card.
- **4 px movement threshold** before a drag starts, so a click that wobbles still selects
  rather than nudging the node.
- During the drag, write the node's `translate` and its incident edges' `d` attributes
  **directly to the DOM inside a `requestAnimationFrame` loop**. Do not push every
  `pointermove` through signals and change detection — at 300 nodes that is a dropped-frame
  machine. Commit the final position to state once, on `pointerup`.
- Only incident edges are re-routed during a drag. Everything else is untouched.
- Auto-pan when the pointer comes within ~40 px of a viewport edge, so a node can be dragged
  to somewhere currently off-screen.
- `Escape` mid-drag cancels and restores the pre-drag position.

**Level-band constraint (on by default).** The entire point of the layout is that y encodes
level (§4.1). Free vertical dragging destroys that reading within about ten seconds of
someone tidying up. So: **x is free, y is constrained to the node's own level band**, with a
`Lock to levels` toggle in the header (on) and `Alt` held during a drag as the temporary
override. A node dropped outside its band with the lock off keeps the position but gets a
small out-of-band marker, so the picture never lies about which level a requirement belongs
to.

- Optional 8 px grid snap, `Shift` to disable — helps rough alignment without a full
  alignment-guides feature.
- **Undo/redo** (`Ctrl`/`⌘` + `Z` / `Shift+Z`) over a bounded stack of ~50 position
  snapshots. People drag things by accident and the cost of not having this is re-running
  layout and losing the whole arrangement.
- **`Reset layout`** in the header menu re-runs ELK and discards manual positions, with a
  confirmation prompt when any manual position exists.
- Multi-select (marquee drag on empty canvas, `Shift`-click to add) moving as a group is
  phase 2, not phase 1.
- Keyboard equivalent: with a node focused, `Ctrl` + arrow keys move it 8 px, `Ctrl+Shift` +
  arrows move it 1 px, announced through an `aria-live="polite"` region.

**Edge routing after a move.** ELK's orthogonal bend points are only valid for ELK's
positions. As soon as either endpoint of an edge is moved manually, drop that edge's ELK
bend points and switch it to the **local router**: a deterministic 3-segment orthogonal
route (down / across / down) with the same corner radius and the same stroke styles as the
ELK routes, so the two are visually indistinguishable. The local router does not avoid node
overlaps — that is the accepted cost of manual arrangement, and it is the user's own doing
and therefore fixable by them. If the mismatch ever does read badly in practice, the fallback
is to use the local router for *all* edges and take ELK's node positions only.

**Persistence — session-scoped by default.** Manual positions live in component state for the
session. Persisting them automatically per scope creates stale arrangements the moment a
module is re-imported and node sets change. Provide an explicit **`Save arrangement`** action
that stores a named layout; on restore, nodes present in the saved layout take their saved
position, and nodes that are new get their ELK position plus a brief highlight so the user
sees what moved in.

Per the project rule, a saved arrangement is application data: it goes into a **separate
layout node linked by a `__`-prefixed meta relationship**, keyed by user and layout name.
It is never a property on a `DOORSObject`, and it never touches the imported data.

### 5.7 Interaction

| Action | Result |
|---|---|
| hover node | highlight its incident edges, dim the rest |
| click node | select; the selection syncs with the breakdown tree behind the dialog |
| double-click node | expand one more hop around that node (re-query, re-layout, keep the viewport centred on it) |
| context menu | "Open in document view", "Make this the seed", "Copy `__id`" |

A node with `truncatedNeighbours > 0` shows a small badge with the count, so a user can see
where the 300-node cap cut the picture rather than believing the graph ends there.

### 5.7 Accessibility

A node-link diagram is not navigable by screen reader on its own, so the dialog has two tabs:
**Graph** and **List**. The List tab renders the identical node and edge set as a nested
list ("SRD-142 → depends on 3, depended on by 1"), reusing the same card component. This is
the accessible equivalent, and it is also the thing people paste into review minutes.

In the graph itself: roving `tabindex`, arrow keys move along edges, `role="application"` on
the canvas with an `aria-describedby` explaining the key bindings, visible focus ring on the
focused card.

---

## 6. Performance

- 300-node cap (§3.1) — with fixed-width cards and ELK in a worker, layout stays well under
  200 ms at that size.
- `OnPush` everywhere, signals, `track n.itemId`.
- Edges as a single `<svg>` with one `<path>` per edge is fine at this scale. Do not reach
  for canvas or WebGL; if you ever need to, the cap is wrong, not the renderer.
- Debounce the depth/direction controls (~250 ms) — each change is a round trip plus a
  re-layout.
- Cache the layout result keyed by `(seedIds, depth, direction, levelStrategy, cardVersion)`
  in the API-layer/client cache so toggling back and forth is instant. **Not** in Neo4j.

---

## 7. Testing

Pure functions (no DOM, no ELK, no driver):

- `partitionOf`: dense renumbering with gaps in the level sequence (levels 1, 2, 4 → 0, 1, 2);
  unknown level always last; empty input.
- `compressBands`: single partition; several partitions with several sub-lanes each; a
  partition with one node; bend-point remapping matches node remapping exactly; output y
  ordering never changes relative order within a partition.
- Level parsing from `moduleFullPath`: matching path, non-matching path → unknown, manual
  override wins over parse.
- Cycle handling: a 2-cycle and a 3-cycle produce a layout, and every rendered arrowhead
  matches the source DTO's direction.
- Edge de-duplication and self-loop retention.

Backend, against the reference module in a Testcontainers Neo4j 2026.x Community instance:

- The induced subgraph contains edges between neighbours, not only traversal edges.
- `depth` outside 1..5 is rejected before any query is built.
- Dangling links (absolute numbers 445, 446, 367, 374) surface as `__UNDEFINED` nodes with
  `isPlaceholder = true` and populate `unresolvedModules`.
- The 300-node cap sets `truncated` and populates `truncatedNeighbours` on boundary nodes.
- The same request twice returns byte-identical JSON.

Component:

- The graph node and the breakdown row render the same field set for the same DTO.
- The incompleteness banner is present whenever `unresolvedModules` is non-empty, and the
  "only outgoing links are imported" sentence is present unconditionally.
- Reopening the dialog on the same scope produces identical node coordinates.

---

## 8. Things this feature must not do

- **Must not write layout, positions or level overrides onto imported nodes.** Settings node
  + `__`-prefixed meta relationship, or client/API cache.
- **Must not label edges**, or add a `type` field to `GraphEdgeDto`. The semantics are not in
  the export; a placeholder field will get filled with a guess.
- **Must not present "no incoming edges" as "nothing depends on this"** (§1.1).
- **Must not open unscoped.** No whole-module, no whole-database graph.
- **Must not use the DOORS `id` as a node key** — unique within a module only. `__id` always.
- **Must not silently drop nodes or edges** at the cap, on cycles, or on unknown level.
- **Must not reverse a rendered arrowhead** to suit the layout.

---

## 9. Open question to confirm with the user

The breakdown tree is the `__child` hierarchy; this graph is the `refersTo` traceability
network. They are different relations, and this spec assumes **dependency = `refersTo`**.

Two things worth confirming before step 4 of the build order:

1. Should `__child` also be drawn, as faint containment edges behind the `refersTo` ones?
   Implement it as a toggle, **off by default** — mixing two relation types in one untyped
   diagram is exactly how a traceability picture becomes misleading.
2. Which `level` strategy is right for the real module layout (§4.1). The `MODULE_SE_LEVEL`
   regex is currently a guess from a single example path, `/XXX-/Level 1 - System/SRD`.

---

## 10. Suggested build order

1. Shared `RequirementCardDto` + `requirement-card.component` extracted from the breakdown
   tree, with both densities. No graph yet.
2. Backend scope query, DTOs, cap and unresolved-module collection, with integration tests.
3. Pure layout functions (`partitionOf`, `compressBands`) + unit tests. No rendering.
4. ELK in a worker, measure pass, static rendering of a fixture graph — no interaction.
5. Level bands, edge styles, legend, incompleteness banner.
6. Dialog, toolbar button, scope controls, selection sync.
7. Pan/zoom, level-of-detail, hover highlighting, expand-on-double-click.
8. List tab and keyboard navigation.

Steps 1–4 are independently testable. Do not start 6 until 4 renders the reference module's
seed-plus-2-hops fixture correctly and deterministically.
