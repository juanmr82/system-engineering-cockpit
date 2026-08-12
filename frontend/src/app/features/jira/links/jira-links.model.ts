/**
 * The wire types of one issue's related-issues graph (`api/dto/JiraDtos.kt`).
 *
 * Deliberately **not** the DOORS graph's types. The two share a picture, not a payload: a
 * requirement card carries a module, an outline level and a system level; a JIRA node carries a
 * type, a status and a summary. One type covering both would be a type whose fields are null for
 * whichever source you are looking at.
 */

/** One issue in the picture — the four things §13.2 asks a node to show, plus where it sits. */
export interface JiraGraphNode {
  /** The opaque handle over `__id` (R5). The node's key in the layout and in the DOM. */
  readonly ref: string;
  readonly key: string;
  readonly typeName: string | null;
  readonly statusName: string | null;
  readonly summary: string | null;
  /** A stub for an issue outside the configured projects. Drawn, and said in words. */
  readonly unresolved: boolean;
  /** The issue the graph was opened for. Exactly one node carries it. */
  readonly seed: boolean;
  /** Links this node has that the picture does not contain — cut by the cap or the depth. */
  readonly truncatedNeighbours: number;
}

/**
 * One link, in the direction JIRA asserts it.
 *
 * `typeName` is JIRA's own word for the relationship — *Relates*, *Blocks*, *Duplicates* — and it
 * is shown, because unlike DOORS's `refersTo` this source actually says what the link is. A
 * sub-task edge has no type name; the relationship is its label.
 */
export interface JiraGraphEdge {
  readonly source: string;
  readonly target: string;
  readonly typeName: string | null;
  readonly subTask: boolean;
}

export interface JiraLinkGraph {
  readonly seedRef: string;
  readonly depth: number;
  readonly nodes: readonly JiraGraphNode[];
  readonly edges: readonly JiraGraphEdge[];
  /** The cap or the depth cut the picture short, so the view has to say so. */
  readonly truncated: boolean;
}

/** The depth control's bounds, mirroring `JiraLinkGraphProjection`. */
export const MIN_DEPTH = 1;
export const MAX_DEPTH = 5;
export const DEFAULT_DEPTH = 2;
