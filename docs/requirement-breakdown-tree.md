# Requirements → Req review → Breakdown tab

**Status: built.** Backend (`GET /api/v1/items/{ref}/breakdown`, `BreakdownProjection`,
`BreakdownCypher`) and frontend (`features/requirements/review/breakdown/`) both landed, with
Testcontainers coverage in `BreakdownFeatureTest` and unit coverage of the DAG-to-tree transform in
`breakdown.model.spec.ts`. §10 records what was decided where this spec left a choice open.
**Route:** no new route — lives inside `/requirements/review`.
**Component:** `features/requirements/review/breakdown/`, rendered as a tab inside the
existing detail panel component (§7 of `REQ_REVIEW.md`).
**Read first:** `CLAUDE.md` §2 (R1–R7, especially the meta-kind catalogue and the alias map),
`CLAUDE.md` §7 (Neo4j Community limits), `REQ_REVIEW.md` §7 (detail panel) and §9.2
(`:__AttributeSetting`).

This spec assumes the reader has just read `REQ_REVIEW.md` in full — it does not repeat the
table, comment or issues behaviour, only what changes or is added.

---

## 1. What this is

When a reviewer opens the detail panel for a requirement (`REQ_REVIEW.md` §7), they currently
see a flat attribute list. This adds a second tab, **Breakdown**, showing where that requirement
sits in the system's decomposition: the top-level requirement it ultimately traces up to, and
every requirement that decomposes it, down to L3/L4 — a *breakdown tree*, not a flat trace list
(that is already the References column, §5.1, and stays untouched).

The requirement the reviewer actually clicked is highlighted inside that tree, wherever it falls
— it is frequently not the root.

```
┌────────────────────────────────────────────────────┐
│  [ Attributes ]  [ Breakdown ]        ← tabs   (7)  │
├────────────────────────────────────────────────────┤
│  L1   SRD-423                                       │
│  ─────────────────────────────────────────────      │
│  Value of "Object Text", newlines kept               │
│                                                       │
│  ┃ VERIFICATION                                       │
│  ┃  Verification Method: <value>                      │
│                                                       │
│  ├─ L2  SRD-445  refines ▸                            │
│  ├─ L2  ‹prefix›-708  refines ▸                        │
│  └─ L2  ‹prefix›-710  refines ▾                        │
│       Value of "Object Text", newlines kept            │
│       ┃ VERIFICATION — no attribute flagged             │
│       ├─ L3  ‹prefix›-812  refines                       │
│       └─ L3  ‹prefix›-813  refines  ⚑ also refines ‹x› (L2)│
└────────────────────────────────────────────────────┘
```

Corrections carried over from your annotated mockup, both now part of the contract below:

- The relationship word is **"refines"**, not "flows into" — used consistently at every level
  (L1→root as much as L4→L3), not just L2.
- An item with nothing pointing at it says **"no incoming links"**, never "no upstream links" —
  matches the terminology already used for the References column's incoming caveat
  (`REQ_REVIEW.md` §5.1).

There is no Compliance box in this version — only Verification, sourced from the attribute(s)
already flagged `verification` in the existing `:__AttributeSetting` (`REQ_REVIEW.md` §9.2, §6).
No new flag, no new settings-dialog column.

---

## 2. Root-finding and the "refines" relationship

`refersTo` is untyped (`REQ_REVIEW.md` §5.1: *"All refersTo edges are untyped. Do not display or
imply satisfies/verifies/refines here"*). That rule is scoped to the **References column**, which
shows raw, undirected trace data. This tab is a different feature with a different job — showing
decomposition — and needs a direction. Per your decision, the rule here is deliberately simple:

> **Every outgoing `refersTo` edge from a requirement is read as "this requirement refines its
> target."** No system-level filtering, no attribute check. `A -[:refersTo]-> B` renders as
> `A refines B`, and B is drawn as A's parent in the tree.

This is a display convention for this tab only, not a claim about DOORS semantics — it reuses the
word "refines" from the Shape-C link vocabulary (R2) purely as the generic verb for "traces
upward toward," and must **not** be confused with an actual `:__Meta:__Link` with
`semantics: 'refines'`. Say so once, visibly, in the tab (a small info affordance next to the tab
label, same pattern as the References column's incoming-links caveat) so a reviewer never mistakes
a drawn "refines" for an authored one.

Consequence of taking every outgoing edge, accepted knowingly: a requirement's `refersTo` set may
include cross-references that are not really decomposition (e.g. "see also"). This tab will draw
those too. If that turns out to be noisy in practice, the fix is a later filter (§8, open
question), not a redesign now.

**Root** = the terminal node(s) reached by climbing outgoing `refersTo` edges from the selected
requirement until no further outgoing edge exists. Because a node can have more than one outgoing
edge, this climb is a traversal over a DAG, not a single path, and can terminate at more than one
node. See §3 for how that is handled.

**Level badge** (`L0`–`L4`) on each node comes from the existing system-level classification —
`(:DOORSModule)-[:__classifiedAs]->(:__Classification {scheme:'systemLevel', code:'L2', ...})`
(`CLAUDE.md` §2, Shape A) — resolved from the node's owning module. A module with no system level
set renders no badge, not `L?` or a blank chip; the row still renders, just without that one chip.

---

## 3. Multiple parents — the DAG-to-tree problem, and the proposed solution

Two distinct places this shows up, and they need different answers:

**A. The selected requirement's ancestor closure has more than one root.** Climbing from the
selected item, some path terminates at SRD-423 (L1) and another terminates at a different L1 (or
an L2 with nothing above it). This is not an error — a requirement legitimately tracing to two
independent top-level items happens.

→ **Render as a small forest, not one tree.** Show each root as its own top-level card, stacked
vertically, separated by a hairline (`--sec-line`, not a shadow — `CLAUDE.md` §8). Label each
"Root 1 of 2" / "Root 2 of 2" in the 10px uppercase eyebrow style only when there is more than
one; a single root gets no such label, matching today's single-item case exactly. The selected
requirement is highlighted inside **every** root's tree it is reachable from — it is one item, not
one item per tree.

**B. A non-root node has more than one parent** (two or more outgoing `refersTo` edges, each
landing on a different node that is itself included in the tree). Rendering it once under each
parent duplicates the whole subtree beneath it and can blow up combinatorially on a deep DAG.

→ **Render the node once, under one primary parent, and represent every other parent as a small
inline chip on that node's row rather than a repeated subtree:**

```
└─ L3  SRD-813  refines  ⚑ also refines SRD-501 (L2)
```

- **Primary parent** = the parent that lies on a path toward a root which is also an ancestor of
  the *originally selected* requirement, so the chain containing the reviewer's own item never
  gets relegated to a chip. If more than one candidate parent satisfies that (the node sits on
  multiple such paths), take the first one in the order the API returned its outgoing edges
  (stable, not re-sorted client-side).
- **The chip is a jump affordance, not a dead end.** Clicking it scrolls to (and briefly outlines)
  that node in its primary position if already rendered in another root's tree; if the target
  root/branch is currently collapsed, it expands the minimum path to reveal it. It never draws a
  second copy of the subtree.
- A node can carry more than one "also refines" chip; stack them, do not truncate silently.

This keeps the rendered structure a strict tree (one parent per row) while still surfacing every
edge the graph actually has — nothing is hidden, it is just not drawn twice.

**Cycle guard.** `refersTo` is not supposed to cycle, but nothing in the schema prevents it
(`CLAUDE.md` §7: Community enforces no such constraint). If climbing or descending revisits a node
already on the current path, stop that branch, mark the closing edge as `cyclic: true` in the API
response, and render it the same as an "also refines" chip with a warning tone instead of infinite
recursion.

---

## 4. Node card content

Every node in the tree — root, mid-level, leaf, and the selected node wherever it falls — renders
the same card shape, collapsed to just the ID/level/relationship row by default, expandable to the
full card. The root and the path to the selected node are expanded by default; every other branch
loads collapsed with a child count (`▸ 3 children`), so a wide tree does not dump every level
1–4 sub-item on screen at once. This is the practical answer to "don't overdo the indent" — depth
is mostly controlled by *not expanding* rather than by shrinking the rail.

| Element | Source | Notes |
|---|---|---|
| ID + level badge | `id`, resolved system level | Tabular figures, same as the review table |
| Relationship word | fixed string **"refines"** | Not per-node data — see §2 |
| Description | same derivation as the review table: heading → `objectNumber` + `Object Heading`; otherwise `Object Text` | Identical rule to `REQ_REVIEW.md` §5, reused rather than reinvented |
| **Verification** box | every attribute of this object's module already flagged `verification: true` in `:__AttributeSetting` (`REQ_REVIEW.md` §9.2) — no schema change needed | **All** flagged attributes, name + value, one per line — not just one. Empty state: *"No verification attribute defined yet for this requirement"* (matches your annotated wording), rendered quietly, not in error red — this is an absence of configuration, not a finding |
| Children | recursive, per §2–§3 | Collapsed by default beyond the selected-node path |

The box is omitted entirely (no empty box, no header) when the module has zero attributes flagged
`verification` *and* it isn't the node currently expanded-by-default — i.e. a collapsed sibling
never pre-renders its box; expanding it is what triggers showing "no verification attribute…" if
that turns out to be the case.

---

## 5. Visual design

**Reuse `_document.scss` — do not invent a parallel vocabulary.** `CLAUDE.md` §6 already flags
this file: *"depth rails, object cards, verification and extended-attribute panels… the specified
look for the review and tree views"* — written for exactly this feature and unused until now. Pull
the tree's rail, card and panel treatment from there before writing any new mixin; only add to it
if this view needs a shape it genuinely does not have yet (document that addition in the same
file, per its own convention).

- **Depth is a rail, not indentation growth.** A thin vertical line (`--sec-line`) runs down the
  left edge of each level, one step further right per depth, same weight throughout L1→L4. This
  is what keeps four levels legible in a side-panel-width column — indentation alone would not
  survive to L4 at this width, which is exactly the risk flagged in your note.
- **Level badge** (`L1`…`L4`) uses the existing `--sec-level-0`…`--sec-level-4` sequential scale
  (`CLAUDE.md` §8) — the same chip already used for system-level classification elsewhere, not a
  new colour.
- **Verification box** is the "extended-attribute panel" shape from `_document.scss`: a rule on
  the left edge, near-white wash, never a saturated fill — consistent with §8's "colour is a rail
  or a rule" rule and its Tier-2 exception. Because the box surfaces a `:__AttributeSetting`-flagged
  attribute value, the left rule is the Tier-2 accent `#0077C8`, matching how a dirty comment is
  marked in the review table (§5.2) — a reviewer sees the same colour meaning "this is
  app-configured, not raw DOORS content" in both places.
- **The selected requirement's highlight reuses the sidenav's active-item language**
  (`CLAUDE.md` §9): a 3px left rule in `--sec-blue-mid` plus a pale background wash, square
  corners, never a filled pill. No new hue is introduced — the brief's "one highlight per view"
  rule is satisfied by borrowing a treatment the app already uses for "you are here," which is
  precisely what this marks.
- **"Also refines" chips** are quiet, outline-only, `--sec-ink-3` text — informational, not a
  finding and not Tier-2 data in themselves (the link is real DOORS `refersTo`), so they get
  neither error red nor the Tier-2 blue.
- Sentence case, tabular figures for IDs, no italics — same typography rules as everywhere else
  (`CLAUDE.md` §8).

---

## 6. API

One new endpoint. It must do the DAG traversal server-side — assembling it from N calls to the
existing `/traces` endpoint client-side would mean an unbounded number of round trips for a tree
that can legitimately be dozens of nodes.

```
GET /api/v1/items/{ref}/breakdown?maxDepth=6&maxNodes=200
```

- `{ref}` — the requirement the reviewer clicked (base64url `__id`, same as every other route
  param, R5).
- `maxDepth` / `maxNodes` — safety bounds, not user-facing controls in v1. Neo4j Community has no
  query governor (`CLAUDE.md` §7); this endpoint is the one place a single click can trigger an
  unbounded graph walk, so both bounds are mandatory, with sane defaults server-side even if the
  client omits them.
- Traversal covers **both directions** from the selected item in one query: upward (outgoing
  `refersTo`, repeated, to find every root) and downward (incoming `refersTo`, repeated, to find
  every descendant of every one of those roots) — the response is the full forest, not just the
  selected item's immediate neighbourhood, so the panel never needs a second request to expand a
  sibling branch.

Response shape — flat node/edge lists, DAG structure intact; the client applies the primary-parent
rule from §3 when it renders, not the server, because "which chain contains the selected item" is
a rendering concern the client already knows without a second call:

```json
{
  "selectedRef": "ZG9vcnM6…423",
  "roots": ["ZG9vcnM6…423"],
  "truncated": false,
  "nodes": [
    {
      "ref": "ZG9vcnM6…423",
      "id": "SRD-423",
      "level": "L1",
      "description": "…",
      "verificationAttributes": [{ "name": "Verification Method", "value": "…" }]
    }
  ],
  "edges": [
    { "from": "ZG9vcnM6…445", "to": "ZG9vcnM6…423", "cyclic": false }
  ]
}
```

`from` refines `to` (§2). `truncated: true` when `maxNodes` was hit mid-traversal — the panel
shows a footer ("Tree truncated at 200 items") rather than silently presenting a partial forest as
complete.

This is a **read-only, computed-on-request** endpoint — nothing here is stored (same rule as the
Issues column, `REQ_REVIEW.md` §5.3: a stored derivation goes stale the moment a new import or a
settings change alters the underlying data). `verificationAttributes` is resolved per node from
that node's own module's `:__AttributeSetting` flags, evaluated fresh every call — a module
reconfigured between two clicks answers correctly on the second click with no migration.

---

## 7. Interaction

- Opening the Breakdown tab triggers the request; the Attributes tab (already built) is unaffected
  and keeps its current data source.
- Loading state inside the tab only — the rest of the detail panel (module link, etc., §7 of
  `REQ_REVIEW.md`) does not block.
- Switching modules or closing the panel behaves exactly as today (`REQ_REVIEW.md` §7) — this tab
  adds no new dirty state, no new save path; it is pure read.
- Clicking a ref inside the tree (not a chip) does the same thing clicking a References-column id
  already does: opens that item in the detail panel. If that item is itself in the same module,
  staying on the Breakdown tab and just re-rooting the highlight is preferable to losing the tree
  the reviewer was just reading — but re-opening the Attributes tab as today's References click
  already does is an acceptable fallback for v1; do not block the rest of this feature on that
  refinement (see §8).
- An `__UNDEFINED` node in the closure renders exactly as it does in the References column — muted,
  non-clickable, "Not yet imported (module ‹name›)," accent `#FE5000` — and is a legitimate leaf:
  its own further ancestry/descent is simply unknown, not queried.

---

## 8. Open questions

- ~~**Does clicking a node inside the tree re-root the tree in place, or navigate to the
  Attributes tab for that item?**~~ **Settled: neither — nothing in the tree is clickable.** Both
  options in §7 assumed following a node was worth doing; in use it is not, because following one
  replaces the tree the reviewer is reading and the tree is the point of the tab. Every row instead
  shows its statement and its verification attributes where it sits, so there is nothing left to go
  and look at. The only control on a row is its twisty.
- **Should "every outgoing refersTo counts as refines" be revisited if it proves noisy** (§2) —
  e.g. requirements that reference unrelated items acquire phantom parents in this tree. If that
  turns out to matter in practice, the fix is scoping by relationship target type or reviving the
  system-level filter considered and set aside during this spec's design, not a rewrite of the
  tree/chip mechanics above. **Still open**, and the reference data does not settle it: every
  Segment→SRD link in it reads plausibly as decomposition, but the export is sanitised, so that is
  weak evidence.
- **Should `maxDepth` / `maxNodes` ever become user-visible** (a "load more" on a truncated tree)
  — v1 treats truncation as a rare safety net, not a paging control. **Still open.** Nothing in the
  reference data comes close to either bound (the widest tree observed is 12 nodes against a
  default of 200), so there is no evidence yet that it needs to be.

---

## 9. Acceptance criteria

1. Opening the Breakdown tab for any requirement shows every root it traces up to (via repeated
   outgoing `refersTo`) and the full decomposition down from each root, to whatever depth the data
   has, up to `maxDepth`.
2. The requirement the reviewer actually clicked is visually distinguished (§5) in every root's
   tree it appears in, even when it is not itself a root.
3. A node with more than one outgoing `refersTo` renders once, under one primary parent, with
   every other parent shown as an "also refines" chip (§3) — never as a duplicated subtree.
4. A node with no incoming `refersTo` in the closure shows "no incoming links," never "no upstream
   links."
5. Every relationship in the tree renders as "refines," never "flows into."
6. The Verification box lists **every** attribute flagged `verification` for that node's module —
   not just one.
7. A module with no attribute flagged `verification` omits the box's content and shows the "no
   verification attribute defined yet" message, quietly, not in error red.
8. A cyclic `refersTo` chain (however unlikely) does not hang or crash the panel — it renders as a
   marked, non-recursing chip.
9. No `__`-prefixed string reaches the template — `sec/no-internal-namespace` passes (R5), same
   as every other view.
10. The traversal query is bounded by `maxDepth` and `maxNodes` server-side and cannot be made to
    run unbounded by any client input (`CLAUDE.md` §7).
11. No changes to `:__AttributeSetting`'s schema, the settings dialog, or the alias map — this
    feature reads existing Tier-2 data only.
12. Graph behaviour covered by a Testcontainers test against Neo4j **Community**, tagged `docker`
    (`CLAUDE.md` §11), including a fixture with a genuine multi-parent and a genuine multi-root
    case.

---

## 10. What the implementation decided, where this spec left a choice

### 10.1 §3A and §3B contradict each other; §3A won, after §3B was tried and rejected in use

§3A says the selected requirement "is highlighted inside **every** root's tree it is reachable
from." §3B says a multi-parent node "renders once, under one primary parent." When the selected
item is itself the multi-parent node — which is the common case, and the one the reference data
produces — those two cannot both hold: SEG-REQ-1247 refines SRD-1158 and SRD-1411, which sit under
different roots, so drawing it under every root it is reachable from *is* drawing it twice.

§3B shipped first, on the argument that it was the rule with a stated failure mode behind it. **It
was wrong in use**, and the way it was wrong is the argument against it: opening SEG-REQ-1247 gave
a Root 2 that did not contain SEG-REQ-1247. A reviewer reading tree 2 sees a decomposition the
requirement is genuinely part of, with the requirement absent from it, and there is no wording that
fixes that — a chip on a row in *tree 1* cannot tell someone reading tree 2 what is missing.

**A requirement is therefore drawn under every parent it refines.** What §3B was protecting against
is real, and is handled directly rather than by not drawing:

- **a node never appears twice on one root-to-node path**, which stops a cycle dead and is stricter
  than the server's own cycle marking;
- **the forest stops at 500 rendered rows** and says so, which stops a dense DAG;
- **each row names the parent it refines** (§10.6), so two copies are never ambiguous.

The feared blow-up does not happen on real data. Measured over 250 objects of the reference Segment
module, the widest breakdown draws **40 rows over 31 nodes**; nothing came within an order of
magnitude of the cap. Note the cascade this implies and accept it: a *parent* with two parents takes
its whole subtree into both trees, so the copy count multiplies down a chain rather than just at the
node the reviewer opened. That is the honest rendering of the graph — each of those positions is a
real place in a real decomposition.

### 10.6 Every row names what it refines, and nothing in the tree is a link

Two consequences of 10.1, and one is what makes it readable. "refines" alone was ambiguous the
moment a requirement could appear twice, so each row reads **`refines SRD-1158`** — the parent's
own id, so two copies of one requirement tell each other apart while scrolling. A root refines
nothing and gets no line; a row whose parent is a placeholder says so in words rather than showing
a name it does not have (R5).

And **no row is clickable.** Following a node replaces the tree the reviewer is reading, which is
the one thing this tab exists to hold still. Every row instead shows its statement and its
verification attributes where it sits, expanded by default and collapsible — so there is nothing
left to navigate to. The twisty is the only control on a row, and the `itemSelected` output that
carried the re-rooting is gone rather than left dangling.

### 10.2 The traversal is a loop in Kotlin, not one variable-length pattern

Neo4j will not take a parameter as a variable-length bound (`*1..$maxDepth` is a syntax error), so
a single-statement version has to bake a literal bound in — and then `maxDepth=2` costs the same as
`maxDepth=12`, which is not the guarantee §9 criterion 10 asks for. One query per level makes both
bounds real and makes truncation exact. The cost is up to `2 × maxDepth` round trips, each an index
seek; against the reference module a 12-node tree answers in well under the transaction timeout.

### 10.3 `description` is derived server-side, unlike in the review table

§6 puts `description` in the response and that is right, but it does mean the Description rule now
has two implementations — `BreakdownProjection.describe()` in Kotlin and `describe()` in
`review-table.model.ts`. They cannot share code across the language boundary. The alternative was
shipping each node's whole attribute bag (78 attributes on the reference module) to let the client
apply the rule for two strings. Both are commented as pointing at each other; if one changes, so
must the other.

### 10.4 A bug the specs did not catch, and now do

Pointing the panel at another object silently reset it to the Attributes tab. The tab group lived
inside an `@if` on the detail resource, so the change unmounted it for the moment the resource was
loading, and it came back with tab one selected. Found by clicking around in a browser, not by a
test. The fix moves the tab group outside every `@if` and lets each tab handle its own loading;
`item-detail-panel.spec.ts` now asserts the tab survives, **with the assertion made while both
requests are still in flight**, which is the window the bug lived in.

This mattered more when the tree could re-root itself (10.6 removed that), but it still does: the
review table beneath can point the panel at a different object at any time, and doing so must not
throw away the tab the reviewer chose.

### 10.5 `_document.scss` gained one parameter

`verification-panel` now takes an `$accent`, defaulting to the verified green it already used. The
Breakdown tab passes the Tier-2 blue, because what its box surfaces is an attribute a user flagged
in the settings dialog rather than a fact DOORS asserted (§5). That is one parameter on an existing
mixin, not a parallel vocabulary — the rest of the tab's look (depth rails, tree cards, the
disclosure twisty, the document footer) is `_document.scss` as written, used for the first time.

`styles/_mixins.scss` also gained `system-level-scale`, the five `.sec-level--L*` rules, which the
Modules table's level cell had been carrying privately. Second use, so it moved.

### 10.7 Three things the mockup could not have shown

Each of these only became visible with the real, sanitised export in front of it.

**The detail panel now leads with the object id.** It was headed by `__name`, which for a
requirement is its `Object Text` — and a sanitised export blanks user attributes, so every object
in the reference module is headed "Example of a Requirement Text". There was no way to tell which
requirement was open. `ItemDetailDto` gained an `id` (display only, never a key — R6; null for a
placeholder, whose internal name is its internal id spelled out — R5), the id is the heading, and
the name is a clamped second line. An object with no id of its own keeps the name as its heading
and shows no second line rather than repeating it.

**The subject row is marked in words, not only in colour.** It first wore the sidenav's active-item
language, a `--sec-blue-mid` rule over `--sec-wash-cool`. That marks one row in a short list of
unlike things; here it has to mark one card among a dozen cards that look exactly like it, drawn
more than once, at no predictable place in the forest. It now carries a navy rail, the
`--sec-subject` wash (the fourth background exception, `CLAUDE.md` §8) and a chip reading **"The
requirement you opened"**. Three signals, none load-bearing alone.

**An unset system level keeps its square.** Rendering no badge shifted every id in the column left
by the badge's width, which reads as a rendering fault rather than as an absence. The badge is
always drawn; with no level it is the empty outlined `.sec-level--none` treatment the Modules table
already used, and it says *No system level set for this module* on hover. That rule moved into
`system-level-scale` in `_mixins.scss` on its second use, along with the five colour stops.
