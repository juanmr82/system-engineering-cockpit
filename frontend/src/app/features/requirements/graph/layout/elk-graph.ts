import type { ElkExtendedEdge, ElkNode } from 'elkjs/lib/elk-api';
import type { LayoutBox, LayoutEdge, Point } from './graph-layout';
import { CARD_WIDTH } from './graph-layout';

/**
 * Translating between this view's vocabulary and ELK's (docs/REQ_BREAKDOWN_GRAPH_VIEW.md §4.2).
 *
 * Pure, and kept apart from the worker so it can be tested without one: the worker is three lines
 * of message plumbing around `elk.layout()`, and everything worth asserting is here.
 */

/** What the worker is asked to lay out. Structured-cloneable — it crosses a thread boundary. */
export interface LayoutRequest {
  readonly nodes: readonly LayoutNodeSpec[];
  readonly edges: readonly LayoutEdgeSpec[];
}

export interface LayoutNodeSpec {
  readonly id: string;
  readonly height: number;
  /** Dense, contiguous, top band first. ELK rejects gaps — see `partitionIndex`. */
  readonly partition: number;
}

export interface LayoutEdgeSpec {
  readonly id: string;
  readonly source: string;
  readonly target: string;
}

export interface LayoutResult {
  readonly nodes: LayoutBox[];
  readonly edges: LayoutEdge[];
}

/**
 * ELK's options for a layered, partitioned, top-to-bottom layout.
 *
 * `randomSeed` is what makes reopening the dialog on the same scope give the same picture (§4.6).
 * The rest is §4.2's list, with the two spacings deliberately small: `compressBands` re-spaces
 * everything vertically afterwards, so ELK's own gaps only have to be big enough to keep its
 * routing sane.
 */
export const ELK_LAYOUT_OPTIONS: Record<string, string> = {
  'elk.algorithm': 'layered',
  'elk.direction': 'DOWN',
  'elk.partitioning.activate': 'true',
  'elk.layered.spacing.nodeNodeBetweenLayers': '48',
  'elk.spacing.nodeNode': '32',
  'elk.layered.crossingMinimization.strategy': 'LAYER_SWEEP',
  'elk.layered.nodePlacement.strategy': 'BRANDES_KOEPF',
  'elk.edgeRouting': 'ORTHOGONAL',
  'elk.randomSeed': '1',
};

/**
 * The ELK graph for one picture.
 *
 * **Edges are handed to ELK reversed, and this is the subtle part.** `refersTo` is read as
 * "refines", so an arrow runs from a requirement *up* to the requirement it refines — while the
 * bands run top-down from L0. Feeding ELK the data direction would make almost every edge point
 * against its own layer flow, and it would reverse them internally and report them as feedback,
 * turning the whole picture dashed. So ELK is given parent → child, which flows with the layering,
 * and {@link readElkResult} reverses each route back before it is drawn. **The arrowhead is never
 * reversed** (§8): only the order the polyline is walked in changes.
 *
 * Self-loops are left out entirely. ELK routes them, badly, and §4.5 wants them as a small loop on
 * the node's own right edge — which the renderer draws itself, from the node's box.
 */
export function buildElkGraph(request: LayoutRequest): ElkNode {
  const known = new Set(request.nodes.map((node) => node.id));

  return {
    id: 'root',
    layoutOptions: ELK_LAYOUT_OPTIONS,
    children: request.nodes.map((node) => ({
      id: node.id,
      width: CARD_WIDTH,
      height: node.height,
      layoutOptions: { 'elk.partitioning.partition': String(node.partition) },
    })),
    edges: request.edges
      .filter((edge) => edge.source !== edge.target)
      .filter((edge) => known.has(edge.source) && known.has(edge.target))
      .map((edge) => ({
        id: edge.id,
        // Reversed on purpose — see above.
        sources: [edge.target],
        targets: [edge.source],
      })),
  };
}

/**
 * ELK's answer, back in this view's vocabulary.
 *
 * Missing coordinates default to zero rather than throwing: ELK returns a laid-out node for
 * everything it was given, so a gap here would mean a version change, and a picture drawn in the
 * top-left corner is recoverable where a blank canvas is not.
 */
export function readElkResult(root: ElkNode, request: LayoutRequest): LayoutResult {
  const nodes: LayoutBox[] = (root.children ?? []).map((child) => ({
    id: child.id,
    x: child.x ?? 0,
    y: child.y ?? 0,
    width: child.width ?? CARD_WIDTH,
    height: child.height ?? 0,
  }));

  const requested = new Map(request.edges.map((edge) => [edge.id, edge]));
  const edges: LayoutEdge[] = (root.edges ?? []).flatMap((elkEdge) => {
    const original = requested.get(elkEdge.id);
    if (!original) {
      return [];
    }
    return [
      {
        id: elkEdge.id,
        source: original.source,
        target: original.target,
        // Reversed back, so the polyline starts at the edge's real source and the arrowhead lands
        // on its real target.
        points: routeOf(elkEdge).reverse(),
      },
    ];
  });

  return { nodes, edges };
}

/** One ELK edge's route as a flat polyline. Multi-section edges are hierarchical; these are not. */
function routeOf(edge: ElkExtendedEdge): Point[] {
  const section = edge.sections?.[0];
  if (!section) {
    return [];
  }
  return [section.startPoint, ...(section.bendPoints ?? []), section.endPoint].map((point) => ({
    x: point.x,
    y: point.y,
  }));
}
