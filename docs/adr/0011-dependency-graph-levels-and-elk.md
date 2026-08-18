# ADR 0011: The dependency graph's level axis, and elkjs for its layout

Status: accepted
Date: 2026-08-09

## Context

`docs/REQ_BREAKDOWN_GRAPH_VIEW.md` specifies a node-and-edge view of `refersTo`, opened from the
Breakdown tab. Its central design idea is that **the vertical axis is the level axis**: a
requirement's y position says which system level it belongs to, and a small step down inside a band
says "still this level, but refined by the thing above it".

That makes "what is a level" the load-bearing decision of the whole feature, and the spec flags it
as an open question (§9.2). Two dependency decisions follow from it and are recorded here too,
because neither is in `CLAUDE.md` §4.

### The level axis

The spec's default strategy, `MODULE_SE_LEVEL`, parses a level out of the owning module's
`moduleFullPath` with a configurable regex — `/XXX-/Level 1 - System/SRD` → 1 — and adds a manual
per-module override, persisted as a new `:__Meta` settings node, for paths that do not match.

The spec says, in as many words, that the regex is "currently a guess from a single example path".

It was also written before this product had a real notion of system level. It has one now:

- `:__Classification` with `scheme: systemLevel`, carrying the closed L0–L4 vocabulary;
- set by a human, in the Modules settings dialog, per module;
- already resolved onto every requirement card by `RequirementCardProjection`;
- already the source of the level badge in the Breakdown tab;
- already the source of the `--sec-level-0` … `--sec-level-4` colour ramp, which `CLAUDE.md` §8
  carves out a deliberate exception for.

So the regex strategy would not have been a *new* answer to "what level is this requirement at". It
would have been a **second** one, differently numbered, on the same screen as the first — a card
showing an `L2` badge sitting inside a band labelled `Level 1`. The manual-override meta node it
needs is also, exactly, the Modules settings dialog that already exists.

### The layout engine

A layered layout with externally imposed layers is not a thing to hand-roll: longest-path layering
is easy, crossing minimisation and orthogonal edge routing are not. `elkjs` supports imposed layers
through partitioning, which is precisely the constraint here. `dagre` has no imposed-layer mechanism
and is unmaintained; `d3-hierarchy` lays out trees, and this is a DAG with cycles.

## Decision

**1. The default level strategy is the module's `:__Classification` system level**, named
`MODULE_SYSTEM_LEVEL`. `OUTLINE_LEVEL` (the object's own `objectLevel`) and `GRAPH_RANK` (longest
path down the `refersTo` DAG, over the returned subgraph) are offered beside it in the dialog's
overflow menu, which is the pluggable-strategy shape the spec asks for. The regex strategy is not
implemented.

A module nobody has classified is **unknown**, never level 0, and unknown nodes get their own
explicit band at the bottom labelled *No system level set*.

**2. `elkjs` is added, pinned exactly at 0.11.0** (EPL-2.0), loaded only inside the layout Web
Worker so it never enters the initial bundle. `CLAUDE.md` §4 gains a row.

**3. Direction is named by the relation, never by "upstream" and "downstream".** The spec calls the
outgoing direction `DOWNSTREAM`; in this product an outgoing `refersTo` is read as *this requirement
refines its target*, so following it goes **up** the decomposition. The enum is `OUTGOING` /
`INCOMING` / `BOTH` and the user sees *What these refine* / *What refines these* / *Both directions*.

**4. Edges are handed to ELK reversed, and the arrowhead is never reversed to match.** Bands run
top-down from L0, so an arrow that means "refines" runs upward — against the layer flow. Feeding ELK
the data direction would make it reverse almost every edge internally and report it as feedback,
turning the whole picture dashed. ELK is given parent → child; the route is walked back the other
way before it is drawn. An edge whose target genuinely did not end up above its source — a cycle —
is drawn dashed, and its arrowhead still points the way the data does.

## Consequences

**Easier.** There is one answer to "what system level is this", set in one place, shown identically
on the badge and on the band. Adding the graph needed no new configuration key, no regex to tune per
project, and no new `:__Meta` kind — which means no new write path, no schema version to migrate,
and nothing for `MATCH (m:__Meta) DETACH DELETE m` to have missed.

**Harder.** A project that has not classified its modules gets one band. That is honest — nobody has
said what the levels are — but it is a worse first impression than a regex that happens to match.
`GRAPH_RANK` is the answer for that case and it is one menu item away; it always places every node,
because it is a property of the picture rather than of the data.

**Foreclosed, deliberately.** Deriving a level from a folder-path convention. If a project turns out
to need it, it belongs as a *bulk-classify* action in the Modules view — writing the same
`:__Classification` every other feature reads — and not as a fourth strategy that answers the same
question differently at render time.

**Accepted risk on elkjs.** It is a compiled Java-to-JavaScript bundle, 1.4 MB, and effectively
unreadable if it misbehaves. That is contained by keeping everything decision-shaped *outside* it:
`partitionOf`, `compressBands`, the bend-point remapping, the edge dedup, the feedback detection and
the local router are all pure functions in `layout/`, unit-tested with no ELK types in sight. ELK
supplies coordinates; it does not supply meaning. If it ever has to be replaced, the pure half — and
its tests — survive.
