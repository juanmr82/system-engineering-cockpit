import { describe, expect, it } from 'vitest';
import { localRoute, roundedPolyline, selfLoopPath } from './edge-path';
import type { LayoutBox } from './graph-layout';

function box(id: string, x: number, y: number, height = 100): LayoutBox {
  return { id, x, y, width: 260, height };
}

describe('roundedPolyline', () => {
  it('is empty for no points', () => {
    expect(roundedPolyline([])).toBe('');
  });

  it('is a bare move for one point', () => {
    expect(roundedPolyline([{ x: 5, y: 6 }])).toBe('M 5 6');
  });

  it('is a straight line for two points', () => {
    expect(
      roundedPolyline([
        { x: 0, y: 0 },
        { x: 0, y: 50 },
      ]),
    ).toBe('M 0 0 L 0 50');
  });

  it('rounds a corner with a curve through the corner point', () => {
    const path = roundedPolyline(
      [
        { x: 0, y: 0 },
        { x: 0, y: 100 },
        { x: 80, y: 100 },
      ],
      6,
    );

    expect(path).toBe('M 0 0 L 0 94 Q 0 100 6 100 L 80 100');
  });

  /**
   * A fixed radius on a short leg overshoots into the next corner, which draws a route that
   * visibly leaves and re-enters its own line. Half the leg is the most it may ever take.
   */
  it('never takes more than half a leg, however tight the route', () => {
    const path = roundedPolyline(
      [
        { x: 0, y: 0 },
        { x: 0, y: 4 },
        { x: 20, y: 4 },
      ],
      20,
    );

    expect(path).toBe('M 0 0 L 0 2 Q 0 4 10 4 L 20 4');
  });
});

describe('localRoute', () => {
  /**
   * `refersTo` is read as "refines", so an arrow runs from a card up to the card it refines: it
   * leaves the source's top edge and arrives at the target's bottom edge.
   */
  it('leaves the top of the source and arrives at the bottom of the target', () => {
    const route = localRoute(box('from', 0, 400), box('to', 0, 0));

    expect(route[0]).toEqual({ x: 130, y: 400 });
    expect(route[route.length - 1]).toEqual({ x: 130, y: 100 });
  });

  it('is a straight line when the two are in the same column', () => {
    expect(localRoute(box('from', 0, 400), box('to', 0, 0))).toHaveLength(2);
  });

  it('steps across at the midpoint when they are not', () => {
    const route = localRoute(box('from', 0, 400), box('to', 500, 0));

    expect(route).toHaveLength(4);
    expect(route[1].y).toBe(route[2].y);
    expect(route[1].x).toBe(130);
    expect(route[2].x).toBe(630);
  });

  /**
   * A cycle the layout could not honour leaves the target below its source. The route has to turn
   * round with it, or the arrowhead lands in the middle of a card instead of on its edge — and the
   * arrowhead is the one thing that must never lie about direction (§8).
   */
  it('turns round when the target is not above the source', () => {
    const route = localRoute(box('from', 0, 0), box('to', 0, 400));

    expect(route[0]).toEqual({ x: 130, y: 100 });
    expect(route[route.length - 1]).toEqual({ x: 130, y: 400 });
  });
});

describe('selfLoopPath', () => {
  // Never dropped: a requirement referring to itself is almost always an authoring error (§4.5).
  it('draws a loop off the right edge of the node', () => {
    const path = selfLoopPath(box('a', 100, 200));

    expect(path.startsWith('M 360')).toBe(true);
    expect(path).toContain('C 382');
  });
});
