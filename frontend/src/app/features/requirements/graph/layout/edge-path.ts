import type { LayoutBox, Point } from './graph-layout';

/**
 * Turning a route into an SVG path (docs/REQ_BREAKDOWN_GRAPH_VIEW §5.3).
 *
 * Pure, and separate from the canvas, because a path string is exactly the kind of thing that is
 * easy to get subtly wrong and impossible to see in jsdom — which has no layout and no canvas.
 */

/** How tightly an orthogonal route turns a corner. Small: this is paper, not a subway map. */
export const CORNER_RADIUS = 6;

/**
 * A polyline with rounded corners, as an SVG path.
 *
 * Each corner is replaced by a quadratic curve through the corner point, pulled back along both
 * legs by at most half their length — so a short leg gives a small corner instead of a curve that
 * overshoots into the next one, which is what a fixed radius does on a tight route.
 */
export function roundedPolyline(points: readonly Point[], radius = CORNER_RADIUS): string {
  if (points.length === 0) {
    return '';
  }
  if (points.length === 1) {
    return `M ${round(points[0].x)} ${round(points[0].y)}`;
  }

  const parts = [`M ${round(points[0].x)} ${round(points[0].y)}`];

  for (let i = 1; i < points.length - 1; i++) {
    const previous = points[i - 1];
    const corner = points[i];
    const next = points[i + 1];

    const into = pullBack(corner, previous, radius);
    const outOf = pullBack(corner, next, radius);

    parts.push(`L ${round(into.x)} ${round(into.y)}`);
    parts.push(`Q ${round(corner.x)} ${round(corner.y)} ${round(outOf.x)} ${round(outOf.y)}`);
  }

  const end = points[points.length - 1];
  parts.push(`L ${round(end.x)} ${round(end.y)}`);
  return parts.join(' ');
}

/**
 * The local router: a deterministic three-segment orthogonal route, down / across / down (§5.6).
 *
 * Used whenever ELK's bend points are not valid — before a layout has run, and for any edge whose
 * endpoints have been moved by hand. Same corner radius and same stroke as the ELK routes, so the
 * two are visually indistinguishable. It does **not** avoid overlapping nodes, which is the
 * accepted cost of a route that has to be computed in a pointer-move handler.
 *
 * The arrow runs source → target, and by this product's convention that means bottom → top: the
 * route leaves the source's top edge and arrives at the target's bottom edge. When the target is
 * *not* above the source — a cycle the layout could not honour — it leaves the bottom and arrives
 * at the top instead, so the arrowhead still lands on an edge of the card rather than in its middle.
 */
export function localRoute(source: LayoutBox, target: LayoutBox): Point[] {
  const sourceX = source.x + source.width / 2;
  const targetX = target.x + target.width / 2;
  const upward = target.y + target.height <= source.y;

  const start = { x: sourceX, y: upward ? source.y : source.y + source.height };
  const end = { x: targetX, y: upward ? target.y + target.height : target.y };
  const middle = (start.y + end.y) / 2;

  if (Math.abs(sourceX - targetX) < 1) {
    return [start, end];
  }
  return [start, { x: sourceX, y: middle }, { x: targetX, y: middle }, end];
}

/**
 * A self-loop: a small loop off the node's right edge (§4.5).
 *
 * Never dropped, because a requirement referring to itself is almost always an authoring error and
 * is worth seeing. Drawn from the box rather than routed, since there is nothing to route between.
 */
export function selfLoopPath(box: LayoutBox, size = 22): string {
  const x = box.x + box.width;
  const top = box.y + box.height / 3;
  const bottom = box.y + (box.height * 2) / 3;

  return [
    `M ${round(x)} ${round(top)}`,
    `C ${round(x + size)} ${round(top - size / 2)}`,
    `${round(x + size)} ${round(bottom + size / 2)}`,
    `${round(x)} ${round(bottom)}`,
  ].join(' ');
}

/** A point on the line from `corner` towards `towards`, at most half way. */
function pullBack(corner: Point, towards: Point, radius: number): Point {
  const dx = towards.x - corner.x;
  const dy = towards.y - corner.y;
  const length = Math.hypot(dx, dy);
  if (length === 0) {
    return corner;
  }
  const distance = Math.min(radius, length / 2);
  return { x: corner.x + (dx / length) * distance, y: corner.y + (dy / length) * distance };
}

// Sub-pixel precision is invisible and makes a path string three times longer than it needs to be,
// which matters at 300 edges re-serialised on every drag.
function round(value: number): number {
  return Math.round(value * 10) / 10;
}
