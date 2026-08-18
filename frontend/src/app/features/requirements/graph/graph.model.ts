import type { RequirementCardNode } from '../../../shared/requirement-card/requirement-card.model';

// Wire shapes for docs/REQ_BREAKDOWN_GRAPH_VIEW.md §3.3, mirroring `api/dto/DependencyGraphDtos.kt`.
// Every `ref` is the opaque route handle (R5) — never a raw internal id.

/**
 * One node: the shared requirement card, plus where the picture puts it.
 *
 * `card.ref` is the only key. There is no separate item id, no module url and no placeholder flag
 * beside it — `card` already carries identity, module and `resolved`, and stating any of them twice
 * is how the two come to disagree.
 *
 * `level` is the strategy's raw level, **not** a band index: null means the strategy could not
 * place this node. {@link partitionOf} is what turns the distinct levels into dense, ordered
 * layout partitions.
 */
export interface GraphNode {
  readonly card: RequirementCardNode;
  readonly level: number | null;
  readonly seed: boolean;
  /** Neighbours this node has that the picture does not contain — cut by the cap or the depth. */
  readonly truncatedNeighbours: number;
}

/**
 * One `refersTo` edge, source → target, drawn with an arrowhead and **no label**.
 *
 * There is deliberately no `type` or `weight`: the DXL exporter discards the DOORS link-module
 * name, so satisfies / verifies / refines do not exist in the graph and there is nothing truthful
 * to label an edge with (§8).
 */
export interface GraphEdge {
  readonly source: string;
  readonly target: string;
}

/** One horizontal band, top to bottom. `level` null is the band unplaced nodes fall into. */
export interface LevelBand {
  readonly level: number | null;
  readonly label: string;
}

export interface UnresolvedModule {
  readonly ref: string | null;
  readonly name: string;
  readonly count: number;
}

export interface DependencyGraph {
  readonly seedRefs: string[];
  readonly depth: number;
  readonly direction: GraphDirection;
  readonly levelStrategy: GraphLevelStrategy;
  readonly nodes: GraphNode[];
  readonly edges: GraphEdge[];
  readonly levels: LevelBand[];
  readonly truncated: boolean;
  readonly unresolvedModules: UnresolvedModule[];
}

/**
 * Which way the walk follows `refersTo`.
 *
 * Named by the data, not by "up" and "down": an outgoing `refersTo` is read here as *this
 * requirement refines its target*, so following it goes **up** the decomposition — the opposite of
 * what "downstream" would suggest. The wording a user sees comes from the server.
 */
export type GraphDirection = 'OUTGOING' | 'INCOMING' | 'BOTH';

export type GraphLevelStrategy = 'MODULE_SYSTEM_LEVEL' | 'OUTLINE_LEVEL' | 'GRAPH_RANK';

/** The scope a request is for. The seed is a route handle, so the whole scope is shareable. */
export interface GraphScope {
  readonly seedRef: string;
  readonly depth: number;
  readonly direction: GraphDirection;
  readonly levelStrategy: GraphLevelStrategy;
}

export const DEFAULT_SCOPE: Omit<GraphScope, 'seedRef'> = {
  depth: 2,
  direction: 'BOTH',
  levelStrategy: 'MODULE_SYSTEM_LEVEL',
};

/** The range the depth control offers, matching what the API accepts (§3.1). */
export const MIN_DEPTH = 1;
export const MAX_DEPTH = 5;
