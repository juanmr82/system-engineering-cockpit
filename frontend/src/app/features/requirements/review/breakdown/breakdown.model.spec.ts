import { MAX_ROWS, buildTree, flatten } from './breakdown.model';
import type { BreakdownNode, BreakdownResponse } from './breakdown.model';

// The §3 rules, exercised as pure functions. This is where the DAG-to-tree decisions live, and
// they are the part of the feature most likely to be quietly changed by a later refactor.

const node = (ref: string, level: string | null = null): BreakdownNode => ({
  ref,
  id: ref.toUpperCase(),
  level: level ? { code: level, label: `${level} – label` } : null,
  description: `${ref} text`,
  resolved: true,
  moduleRef: 'module',
  moduleName: 'A module',
  verificationAttributes: [],
});

const open = new Set<string>();

/**
 * The shape SEG-REQ-1247 has in the reference module, and the one this transform exists for:
 *
 * ```
 *   sys1 (L1)        sys9 (L1)     ← two independent roots
 *     ↑     ↑            ↑
 *   seg1   seg2          |
 *     ↑  ↖   ↑           |
 *   cmp2  \ cmp1 --------+        ← cmp1 refines three parents across both roots
 * ```
 */
const FOREST: BreakdownResponse = {
  selectedRef: 'cmp1',
  roots: ['sys1', 'sys9'],
  truncated: false,
  nodes: [node('sys1', 'L1'), node('sys9', 'L1'), node('seg1', 'L2'), node('seg2', 'L2'), node('cmp1'), node('cmp2')],
  edges: [
    { from: 'cmp1', to: 'seg1', cyclic: false },
    { from: 'cmp1', to: 'seg2', cyclic: false },
    { from: 'cmp1', to: 'sys9', cyclic: false },
    { from: 'seg1', to: 'sys1', cyclic: false },
    { from: 'seg2', to: 'sys1', cyclic: false },
    { from: 'cmp2', to: 'seg1', cyclic: false },
  ],
};

describe('buildTree', () => {
  /**
   * The rule the whole transform turns on: a requirement is drawn under **every** parent it
   * refines, so a reviewer reading the second tree finds it there too. Drawing it once and
   * chipping the rest was tried first, and read as a hole in tree 2.
   */
  it('draws a multi-parent requirement under every parent, in every root it belongs to', () => {
    const rows = flatten(buildTree(FOREST), open);

    const copies = rows.filter((row) => row.node.ref === 'cmp1');
    expect(copies).toHaveLength(3);
    expect(copies.map((row) => row.parent?.ref).sort()).toEqual(['seg1', 'seg2', 'sys9']);
    // One root's tree holds two of them (under seg1 and seg2), the other holds the third.
    expect(new Set(copies.map((row) => row.rootIndex))).toEqual(new Set([0, 1]));
  });

  // Every copy is marked, not just the first: it is one item, and a reviewer scanning the second
  // tree needs to see where they are in it too.
  it('marks every copy of the selected requirement', () => {
    const rows = flatten(buildTree(FOREST), open);

    expect(rows.filter((row) => row.selected)).toHaveLength(3);
  });

  // Two copies of one requirement are only readable if each says which parent it sits under.
  it('gives each row the parent it refines, and gives a root none', () => {
    const rows = flatten(buildTree(FOREST), open);

    expect(rows.find((row) => row.node.ref === 'sys1')?.parent).toBeNull();
    expect(rows.find((row) => row.node.ref === 'seg1')?.parent?.id).toBe('SYS1');
  });

  it('renders each root as its own tree rather than merging them', () => {
    const tree = buildTree(FOREST);

    expect(tree.roots.map((root) => root.node.ref)).toEqual(['sys1', 'sys9']);
  });

  // The key is the position, not the node — otherwise collapsing one copy would collapse them all.
  it('keys a row by where it is drawn, so two copies collapse independently', () => {
    const tree = buildTree(FOREST);
    const rows = flatten(tree, open);
    const keys = rows.filter((row) => row.node.ref === 'cmp1').map((row) => row.key);

    expect(new Set(keys).size).toBe(3);

    const collapsedOne = flatten(tree, new Set([keys[0]]));
    expect(collapsedOne.filter((row) => row.node.ref === 'cmp1')).toHaveLength(3);
    expect(collapsedOne.find((row) => row.key === keys[0])?.expanded).toBe(false);
    expect(collapsedOne.find((row) => row.key === keys[1])?.expanded).toBe(true);
  });

  // Criterion 8: a cycle stops at a marked row instead of recursing.
  it('never follows a cyclic edge, and reports it as a loop', () => {
    const rows = flatten(
      buildTree({
        selectedRef: 'loop1',
        roots: ['loop1'],
        truncated: false,
        nodes: [node('loop1'), node('loop2')],
        edges: [
          { from: 'loop1', to: 'loop2', cyclic: true },
          { from: 'loop2', to: 'loop1', cyclic: false },
        ],
      }),
      open,
    );

    expect(rows.map((row) => row.node.ref)).toEqual(['loop1', 'loop2']);
    expect(rows[0].loops.map((target) => target.ref)).toEqual(['loop2']);
  });

  /**
   * The guard that replaces §3B's draw-once rule.
   *
   * A node never appears twice on one root-to-node path, so a loop the server did not mark still
   * terminates — and it terminates *visibly*, as a loop marker rather than a missing branch.
   */
  it('stops a branch that would revisit a node already on its path', () => {
    const rows = flatten(
      buildTree({
        selectedRef: 'a',
        roots: ['a'],
        truncated: false,
        nodes: [node('a'), node('b')],
        edges: [
          { from: 'b', to: 'a', cyclic: false },
          { from: 'a', to: 'b', cyclic: false },
        ],
      }),
      open,
    );

    expect(rows.map((row) => row.node.ref)).toEqual(['a', 'b']);
    expect(rows[1].loops.map((target) => target.ref)).toEqual(['a']);
  });

  /**
   * Drawing under every parent can in principle grow exponentially, so the forest stops at
   * MAX_ROWS and says so. It does not come close on real data — the widest breakdown in the
   * reference module is 40 rows over 31 nodes — which is why this is a guard, not a working limit.
   */
  it('stops drawing at the row cap and reports it rather than truncating in silence', () => {
    // A binary fan-out deep enough to blow past the cap: each level doubles.
    const depth = 12;
    const nodes = [node('n0')];
    const edges: BreakdownResponse['edges'] = [];
    for (let level = 1; level <= depth; level++) {
      nodes.push(node(`n${level}`));
      edges.push({ from: `n${level}`, to: `n${level - 1}`, cyclic: false });
      if (level > 1) {
        // A second parent per level, which is what multiplies the drawn rows.
        edges.push({ from: `n${level}`, to: `n${level - 2}`, cyclic: false });
      }
    }

    const tree = buildTree({ selectedRef: 'n0', roots: ['n0'], truncated: false, nodes, edges });

    expect(tree.capped).toBe(true);
    expect(flatten(tree, open).length).toBeLessThanOrEqual(MAX_ROWS);
  });

  // Truncation can sever a chain. A node the reviewer cannot see at all is worse than a forest
  // with an extra stump in it.
  it('still draws a node the roots cannot reach', () => {
    const tree = buildTree({ ...FOREST, roots: ['sys9'] });

    expect(tree.roots.map((root) => root.node.ref)).toContain('sys1');
  });
});

describe('flatten', () => {
  // Everything is open by default: §1's ask is that every requirement in this view shows its
  // statement and its verification attributes, without a click.
  it('opens every row by default and reads down the tree, deepest branch first', () => {
    const rows = flatten(buildTree(FOREST), open);

    expect(rows.every((row) => row.expanded)).toBe(true);
    expect(rows.map((row) => [row.node.ref, row.depth])).toEqual([
      ['sys1', 0],
      ['seg1', 1],
      ['cmp1', 2],
      ['cmp2', 2],
      ['seg2', 1],
      ['cmp1', 2],
      ['sys9', 0],
      ['cmp1', 1],
    ]);
  });

  it('takes a collapsed row\'s children off screen and reports the count instead', () => {
    const tree = buildTree(FOREST);
    const rows = flatten(tree, new Set(['sys1']));

    expect(rows.map((row) => row.node.ref)).toEqual(['sys1', 'sys9', 'cmp1']);
    expect(rows[0].childCount).toBe(2);
    expect(rows[0].expanded).toBe(false);
  });
});
