import type { SystemLevelOption } from '../../modules/modules.model';

// Wire shapes and the DAG-to-tree transform for docs/requirement-breakdown-tree.md. `ref` is
// always the opaque route handle (R5) — never a raw internal id.

export interface BreakdownAttribute {
  readonly name: string;
  readonly value: string;
}

/**
 * One node of the forest.
 *
 * `resolved` false means the target is a placeholder the importer created for an object no import
 * has reached: it carries no DOORS id and no description, and the UI renders it as "Not yet
 * imported", names the owning module, and does not link it — exactly as the References column does
 * (§7).
 */
export interface BreakdownNode {
  readonly ref: string;
  readonly id: string | null;
  readonly level: SystemLevelOption | null;
  readonly description: string;
  readonly resolved: boolean;
  readonly moduleRef: string | null;
  readonly moduleName: string | null;
  readonly verificationAttributes: BreakdownAttribute[];
}

/** `from` refines `to` (§2), so `to` is drawn as `from`'s parent. */
export interface BreakdownEdge {
  readonly from: string;
  readonly to: string;
  readonly cyclic: boolean;
}

export interface BreakdownResponse {
  readonly selectedRef: string;
  readonly roots: string[];
  readonly truncated: boolean;
  readonly nodes: BreakdownNode[];
  readonly edges: BreakdownEdge[];
}

/**
 * How many rows the forest may draw before it stops.
 *
 * Drawing a requirement under every parent it refines can, in principle, grow exponentially on a
 * dense DAG. It does not on real data — the widest breakdown in the reference Segment module is 40
 * rows over 31 nodes, measured across 250 objects — so this is a guard, not a working limit, and
 * hitting it is reported rather than silently swallowed.
 */
export const MAX_ROWS = 500;

export interface TreeNode {
  /**
   * Unique per *rendered position*, not per node.
   *
   * A requirement that refines two parents is drawn under both, so its ref is no longer a key. The
   * path is: collapsing one copy leaves the other open, which is right — they are two places in the
   * document, not two views of one place.
   */
  readonly key: string;
  readonly node: BreakdownNode;
  /** What this row refines, and therefore what its relationship line names. Null on a root. */
  readonly parent: BreakdownNode | null;
  readonly children: TreeNode[];
  /**
   * References out of or into this row that the tree cannot draw, because following one would
   * revisit a node already on this path. Reported as a quiet marker so the branch stopping is
   * visible; `refersTo` is not supposed to cycle and nothing in Community's schema prevents it
   * (CLAUDE.md §7).
   */
  readonly loops: BreakdownNode[];
}

export interface BreakdownTree {
  readonly roots: TreeNode[];
  readonly selectedRef: string;
  /** The server stopped walking: nodes exist that the response does not carry. */
  readonly truncated: boolean;
  /** This transform stopped drawing at {@link MAX_ROWS}. */
  readonly capped: boolean;
}

/** One line of the flattened tree: what the template actually renders. */
export interface BreakdownRow {
  readonly key: string;
  readonly node: BreakdownNode;
  readonly parent: BreakdownNode | null;
  readonly loops: BreakdownNode[];
  /** 0 for a root, one step further right per level. Drives the depth rail, not an indent. */
  readonly depth: number;
  readonly childCount: number;
  readonly expanded: boolean;
  readonly selected: boolean;
  /** Which root's tree this row belongs to — the "Root 1 of 2" eyebrow. */
  readonly rootIndex: number;
}

/**
 * Turns the response's DAG into a tree (§3).
 *
 * **A requirement is drawn under every parent it refines.** SEG-REQ-1247 refines both SRD-1158 and
 * SRD-1411, which sit under different roots, so it appears in both trees — a reviewer reading the
 * second tree must not find the requirement missing from the decomposition it is genuinely part of.
 * This is §3A's rule ("highlighted inside every root's tree it is reachable from"), and it is the
 * one that ships; §3B's draw-once-with-a-chip was tried first and read as a hole in tree 2.
 *
 * What §3B was protecting against is real but is handled directly: a node never appears twice on
 * one root-to-node path, which stops a cycle dead, and the whole forest stops at {@link MAX_ROWS},
 * which stops a dense DAG. Each copy names the parent it refines, so two copies are never
 * ambiguous.
 */
export function buildTree(response: BreakdownResponse): BreakdownTree {
  const byRef = new Map(response.nodes.map((node) => [node.ref, node]));

  const parents = new Map<string, BreakdownEdge[]>();
  const children = new Map<string, BreakdownEdge[]>();
  for (const edge of response.edges) {
    if (!byRef.has(edge.from) || !byRef.has(edge.to)) {
      continue;
    }
    push(parents, edge.from, edge);
    // A cyclic edge is never a tree edge — following one is what would not terminate. It still
    // reaches the reviewer, as a loop marker.
    if (!edge.cyclic) {
      push(children, edge.to, edge);
    }
  }

  let drawn = 0;
  let capped = false;

  const build = (
    ref: string,
    parent: BreakdownNode | null,
    key: string,
    path: ReadonlySet<string>,
  ): TreeNode | null => {
    const node = byRef.get(ref);
    if (!node) {
      return null;
    }
    if (drawn >= MAX_ROWS) {
      capped = true;
      return null;
    }
    drawn++;

    const childEdges = children.get(ref) ?? [];
    const nextPath = new Set(path).add(ref);

    return {
      key,
      node,
      parent,
      children: childEdges
        .filter((edge) => !path.has(edge.from))
        .map((edge) => build(edge.from, node, `${key}/${edge.from}`, nextPath))
        .filter((child): child is TreeNode => child !== null),
      loops: [
        // A reference out of here that the server marked as closing a cycle...
        ...(parents.get(ref) ?? []).filter((edge) => edge.cyclic).map((edge) => byRef.get(edge.to)),
        // ...and one into here from a node already on this path, which is the same fact seen from
        // the other end. Both mean: this link exists, and the tree stops rather than repeating.
        ...childEdges.filter((edge) => path.has(edge.from)).map((edge) => byRef.get(edge.from)),
      ].filter((target): target is BreakdownNode => target !== undefined),
    };
  };

  const roots = response.roots
    .map((ref) => build(ref, null, ref, new Set()))
    .filter((root): root is TreeNode => root !== null);

  // Anything the roots did not reach still has to be drawn — truncation can sever a chain, and a
  // node the reviewer cannot see at all is worse than a forest with an extra stump in it.
  const reached = new Set<string>();
  const collect = (tree: TreeNode): void => {
    reached.add(tree.node.ref);
    tree.children.forEach(collect);
  };
  roots.forEach(collect);
  for (const node of response.nodes) {
    if (!reached.has(node.ref)) {
      const orphan = build(node.ref, null, node.ref, new Set());
      if (orphan) {
        collect(orphan);
        roots.push(orphan);
      }
    }
  }

  return { roots, selectedRef: response.selectedRef, truncated: response.truncated, capped };
}

/**
 * The visible rows, in reading order: a pre-order walk that stops at a collapsed row.
 *
 * Collapsed rather than expanded, because everything is open by default — every requirement shows
 * its statement and its verification attributes, and collapsing is what the reviewer does to a
 * branch they are done with. An empty set is therefore the correct initial state for any tree,
 * with nothing to recompute when the tree changes.
 */
export function flatten(tree: BreakdownTree, collapsed: ReadonlySet<string>): BreakdownRow[] {
  const rows: BreakdownRow[] = [];

  const walk = (node: TreeNode, depth: number, rootIndex: number): void => {
    const isCollapsed = collapsed.has(node.key);
    rows.push({
      key: node.key,
      node: node.node,
      parent: node.parent,
      loops: node.loops,
      depth,
      childCount: node.children.length,
      expanded: !isCollapsed,
      // Every copy of the selected requirement is marked, not just the first — it is one item, and
      // a reviewer scanning the second tree needs to see where they are in it too.
      selected: node.node.ref === tree.selectedRef,
      rootIndex,
    });
    if (!isCollapsed) {
      for (const child of node.children) {
        walk(child, depth + 1, rootIndex);
      }
    }
  };

  tree.roots.forEach((root, index) => walk(root, 0, index));
  return rows;
}

function push(map: Map<string, BreakdownEdge[]>, key: string, edge: BreakdownEdge): void {
  const existing = map.get(key);
  if (existing) {
    existing.push(edge);
  } else {
    map.set(key, [edge]);
  }
}
