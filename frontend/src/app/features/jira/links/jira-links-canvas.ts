import { Component, computed, inject, input, resource, signal } from '@angular/core';
import { MatTooltipModule } from '@angular/material/tooltip';
import { GraphLayoutService } from '../../requirements/graph/layout/graph-layout.service';
import { localRoute, roundedPolyline } from '../../requirements/graph/layout/edge-path';
import type { LayoutBox } from '../../requirements/graph/layout/graph-layout';
import type { JiraGraphEdge, JiraGraphNode, JiraLinkGraph } from './jira-links.model';

/** One node, placed. */
interface PlacedNode {
  readonly ref: string;
  readonly node: JiraGraphNode;
  readonly x: number;
  readonly y: number;
}

/** One edge, as the three things the template needs to draw it. */
interface DrawnEdge {
  readonly id: string;
  readonly path: string;
  readonly label: string;
  readonly subTask: boolean;
}

/**
 * A JIRA issue box is a fixed size, unlike a requirement card.
 *
 * The DOORS canvas measures every card because a requirement's text is a paragraph of unknown
 * length. An issue box holds four short things — type, key, status and a summary clamped to two
 * lines — so its height is a constant, and a constant removes the whole measure pass, the font
 * re-read it needs, and the frame of layout thrash that comes with it.
 */
const NODE_WIDTH = 260;
const NODE_HEIGHT = 104;

/**
 * The related-issues diagram (the JIRA half of the picture the DOORS dependency graph draws).
 *
 * ## What is reused, and what is not
 *
 * **The layout is reused exactly**: `GraphLayoutService` runs ELK in the same worker, and the edge
 * geometry comes from the same `edge-path` helpers. That is the expensive, subtle half and there is
 * one of it (ADR 0011).
 *
 * **The drawing is not.** The DOORS canvas draws requirement cards inside system-level bands, with
 * a measure pass, sub-lane compression and lane labels down the left edge. None of that applies
 * here: a JIRA link is not a decomposition, an issue has no system level, and the box is a fixed
 * size. Generalising that component to cover both would mean a component whose every feature is
 * conditional on which source it is drawing.
 *
 * ## Rank is distance from the seed
 *
 * ELK partitions vertically by `partition`, and here that is how many links away from the opened
 * issue a node is. The seed is the top row, what it links to is the second, and so on — which is
 * the reading a person brings to "show me what this is related to".
 */
@Component({
  selector: 'sec-jira-links-canvas',
  imports: [MatTooltipModule],
  templateUrl: './jira-links-canvas.html',
  styleUrl: './jira-links-canvas.scss',
  providers: [GraphLayoutService],
})
export class JiraLinksCanvas {
  private readonly layoutService = inject(GraphLayoutService);

  readonly graph = input.required<JiraLinkGraph>();

  protected readonly nodeWidth = NODE_WIDTH;
  protected readonly nodeHeight = NODE_HEIGHT;

  protected readonly zoom = signal(1);

  /**
   * How far each node is from the seed, following links in either direction.
   *
   * Computed here rather than by the server: it is a property of *this picture*, and the server
   * returns a set of nodes rather than a tree. A node the walk cannot reach — which the induced
   * edge set can produce when the cap cuts a path — falls to the last rank rather than to zero,
   * because zero is the seed's row and nothing else belongs in it.
   */
  private readonly ranks = computed(() => {
    const graph = this.graph();
    const neighbours = new Map<string, string[]>();

    for (const edge of graph.edges) {
      neighbours.set(edge.source, [...(neighbours.get(edge.source) ?? []), edge.target]);
      neighbours.set(edge.target, [...(neighbours.get(edge.target) ?? []), edge.source]);
    }

    const rank = new Map<string, number>([[graph.seedRef, 0]]);
    let frontier = [graph.seedRef];
    let distance = 0;

    while (frontier.length > 0) {
      distance += 1;
      const next: string[] = [];
      for (const ref of frontier) {
        for (const neighbour of neighbours.get(ref) ?? []) {
          if (!rank.has(neighbour)) {
            rank.set(neighbour, distance);
            next.push(neighbour);
          }
        }
      }
      frontier = next;
    }

    const unreached = Math.max(0, ...rank.values()) + 1;
    for (const node of graph.nodes) {
      if (!rank.has(node.ref)) rank.set(node.ref, unreached);
    }
    return rank;
  });

  /**
   * The laid-out picture.
   *
   * A `resource` because ELK is asynchronous and lives in a worker: the request is the graph and
   * the ranks, so a new graph re-runs the layout and nothing else does.
   */
  private readonly layout = resource({
    params: () => ({ graph: this.graph(), ranks: this.ranks() }),
    loader: async ({ params }) =>
      this.layoutService.layout({
        nodes: params.graph.nodes.map((node) => ({
          id: node.ref,
          height: NODE_HEIGHT,
          partition: params.ranks.get(node.ref) ?? 0,
        })),
        edges: params.graph.edges.map((edge, index) => ({
          id: `e${index}`,
          source: edge.source,
          target: edge.target,
        })),
      }),
  });

  protected readonly isLoading = computed(() => this.layout.isLoading());
  protected readonly failed = computed(() => this.layout.error() !== undefined);

  private readonly boxes = computed(() => {
    const result = this.layout.hasValue() ? this.layout.value() : { nodes: [], edges: [] };
    return new Map<string, LayoutBox>(result.nodes.map((box) => [box.id, box]));
  });

  protected readonly placed = computed<PlacedNode[]>(() => {
    const boxes = this.boxes();
    return this.graph()
      .nodes.map((node) => {
        const box = boxes.get(node.ref);
        return box ? { ref: node.ref, node, x: box.x, y: box.y } : null;
      })
      .filter((placed): placed is PlacedNode => placed !== null);
  });

  protected readonly drawn = computed<DrawnEdge[]>(() => {
    const boxes = this.boxes();
    const routed = this.layout.hasValue() ? this.layout.value().edges : [];
    const byId = new Map(routed.map((edge) => [edge.id, edge]));

    return this.graph()
      .edges.map((edge, index) => {
        const source = boxes.get(edge.source);
        const target = boxes.get(edge.target);
        if (!source || !target) return null;

        // ELK's own bend points where it produced them, and a local route where it did not — the
        // same fallback the DOORS canvas uses, and the reason both draw an edge at all when the
        // layout is still catching up with a changed graph.
        const points = byId.get(`e${index}`)?.points ?? localRoute(source, target);

        return {
          id: `e${index}`,
          path: roundedPolyline(points),
          label: edgeLabel(edge),
          subTask: edge.subTask,
        };
      })
      .filter((edge): edge is DrawnEdge => edge !== null);
  });

  /** The drawing's extent, so the scroll container knows how much there is. */
  protected readonly extent = computed(() => {
    const boxes = [...this.boxes().values()];
    return {
      width: Math.max(NODE_WIDTH, ...boxes.map((box) => box.x + box.width)) + 48,
      height: Math.max(NODE_HEIGHT, ...boxes.map((box) => box.y + box.height)) + 48,
    };
  });

  protected readonly transform = computed(() => `scale(${this.zoom()})`);

  protected zoomIn(): void {
    this.zoom.update((value) => Math.min(2, Math.round(value * 110) / 100));
  }

  protected zoomOut(): void {
    this.zoom.update((value) => Math.max(0.4, Math.round(value * 90) / 100));
  }

  protected resetZoom(): void {
    this.zoom.set(1);
  }
}

/** What an edge says it is. JIRA names its link types, so the picture can name them too (§9.4). */
function edgeLabel(edge: JiraGraphEdge): string {
  if (edge.subTask) return 'Sub-task of';
  return edge.typeName ?? 'Linked';
}
