import { describe, expect, it } from 'vitest';
import {
  compressBands,
  dedupeEdges,
  isFeedbackEdge,
  isSelfLoop,
  orderEdges,
  orderedBands,
  partitionIndex,
  partitionMap,
  partitionOf,
} from './graph-layout';
import type { CompressedLayout, LayoutBox, LayoutEdge } from './graph-layout';
import type { GraphEdge, GraphNode, LevelBand } from '../graph.model';

// The pure half of docs/REQ_BREAKDOWN_GRAPH_VIEW §4, exercised without a DOM, without ELK and
// without a driver — the list §7 asks for by name.

function node(ref: string, level: number | null, seed = false): GraphNode {
  return {
    card: {
      ref,
      id: ref.toUpperCase(),
      level: null,
      description: '',
      resolved: true,
      moduleRef: null,
      moduleName: null,
      verificationAttributes: [],
    },
    level,
    seed,
    truncatedNeighbours: 0,
  };
}

function band(level: number | null, label = `band ${level}`): LevelBand {
  return { level, label };
}

function box(id: string, y: number, height = 100, x = 0): LayoutBox {
  return { id, x, y, width: 260, height };
}

function edge(id: string, source: string, target: string, ...points: [number, number][]): LayoutEdge {
  return { id, source, target, points: points.map(([x, y]) => ({ x, y })) };
}

/**
 * A node the layout must have placed, or a named failure.
 *
 * Thrown rather than asserted non-null: a missing node means the pass dropped one, and saying which
 * beats a `Cannot read properties of undefined` from four lines further down.
 */
function placed(result: CompressedLayout, id: string): LayoutBox {
  const node = result.nodes.find((box) => box.id === id);
  if (!node) {
    throw new Error(`the layout did not place ${id}`);
  }
  return node;
}

describe('partitionIndex', () => {
  // The case §7 names: ELK needs contiguous partition numbers, and a project whose levels are
  // 1, 2 and 4 must not produce an empty partition 3.
  it('renumbers gapped levels densely', () => {
    const index = partitionIndex([band(1), band(2), band(4)]);

    expect([...index.entries()]).toEqual([
      [1, 0],
      [2, 1],
      [4, 2],
    ]);
  });

  it('puts the unplaced level last, whatever order it arrived in', () => {
    const index = partitionIndex([band(null), band(3), band(1)]);

    expect([...index.entries()]).toEqual([
      [1, 0],
      [3, 1],
      [null, 2],
    ]);
  });

  it('collapses a repeated level rather than numbering it twice', () => {
    expect([...partitionIndex([band(1), band(1), band(2)]).entries()]).toEqual([
      [1, 0],
      [2, 1],
    ]);
  });

  it('is empty for no bands', () => {
    expect(partitionIndex([]).size).toBe(0);
  });
});

describe('partitionOf', () => {
  it('places a node by its level', () => {
    const index = partitionIndex([band(1), band(2), band(null)]);

    expect(partitionOf(node('a', 1), index)).toBe(0);
    expect(partitionOf(node('b', 2), index)).toBe(1);
    expect(partitionOf(node('c', null), index)).toBe(2);
  });

  /**
   * A level the band list does not mention cannot happen — the server builds both from one pass —
   * and if it ever did, a node drawn in the bottom band is recoverable where a blank canvas is not.
   */
  it('falls back to the last partition rather than throwing', () => {
    const index = partitionIndex([band(1), band(2)]);

    expect(partitionOf(node('x', 9), index)).toBe(1);
  });

  it('survives an empty band list', () => {
    expect(partitionOf(node('x', 1), partitionIndex([]))).toBe(0);
  });
});

describe('orderedBands', () => {
  it('returns the bands in the order the partitions were numbered', () => {
    const ordered = orderedBands([band(null, 'nowhere'), band(2, 'two'), band(1, 'one')]);

    expect(ordered.map((b) => b.label)).toEqual(['one', 'two', 'nowhere']);
  });
});

describe('partitionMap', () => {
  it('keys every node by its card ref', () => {
    const map = partitionMap([node('a', 2), node('b', null), node('c', 1)], [band(1), band(2), band(null)]);

    expect(map.get('a')).toBe(1);
    expect(map.get('b')).toBe(2);
    expect(map.get('c')).toBe(0);
  });
});

describe('compressBands', () => {
  const bands = [band(1, 'L1'), band(2, 'L2')];

  it('does nothing to an empty layout', () => {
    const result = compressBands([], [], new Map(), bands);

    expect(result.nodes).toEqual([]);
    expect(result.bands).toEqual([]);
    expect(result.height).toBe(0);
  });

  it('lays out a single partition as one band', () => {
    const partition = new Map([['a', 0]]);
    const result = compressBands([box('a', 900)], [], partition, [band(1, 'L1')]);

    expect(result.bands).toHaveLength(1);
    expect(result.bands[0].label).toBe('L1');
    // Pulled to the top of the canvas: ELK's absolute y is not a fact worth preserving.
    expect(result.nodes[0].y).toBe(18);
    expect(result.bands[0].top).toBe(0);
    expect(result.bands[0].bottom).toBe(18 + 100 + 18);
  });

  it('places a partition holding one node', () => {
    const partition = new Map([
      ['a', 0],
      ['b', 1],
    ]);
    const result = compressBands([box('a', 0), box('b', 400)], [], partition, bands);

    expect(result.bands).toHaveLength(2);
    expect(placed(result, 'b').y).toBe(result.bands[1].top + 18);
  });

  /**
   * The behaviour the whole pass exists for (§4.3): two sub-lanes inside one band sit a small step
   * apart, and the next band is pushed much further down — so "a bit lower" reads as "still this
   * level" and never as "the next one".
   */
  it('compresses sub-lanes inside a band and pushes the next band well clear', () => {
    const partition = new Map([
      ['a', 0],
      ['b', 0],
      ['c', 1],
    ]);
    const nodes = [box('a', 0), box('b', 500), box('c', 1000)];

    const result = compressBands(nodes, [], partition, bands);

    const sublaneStep = placed(result, 'b').y - placed(result, 'a').y;
    const bandStep = placed(result, 'c').y - placed(result, 'b').y;

    // 100px of card plus the 28px gap.
    expect(sublaneStep).toBe(128);
    // The property that matters is the ratio, not a number: crossing into the next level has to be
    // an unmistakably bigger move than stepping down inside this one, or the two read the same.
    expect(bandStep).toBeGreaterThanOrEqual(sublaneStep * 2);
    expect(result.bands[1].top).toBeGreaterThan(result.bands[0].bottom);
  });

  it('keeps the relative order of nodes inside a partition', () => {
    const partition = new Map([
      ['a', 0],
      ['b', 0],
      ['c', 0],
    ]);
    const nodes = [box('c', 800), box('a', 0), box('b', 400)];

    const result = compressBands(nodes, [], partition, [band(1)]);

    expect(placed(result, 'a').y).toBeLessThan(placed(result, 'b').y);
    expect(placed(result, 'b').y).toBeLessThan(placed(result, 'c').y);
  });

  it('keeps nodes sharing a row in the same sub-lane', () => {
    const partition = new Map([
      ['a', 0],
      ['b', 0],
    ]);
    const result = compressBands([box('a', 300, 100, 0), box('b', 300, 100, 400)], [], partition, [
      band(1),
    ]);

    const [a, b] = ['a', 'b'].map((id) => placed(result, id));
    expect(a.y).toBe(b.y);
    // x is ELK's and is never touched.
    expect(a.x).toBe(0);
    expect(b.x).toBe(400);
  });

  /**
   * §7: "bend-point remapping matches node remapping exactly."
   *
   * A bend point sitting on a card's top edge has to land on its new top edge, or a route stops
   * meeting the thing it was routed to.
   */
  it('remaps a bend point on a node edge to that node’s new edge', () => {
    const partition = new Map([
      ['a', 0],
      ['b', 1],
    ]);
    const nodes = [box('a', 0), box('b', 700)];
    const edges = [edge('e0', 'b', 'a', [10, 700], [10, 400], [10, 100])];

    const result = compressBands(nodes, edges, partition, bands);
    const points = result.edges[0].points;

    expect(points[0].y).toBe(placed(result, 'b').y);
    expect(points[2].y).toBe(placed(result, 'a').y + 100);
    // x is untouched by the pass, at every point.
    expect(points.map((p) => p.x)).toEqual([10, 10, 10]);
    // Monotone: a route that crossed itself would be the mapping not being a function.
    expect(points[0].y).toBeGreaterThan(points[1].y);
    expect(points[1].y).toBeGreaterThan(points[2].y);
  });

  it('remaps a bend point between two lanes to somewhere between them', () => {
    const partition = new Map([
      ['a', 0],
      ['b', 0],
    ]);
    const nodes = [box('a', 0), box('b', 500)];
    const edges = [edge('e0', 'a', 'b', [0, 100], [0, 300], [0, 500])];

    const result = compressBands(nodes, edges, partition, [band(1)]);
    const [top, middle, bottom] = result.edges[0].points;

    expect(middle.y).toBeGreaterThan(top.y);
    expect(middle.y).toBeLessThan(bottom.y);
  });

  it('skips a band the picture has nothing in', () => {
    const partition = new Map([['a', 2]]);
    const result = compressBands([box('a', 0)], [], partition, [band(1), band(2), band(3)]);

    expect(result.bands).toHaveLength(1);
    expect(result.bands[0].level).toBe(3);
  });

  it('reports the canvas extent the nodes actually occupy', () => {
    const partition = new Map([
      ['a', 0],
      ['b', 0],
    ]);
    const result = compressBands([box('a', 0, 100, 0), box('b', 0, 100, 900)], [], partition, [band(1)]);

    expect(result.width).toBe(1160);
    expect(result.height).toBe(result.bands[0].bottom);
  });
});

describe('edges', () => {
  const e = (source: string, target: string): GraphEdge => ({ source, target });

  it('collapses duplicate pairs to one', () => {
    expect(dedupeEdges([e('a', 'b'), e('a', 'b'), e('b', 'a')])).toEqual([e('a', 'b'), e('b', 'a')]);
  });

  // Almost always an authoring error, and therefore worth seeing (§4.5).
  it('keeps a self-loop', () => {
    expect(dedupeEdges([e('a', 'a')])).toEqual([e('a', 'a')]);
    expect(isSelfLoop(e('a', 'a'))).toBe(true);
    expect(isSelfLoop(e('a', 'b'))).toBe(false);
  });

  it('orders edges by the order the response listed their nodes', () => {
    const nodes = [node('x', 1), node('y', 1), node('z', 1)];
    const ordered = orderEdges([e('z', 'x'), e('x', 'z'), e('x', 'y')], nodes);

    expect(ordered).toEqual([e('x', 'y'), e('x', 'z'), e('z', 'x')]);
  });

  describe('isFeedbackEdge', () => {
    const positions = new Map([
      ['top', box('top', 0)],
      ['bottom', box('bottom', 400)],
    ]);

    it('is false when the target sits above the source, as refines should', () => {
      expect(isFeedbackEdge(e('bottom', 'top'), positions)).toBe(false);
    });

    it('is true when the layout could not put the target above its source', () => {
      expect(isFeedbackEdge(e('top', 'bottom'), positions)).toBe(true);
    });

    it('is false for a self-loop, which is drawn as a loop rather than as a fight', () => {
      expect(isFeedbackEdge(e('top', 'top'), positions)).toBe(false);
    });

    it('is false when an endpoint is not in the picture', () => {
      expect(isFeedbackEdge(e('top', 'gone'), positions)).toBe(false);
    });
  });

  /**
   * §7: "a 2-cycle and a 3-cycle produce a layout, and every rendered arrowhead matches the source
   * DTO's direction."
   *
   * The arrowhead is what this asserts: exactly one edge of each cycle is marked as one the layout
   * had to fight, and *no* edge comes back with its ends swapped.
   */
  it('marks a cycle without ever reversing an edge', () => {
    const twoCycle = [e('a', 'b'), e('b', 'a')];
    const positions = new Map([
      ['a', box('a', 0)],
      ['b', box('b', 400)],
    ]);

    const marked = twoCycle.filter((edge) => isFeedbackEdge(edge, positions));
    expect(marked).toEqual([e('a', 'b')]);
    expect(dedupeEdges(twoCycle)).toEqual(twoCycle);

    const threeCycle = [e('a', 'b'), e('b', 'c'), e('c', 'a')];
    const three = new Map([
      ['a', box('a', 0)],
      ['b', box('b', 200)],
      ['c', box('c', 400)],
    ]);
    expect(threeCycle.filter((edge) => isFeedbackEdge(edge, three))).toEqual([e('a', 'b'), e('b', 'c')]);
    expect(dedupeEdges(threeCycle)).toEqual(threeCycle);
  });
});
