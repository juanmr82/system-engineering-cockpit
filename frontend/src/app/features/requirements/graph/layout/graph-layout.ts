import type { GraphEdge, GraphNode, LevelBand } from '../graph.model';

/**
 * The dependency graph's layout maths (docs/REQ_BREAKDOWN_GRAPH_VIEW §4).
 *
 * **Everything in this file is a pure function.** No DOM, no ELK types beyond the minimal
 * `{ id, x, y, width, height }` shape, no signals. That is what makes the interesting half of the
 * layout — dense partition renumbering, sub-lane compression, bend-point remapping, cycle-aware
 * ranking — testable without a canvas, which jsdom does not have and a screen reader cannot read
 * (§7).
 */

// The card is a fixed width and a measured height (§5.2). Variable widths make a layered layout
// ragged and double the cost of the measure pass; a fixed one also keeps the wrapping identical to
// the breakdown row, which is the point of sharing the component at all.
export const CARD_WIDTH = 260;

/** What a card falls back to before it has been measured — never what it is drawn at. */
export const CARD_FALLBACK_HEIGHT = 96;

/** The vertical step between two sub-lanes *inside* one level band (§4.3). */
export const SUBLANE_GAP = 28;

/** The vertical step between two level bands. Deliberately several times [SUBLANE_GAP]. */
export const LEVEL_GAP = 120;

/** Breathing room inside a band's drawn rectangle, above and below its content. */
export const BAND_PADDING = 18;

export interface LayoutBox {
  readonly id: string;
  readonly x: number;
  readonly y: number;
  readonly width: number;
  readonly height: number;
}

export interface Point {
  readonly x: number;
  readonly y: number;
}

export interface LayoutEdge {
  readonly id: string;
  readonly source: string;
  readonly target: string;
  /** The route, source end first. Empty means "no route yet" — the local router fills it in. */
  readonly points: readonly Point[];
}

export interface BandBox {
  readonly level: number | null;
  readonly label: string;
  readonly top: number;
  readonly bottom: number;
}

export interface CompressedLayout {
  readonly nodes: LayoutBox[];
  readonly edges: LayoutEdge[];
  readonly bands: BandBox[];
  readonly width: number;
  readonly height: number;
}

export interface BandOptions {
  readonly sublaneGap?: number;
  readonly levelGap?: number;
  readonly bandPadding?: number;
}

// ---------------------------------------------------------------------------------------------
// Partitions
// ---------------------------------------------------------------------------------------------

/**
 * The dense, ordered partition index of each level (§4.2).
 *
 * **ELK requires contiguous partition numbers**, so a project whose levels are 1, 2 and 4 must
 * produce partitions 0, 1, 2 and never an empty partition 3 — an empty partition is a lane with a
 * label and nothing in it, which reads as a rendering fault rather than as an absence.
 *
 * The unplaced level (`null`) is always last, whatever order it arrived in. Nodes the strategy
 * could not place get their own band at the bottom and are never folded into a real level (§4.1):
 * a requirement in the wrong band is a wrong statement about the system, made silently.
 */
export function partitionIndex(bands: readonly LevelBand[]): ReadonlyMap<number | null, number> {
  const levels = [...new Set(bands.map((band) => band.level))];
  const placed = levels.filter((level): level is number => level !== null).sort((a, b) => a - b);
  const ordered: (number | null)[] = levels.includes(null) ? [...placed, null] : placed;

  return new Map(ordered.map((level, index) => [level, index]));
}

/**
 * Which partition a node belongs to.
 *
 * A level the band list does not mention lands in the last partition rather than throwing. The
 * server builds both lists from the same pass so it cannot happen — and if it ever does, a node
 * drawn in the bottom band is recoverable where a blank canvas is not.
 */
export function partitionOf(node: GraphNode, index: ReadonlyMap<number | null, number>): number {
  const known = index.get(node.level);
  return known ?? Math.max(0, index.size - 1);
}

/** Every node's partition, keyed by the card's ref. */
export function partitionMap(
  nodes: readonly GraphNode[],
  bands: readonly LevelBand[],
): ReadonlyMap<string, number> {
  const index = partitionIndex(bands);
  return new Map(nodes.map((node) => [node.card.ref, partitionOf(node, index)]));
}

/** The bands in partition order, unplaced last — the order [partitionIndex] numbered them in. */
export function orderedBands(bands: readonly LevelBand[]): LevelBand[] {
  const index = partitionIndex(bands);
  const byLevel = new Map(bands.map((band) => [band.level, band]));
  return [...index.keys()]
    .map((level) => byLevel.get(level))
    .filter((band): band is LevelBand => band !== undefined);
}

// ---------------------------------------------------------------------------------------------
// Band compression
// ---------------------------------------------------------------------------------------------

/**
 * Squeezes ELK's layers into level bands, so a level reads as a band and same-level refinement
 * reads as a small step down inside one (§4.3).
 *
 * Partitioning alone gets us most of the way: ELK is free to create several layers inside one
 * partition, so an intra-level edge already places its target a layer lower without leaving the
 * partition. What it cannot give us is the *visual* distinction between "one step down inside a
 * level" and "down to the next level", because layer spacing is a single global option. This pass
 * supplies it: sub-lanes inside a band are pulled to [sublaneGap], bands are pushed apart by
 * [levelGap].
 *
 * **One deviation from the spec's sketch, and it matters.** §4.3 places sub-lane *k* at
 * `bandTop + k * SUBLANE_GAP`, which spaces sub-lane *tops* 28px apart — and a requirement card is
 * around a hundred pixels tall, so every sub-lane would land on top of the one above it. Each
 * sub-lane is placed below the previous one's *bottom* instead, with [sublaneGap] between them, so
 * the gap is what is compressed rather than the cards being stacked on each other.
 *
 * `x` is ELK's, untouched. Only `y` is remapped, and **edge bend points are remapped by the
 * identical piecewise-linear function**, so a route still meets the card it was routed to.
 */
export function compressBands(
  nodes: readonly LayoutBox[],
  edges: readonly LayoutEdge[],
  partition: ReadonlyMap<string, number>,
  bands: readonly LevelBand[],
  options: BandOptions = {},
): CompressedLayout {
  const sublaneGap = options.sublaneGap ?? SUBLANE_GAP;
  const levelGap = options.levelGap ?? LEVEL_GAP;
  const padding = options.bandPadding ?? BAND_PADDING;

  if (nodes.length === 0) {
    return { nodes: [], edges: edges.map(withPoints), bands: [], width: 0, height: 0 };
  }

  const lastPartition = Math.max(0, bands.length - 1);
  const grouped = new Map<number, LayoutBox[]>();
  for (const node of nodes) {
    const index = partition.get(node.id) ?? lastPartition;
    const bucket = grouped.get(index);
    if (bucket) {
      bucket.push(node);
    } else {
      grouped.set(index, [node]);
    }
  }

  // The mapping is built as breakpoints rather than as a per-node offset so that a bend point
  // *between* two sub-lanes lands somewhere sensible instead of jumping to one of them. Slope 1
  // inside a lane — heights are never scaled — and whatever the gap needs between lanes.
  const breakpoints: { from: number; to: number }[] = [];
  const bandBoxes: BandBox[] = [];
  const movedNodes: LayoutBox[] = [];
  const ordered = orderedBands(bands);

  let cursor = 0;
  for (const [index, band] of ordered.entries()) {
    const members = grouped.get(index);
    if (!members || members.length === 0) {
      // A band the picture has nothing in. Skipped rather than drawn empty — the server sends only
      // occupied levels, so this is a guard, and an empty lane reads as a fault.
      continue;
    }

    const bandTop = cursor + padding;
    let laneTop = bandTop;

    for (const lane of sublanes(members)) {
      breakpoints.push({ from: lane.top, to: laneTop });
      breakpoints.push({ from: lane.bottom, to: laneTop + (lane.bottom - lane.top) });

      const shift = laneTop - lane.top;
      for (const node of lane.members) {
        movedNodes.push({ ...node, y: node.y + shift });
      }

      laneTop += lane.bottom - lane.top + sublaneGap;
    }

    // laneTop has one trailing sublaneGap on it; the band ends where its last lane does.
    const bandBottom = laneTop - sublaneGap + padding;
    bandBoxes.push({ level: band.level, label: band.label, top: cursor, bottom: bandBottom });
    cursor = bandBottom + levelGap;
  }

  const remap = piecewise(breakpoints);
  const movedEdges = edges.map((edge) => ({
    ...edge,
    points: edge.points.map((point) => ({ x: point.x, y: remap(point.y) })),
  }));

  const width = Math.max(...movedNodes.map((node) => node.x + node.width), 0);
  const height = bandBoxes.length > 0 ? bandBoxes[bandBoxes.length - 1].bottom : 0;

  return { nodes: movedNodes, edges: movedEdges, bands: bandBoxes, width, height };
}

interface Sublane {
  readonly top: number;
  readonly bottom: number;
  readonly members: LayoutBox[];
}

/**
 * A partition's nodes grouped into sub-lanes: one per distinct row ELK put them in.
 *
 * Rows that overlap vertically are merged, so the breakpoint list stays strictly increasing and the
 * remapping stays a function. ELK sizes a layer to its tallest node and does not overlap layers, so
 * a merge only happens on input this code did not produce — and a monotonic mapping is what the
 * bend-point remapping depends on.
 */
function sublanes(members: readonly LayoutBox[]): Sublane[] {
  const sorted = [...members].sort((a, b) => a.y - b.y || a.x - b.x || compare(a.id, b.id));
  const lanes: { top: number; bottom: number; members: LayoutBox[] }[] = [];

  for (const node of sorted) {
    const bottom = node.y + node.height;
    const current = lanes[lanes.length - 1];
    if (current && node.y < current.bottom) {
      current.bottom = Math.max(current.bottom, bottom);
      current.members.push(node);
    } else {
      lanes.push({ top: node.y, bottom, members: [node] });
    }
  }

  return lanes;
}

/**
 * A monotone piecewise-linear map through the given breakpoints, identity outside their range.
 *
 * This is the one function both nodes and bend points go through, which is what §4.3 means by "the
 * identical function": a bend point sitting exactly on a card's top edge lands exactly on its new
 * top edge, so routing stays consistent with the thing it routes to.
 */
function piecewise(breakpoints: readonly { from: number; to: number }[]): (y: number) => number {
  const points = [...breakpoints].sort((a, b) => a.from - b.from);
  // Duplicate `from` values would make the map ambiguous; the last one wins, which matches the
  // order the bands were laid out in.
  const unique: { from: number; to: number }[] = [];
  for (const point of points) {
    const last = unique[unique.length - 1];
    if (last && last.from === point.from) {
      unique[unique.length - 1] = point;
    } else {
      unique.push(point);
    }
  }

  if (unique.length === 0) {
    return (y) => y;
  }
  if (unique.length === 1) {
    const shift = unique[0].to - unique[0].from;
    return (y) => y + shift;
  }

  return (y) => {
    if (y <= unique[0].from) {
      return y + (unique[0].to - unique[0].from);
    }
    const last = unique[unique.length - 1];
    if (y >= last.from) {
      return y + (last.to - last.from);
    }
    // Small arrays — at most two breakpoints per sub-lane — so a linear scan beats a binary search
    // and reads as what it is.
    for (let i = 1; i < unique.length; i++) {
      const a = unique[i - 1];
      const b = unique[i];
      if (y <= b.from) {
        const span = b.from - a.from;
        const t = span === 0 ? 0 : (y - a.from) / span;
        return a.to + t * (b.to - a.to);
      }
    }
    return y;
  };
}

function withPoints(edge: LayoutEdge): LayoutEdge {
  return { ...edge, points: [...edge.points] };
}

// ---------------------------------------------------------------------------------------------
// Edges
// ---------------------------------------------------------------------------------------------

/**
 * Collapses duplicate `(source, target)` pairs to one edge, and **keeps self-loops** (§4.5).
 *
 * Parallel duplicates are `MERGE`d at import so they should not exist, but the importer spec says
 * not to rely on that — and two identical arrows are indistinguishable from one anyway. A
 * self-loop is the opposite case: it is almost always an authoring error and is worth seeing, so
 * it is never dropped.
 */
export function dedupeEdges(edges: readonly GraphEdge[]): GraphEdge[] {
  const seen = new Set<string>();
  const kept: GraphEdge[] = [];
  for (const edge of edges) {
    const key = `${edge.source} ${edge.target}`;
    if (seen.has(key)) {
      continue;
    }
    seen.add(key);
    kept.push(edge);
  }
  return kept;
}

/** An edge whose two ends are the same node. Drawn as a small loop, never dropped. */
export function isSelfLoop(edge: GraphEdge): boolean {
  return edge.source === edge.target;
}

/**
 * An edge the layout had to fight: its target did not end up above its source.
 *
 * `refersTo` is read as "refines", so an arrow should point from a card up to the card it refines.
 * A cycle makes that impossible for at least one edge of it, and ELK breaks cycles by reversing
 * edges internally. **The rendered arrowhead still shows the true data direction** (§8) — this
 * flag only changes the *stroke*, to dashed, so a reader can see the layout could not honour the
 * data rather than being quietly shown an arrow that means the opposite of what it looks like.
 */
export function isFeedbackEdge(
  edge: GraphEdge,
  positions: ReadonlyMap<string, LayoutBox>,
): boolean {
  if (isSelfLoop(edge)) {
    return false;
  }
  const source = positions.get(edge.source);
  const target = positions.get(edge.target);
  if (!source || !target) {
    return false;
  }
  return target.y >= source.y;
}

// ---------------------------------------------------------------------------------------------
// Deterministic input
// ---------------------------------------------------------------------------------------------

/**
 * Nodes and edges in a fixed order, so reopening the dialog on the same scope gives the same
 * picture (§4.6).
 *
 * The server already returns both in `__sortKey` order, and this does not second-guess it — it
 * sorts by *the order the response listed the nodes in*, which is that sort key without the client
 * ever seeing an internal name (R5). A layout that reshuffles on every open destroys the user's
 * spatial memory and makes a screenshot in review minutes worthless.
 */
export function orderEdges(edges: readonly GraphEdge[], nodes: readonly GraphNode[]): GraphEdge[] {
  const rank = new Map(nodes.map((node, index) => [node.card.ref, index]));
  const of = (ref: string) => rank.get(ref) ?? Number.MAX_SAFE_INTEGER;
  return [...edges].sort(
    (a, b) => of(a.source) - of(b.source) || of(a.target) - of(b.target) || compare(a.target, b.target),
  );
}

function compare(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}
