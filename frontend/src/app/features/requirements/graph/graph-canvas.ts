import {
  Component,
  ElementRef,
  afterRenderEffect,
  computed,
  effect,
  inject,
  input,
  output,
  resource,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RequirementCard } from '../../../shared/requirement-card/requirement-card';
import { localRoute, roundedPolyline, selfLoopPath } from './layout/edge-path';
import { GraphLayoutService } from './layout/graph-layout.service';
import {
  CARD_FALLBACK_HEIGHT,
  CARD_WIDTH,
  compressBands,
  dedupeEdges,
  isFeedbackEdge,
  isSelfLoop,
  orderEdges,
  partitionMap,
} from './layout/graph-layout';
import type { BandBox, LayoutBox } from './layout/graph-layout';
import type { DependencyGraph, GraphNode } from './graph.model';

/** One node, placed. */
interface PositionedNode {
  readonly ref: string;
  readonly node: GraphNode;
  readonly x: number;
  readonly y: number;
  readonly height: number;
}

/** One edge, as the four things the template needs to draw it. */
interface DrawnEdge {
  readonly id: string;
  readonly source: string;
  readonly target: string;
  readonly path: string;
  readonly kind: 'normal' | 'unresolved' | 'feedback' | 'loop';
}

/** Where the level label sits on screen, having followed the canvas down but not across (§4.4). */
interface LaneLabel {
  readonly label: string;
  readonly top: number;
  readonly height: number;
  readonly alternate: boolean;
}

const ZOOM_MIN = 0.25;
const ZOOM_MAX = 2;
const ZOOM_STEP = 1.1;

/** Below this, a card is its identity only: body text scaled this far down is unreadable (§5.5). */
const COMPACT_BELOW = 0.5;

const FIT_PADDING = 48;

/**
 * The dependency graph canvas (docs/REQ_BREAKDOWN_GRAPH_VIEW.md §5).
 *
 * HTML cards over an SVG edge layer, all of it under one composited transform. **Not
 * `<foreignObject>`**: text wrapping, Material theming, focus handling and printing all misbehave
 * inside it.
 *
 * The pipeline is measure → lay out → compress → draw, and each step is a signal, so a depth change
 * re-runs exactly the part that depends on it. The maths is all in `layout/`, pure and unit-tested;
 * what is here is the parts that need a DOM.
 */
@Component({
  selector: 'sec-graph-canvas',
  imports: [MatTooltipModule, RequirementCard],
  templateUrl: './graph-canvas.html',
  styleUrl: './graph-canvas.scss',
  providers: [GraphLayoutService],
  host: {
    class: 'sec-graph',
    '(wheel)': 'onWheel($event)',
    '(pointerdown)': 'onPointerDown($event)',
    '(pointermove)': 'onPointerMove($event)',
    '(pointerup)': 'onPointerUp($event)',
    '(pointercancel)': 'onPointerUp($event)',
    '(keydown)': 'onKeyDown($event)',
    tabindex: '0',
    role: 'application',
  },
})
export class GraphCanvas {
  private readonly layoutService = inject(GraphLayoutService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly graph = input.required<DependencyGraph>();

  /** A node was double-clicked: the dialog re-seeds on it (§5.7). */
  readonly reseeded = output<string>();

  private readonly measureHost = viewChild<ElementRef<HTMLElement>>('measure');
  private readonly viewport = viewChild<ElementRef<HTMLElement>>('viewport');

  // -- View transform ------------------------------------------------------------------------

  protected readonly view = signal({ x: 0, y: 0, scale: 1 });

  /**
   * Set the moment the user pans, zooms, drags or expands a card's text, and never cleared.
   *
   * Auto-fit runs on open and on resize, and stops for good once this is set: re-fitting after
   * someone has arranged the view yanks it out from under them, which is worse than a view that is
   * slightly the wrong size (§5.5).
   */
  private readonly viewportDirty = signal(false);

  protected readonly viewTransform = computed(() => {
    const view = this.view();
    return `matrix(${view.scale}, 0, 0, ${view.scale}, ${view.x}, ${view.y})`;
  });

  /** Public: the dialog header reads it rather than keeping a second copy of the scale. */
  readonly zoomPercent = computed(() => Math.round(this.view().scale * 100));

  /** The level-of-detail switch, driven by the zoom signal rather than by a CSS scale (§5.5). */
  protected readonly compact = computed(() => this.view().scale < COMPACT_BELOW);

  // -- Measure -------------------------------------------------------------------------------

  private readonly measured = signal<ReadonlyMap<string, number>>(new Map());
  private readonly fontsReady = signal(false);

  protected readonly nodes = computed(() => this.graph().nodes);

  protected readonly cardWidth = CARD_WIDTH;

  /**
   * The nodes whose description is shown in full (§5.1).
   *
   * Held here rather than inside each card because an expanded card is a **taller** card, and
   * every position on screen was computed from a height measured before the click. So the set
   * feeds the measure pass, the measure pass produces new heights, and the graph is laid out
   * again — the one path that already exists for a height changing. The alternative, letting a
   * card grow where it stands, is cards overlapping the ones beneath them.
   *
   * Keyed by `ref` and never pruned: a ref that leaves the graph costs one string, and re-seeding
   * back onto a requirement someone had opened finds it open, which is what they left.
   */
  private readonly expandedText = signal<ReadonlySet<string>>(new Set());

  protected isTextExpanded(ref: string): boolean {
    return this.expandedText().has(ref);
  }

  protected setTextExpanded(ref: string, expanded: boolean): void {
    // The view is now the reader's, for the same reason a pan makes it theirs. Expanding a card
    // produces a new layout, and auto-fit would answer a taller diagram by scaling the whole thing
    // down — past the compact threshold on a large graph, which drops every card's body and takes
    // the text that was just asked for with it.
    this.markDirty();

    const next = new Set(this.expandedText());
    if (expanded) {
      next.add(ref);
    } else {
      next.delete(ref);
    }
    this.expandedText.set(next);
  }

  constructor() {
    // Measuring against the fallback font produces heights wrong by enough to overlap edges, so
    // the pass runs again once the real font has arrived (§5.2). `document.fonts` is absent in
    // jsdom, where there is no layout to be wrong about either.
    document.fonts?.ready.then(() => this.fontsReady.set(true));

    afterRenderEffect({
      read: () => {
        // All three are dependencies: a new node set needs measuring, so does the same node set
        // once the font it is set in has changed underneath it, and so does a card whose text has
        // just been expanded — that is the whole mechanism by which the graph makes room for it.
        const nodes = this.nodes();
        this.fontsReady();
        this.expandedText();

        const host = this.measureHost()?.nativeElement;
        if (!host || nodes.length === 0) {
          return;
        }

        const heights = new Map<string, number>();
        for (const element of host.querySelectorAll<HTMLElement>('[data-ref]')) {
          const ref = element.dataset['ref'];
          if (ref) {
            // jsdom reports 0 for everything, which is not a measurement — the fallback is what
            // lets a spec exercise the real pipeline rather than a mock of it.
            heights.set(ref, element.offsetHeight || CARD_FALLBACK_HEIGHT);
          }
        }

        untracked(() => {
          if (!sameHeights(this.measured(), heights)) {
            this.measured.set(heights);
          }
        });
      },
    });

    // Fit once the first layout lands, and again on resize — but only while the user has not taken
    // the view over.
    effect(() => {
      const layout = this.layout.value();
      if (layout && !untracked(() => this.viewportDirty())) {
        untracked(() => this.fit());
      }
    });
  }

  // -- Layout --------------------------------------------------------------------------------

  private readonly bands = computed(() => this.graph().levels);

  private readonly partitions = computed(() => partitionMap(this.nodes(), this.bands()));

  /** Deduplicated, deterministically ordered, self-loops kept (§4.5, §4.6). */
  private readonly edges = computed(() => orderEdges(dedupeEdges(this.graph().edges), this.nodes()));

  /**
   * ELK, then band compression.
   *
   * A `resource` rather than an effect: it cancels a layout that has been superseded, which the
   * depth control makes easy to trigger, and it carries the loading and error states the template
   * needs without a hand-rolled triple.
   */
  protected readonly layout = resource({
    params: () => {
      const heights = this.measured();
      const nodes = this.nodes();
      // Idle until the measure pass has run: laying out against fallback heights and then again
      // against real ones means two layouts and a visible jump on every open.
      if (nodes.length > 0 && heights.size === 0) {
        return undefined;
      }
      return { nodes, heights, edges: this.edges(), bands: this.bands(), partitions: this.partitions() };
    },
    loader: async ({ params }) => {
      const result = await this.layoutService.layout({
        nodes: params.nodes.map((node) => ({
          id: node.card.ref,
          height: params.heights.get(node.card.ref) ?? CARD_FALLBACK_HEIGHT,
          partition: params.partitions.get(node.card.ref) ?? 0,
        })),
        edges: params.edges.map((edge, index) => ({
          id: `e${index}`,
          source: edge.source,
          target: edge.target,
        })),
      });

      return compressBands(result.nodes, result.edges, params.partitions, params.bands);
    },
  });

  private readonly boxes = computed<ReadonlyMap<string, LayoutBox>>(() => {
    const layout = this.layout.hasValue() ? this.layout.value() : null;
    return new Map(layout?.nodes.map((box) => [box.id, box]) ?? []);
  });

  protected readonly positioned = computed<PositionedNode[]>(() => {
    const boxes = this.boxes();
    return this.nodes().flatMap((node) => {
      const box = boxes.get(node.card.ref);
      return box ? [{ ref: node.card.ref, node, x: box.x, y: box.y, height: box.height }] : [];
    });
  });

  protected readonly bandBoxes = computed<BandBox[]>(() =>
    this.layout.hasValue() ? this.layout.value().bands : [],
  );

  protected readonly extent = computed(() => {
    const layout = this.layout.hasValue() ? this.layout.value() : null;
    return { width: layout?.width ?? 0, height: layout?.height ?? 0 };
  });

  /**
   * The lane labels, in screen space.
   *
   * They follow the canvas vertically but stay pinned to the left edge during horizontal pan
   * (§4.4), which is why they are computed here rather than drawn inside the transform — a label
   * inside it would scroll away with the diagram and scale with it.
   */
  protected readonly laneLabels = computed<LaneLabel[]>(() => {
    const view = this.view();
    return this.bandBoxes().map((band, index) => ({
      label: band.label,
      top: band.top * view.scale + view.y,
      height: (band.bottom - band.top) * view.scale,
      alternate: index % 2 === 1,
    }));
  });

  // -- Edges ---------------------------------------------------------------------------------

  protected readonly drawn = computed<DrawnEdge[]>(() => {
    const layout = this.layout.hasValue() ? this.layout.value() : null;
    const boxes = this.boxes();
    const unresolved = new Set(
      this.nodes().filter((node) => !node.card.resolved).map((node) => node.card.ref),
    );
    const routed = new Map(layout?.edges.map((edge) => [`${edge.source} ${edge.target}`, edge]) ?? []);

    return this.edges().flatMap<DrawnEdge>((edge, index) => {
      const source = boxes.get(edge.source);
      const target = boxes.get(edge.target);
      if (!source || !target) {
        return [];
      }

      if (isSelfLoop(edge)) {
        return [
          {
            id: `e${index}`,
            source: edge.source,
            target: edge.target,
            path: selfLoopPath(source),
            kind: 'loop',
          },
        ];
      }

      // ELK's bend points when it produced any, the local router otherwise — same corner radius
      // and same stroke, so the two are visually indistinguishable (§5.6).
      const elkRoute = routed.get(`${edge.source} ${edge.target}`)?.points;
      const points = elkRoute && elkRoute.length >= 2 ? elkRoute : localRoute(source, target);

      return [
        {
          id: `e${index}`,
          source: edge.source,
          target: edge.target,
          path: roundedPolyline(points),
          kind: unresolved.has(edge.target)
            ? 'unresolved'
            : isFeedbackEdge(edge, boxes)
              ? 'feedback'
              : 'normal',
        },
      ];
    });
  });

  // -- Hover highlighting --------------------------------------------------------------------

  protected readonly hovered = signal<string | null>(null);

  /** The refs whose edges are lit. Empty means nothing is hovered and nothing is dimmed. */
  private readonly lit = computed(() => {
    const ref = this.hovered();
    if (!ref) {
      return new Set<string>();
    }
    const incident = new Set<string>([ref]);
    for (const edge of this.edges()) {
      if (edge.source === ref) {
        incident.add(edge.target);
      }
      if (edge.target === ref) {
        incident.add(edge.source);
      }
    }
    return incident;
  });

  protected readonly dimming = computed(() => this.hovered() !== null);

  protected isLit(ref: string): boolean {
    return this.lit().has(ref);
  }

  protected isEdgeLit(edge: DrawnEdge): boolean {
    const ref = this.hovered();
    return ref !== null && (edge.source === ref || edge.target === ref);
  }

  // -- The accessible equivalent ---------------------------------------------------------------

  /**
   * The same nodes and edges as a table, adjacent in the DOM and never conditional (§5.7).
   *
   * A node-link diagram is not navigable by a screen reader on its own, and an SVG canvas is
   * invisible to jsdom — so this is both the accessible equivalent *and* what a spec can assert
   * about the picture. It is the same discipline ADR 0008 applies to every chart.
   */
  protected readonly readingRows = computed(() => {
    const byRef = new Map(this.nodes().map((node) => [node.card.ref, node]));
    const name = (ref: string) => {
      const node = byRef.get(ref);
      if (!node) {
        return 'an object outside this graph';
      }
      return node.card.id ?? (node.card.moduleName ? `not yet imported (${node.card.moduleName})` : 'not yet imported');
    };

    const refines = new Map<string, string[]>();
    const refinedBy = new Map<string, string[]>();
    for (const edge of this.edges()) {
      push(refines, edge.source, name(edge.target));
      push(refinedBy, edge.target, name(edge.source));
    }

    return this.nodes().map((node) => ({
      ref: node.card.ref,
      name: name(node.card.ref),
      level: node.card.level?.label ?? 'No system level set',
      // Spelled out rather than left blank: "no incoming links" is a fact worth stating, and an
      // empty cell reads as something the table failed to show (R5).
      refines: refines.get(node.card.ref)?.join(', ') ?? 'nothing in this graph',
      refinedBy: refinedBy.get(node.card.ref)?.join(', ') ?? 'no incoming links',
    }));
  });

  // -- Zoom and pan --------------------------------------------------------------------------

  /**
   * A browser pinch gesture arrives as a wheel event with `ctrlKey` set, so pinch and ctrl+wheel
   * are one code path. **Plain wheel must not zoom** — hijacking it is the single most disliked
   * behaviour in diagram tools, and it breaks scrolling for anyone who lands here by accident.
   */
  protected onWheel(event: WheelEvent): void {
    if (event.ctrlKey || event.metaKey) {
      event.preventDefault();
      this.zoomAt(event.deltaY < 0 ? ZOOM_STEP : 1 / ZOOM_STEP, event.clientX, event.clientY);
      return;
    }

    event.preventDefault();
    this.markDirty();
    const view = this.view();
    if (event.shiftKey) {
      this.view.set({ ...view, x: view.x - event.deltaY });
    } else {
      this.view.set({ ...view, x: view.x - event.deltaX, y: view.y - event.deltaY });
    }
  }

  private panning: { pointerId: number; x: number; y: number } | null = null;

  protected onPointerDown(event: PointerEvent): void {
    // Drag on empty canvas, or middle-drag anywhere. A drag starting on a card is left alone —
    // moving nodes by hand is the next pass, and swallowing the gesture now would make a card
    // un-selectable in the meantime.
    const onCard = (event.target as HTMLElement).closest('.sec-graph__node') !== null;
    if (event.button !== 1 && (event.button !== 0 || onCard)) {
      return;
    }
    this.panning = { pointerId: event.pointerId, x: event.clientX, y: event.clientY };
    (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
    event.preventDefault();
  }

  protected onPointerMove(event: PointerEvent): void {
    const panning = this.panning;
    if (!panning || panning.pointerId !== event.pointerId) {
      return;
    }
    this.markDirty();
    const view = this.view();
    this.view.set({
      ...view,
      x: view.x + (event.clientX - panning.x),
      y: view.y + (event.clientY - panning.y),
    });
    this.panning = { ...panning, x: event.clientX, y: event.clientY };
  }

  protected onPointerUp(event: PointerEvent): void {
    if (this.panning?.pointerId === event.pointerId) {
      this.panning = null;
    }
  }

  protected onKeyDown(event: KeyboardEvent): void {
    if ((event.ctrlKey || event.metaKey) && event.key === '0') {
      event.preventDefault();
      this.resetZoom();
      return;
    }
    switch (event.key) {
      case '+':
      case '=':
        event.preventDefault();
        this.zoomIn();
        break;
      case '-':
        event.preventDefault();
        this.zoomOut();
        break;
      case 'f':
      case 'F':
        event.preventDefault();
        this.viewportDirty.set(false);
        this.fit();
        break;
      default:
        break;
    }
  }

  zoomIn(): void {
    this.zoomAtCentre(ZOOM_STEP);
  }

  zoomOut(): void {
    this.zoomAtCentre(1 / ZOOM_STEP);
  }

  /** Back to 100%, centred on the seeds — the one gesture that always gets you un-lost. */
  resetZoom(): void {
    this.markDirty();
    const seeds = this.positioned().filter((node) => node.node.seed);
    const box = this.viewportBox();
    if (seeds.length === 0) {
      this.view.set({ x: 0, y: 0, scale: 1 });
      return;
    }
    const x = seeds.reduce((sum, node) => sum + node.x + CARD_WIDTH / 2, 0) / seeds.length;
    const y = seeds.reduce((sum, node) => sum + node.y + node.height / 2, 0) / seeds.length;
    this.view.set({ x: box.width / 2 - x, y: box.height / 2 - y, scale: 1 });
  }

  /**
   * Fit the whole diagram, **clamped to a maximum of 100%** (§2.1).
   *
   * A four-node graph renders at natural size, centred, with whitespace around it. Blowing four
   * cards up to fill a 92vw dialog looks broken, and the whitespace correctly says "this is all
   * there is".
   */
  fit(): void {
    const extent = this.extent();
    const box = this.viewportBox();
    if (extent.width === 0 || extent.height === 0 || box.width === 0) {
      return;
    }

    const scale = Math.min(
      1,
      (box.width - FIT_PADDING * 2) / extent.width,
      (box.height - FIT_PADDING * 2) / extent.height,
    );
    const clamped = Math.max(ZOOM_MIN, scale);
    this.view.set({
      x: (box.width - extent.width * clamped) / 2,
      y: (box.height - extent.height * clamped) / 2,
      scale: clamped,
    });
  }

  private zoomAtCentre(factor: number): void {
    const box = this.viewportBox();
    this.zoomAt(factor, box.left + box.width / 2, box.top + box.height / 2);
  }

  /** Zoom about a screen point, so the thing under the cursor stays under the cursor. */
  private zoomAt(factor: number, clientX: number, clientY: number): void {
    this.markDirty();
    const view = this.view();
    const next = clamp(view.scale * factor, ZOOM_MIN, ZOOM_MAX);
    if (next === view.scale) {
      return;
    }
    const box = this.viewportBox();
    const originX = clientX - box.left;
    const originY = clientY - box.top;
    const ratio = next / view.scale;
    this.view.set({
      x: originX - (originX - view.x) * ratio,
      y: originY - (originY - view.y) * ratio,
      scale: next,
    });
  }

  private viewportBox(): DOMRect {
    const element = this.viewport()?.nativeElement ?? this.host.nativeElement;
    return element.getBoundingClientRect();
  }

  private markDirty(): void {
    if (!this.viewportDirty()) {
      this.viewportDirty.set(true);
    }
  }

  protected onNodeDoubleClick(event: MouseEvent, ref: string, resolved: boolean): void {
    // A control on the card is not the card. Someone toggling the full text twice in quick
    // succession has double-clicked a button, not a node, and re-seeding the whole graph out from
    // under them is the last thing they asked for.
    if ((event.target as HTMLElement).closest('button')) {
      return;
    }

    // A placeholder has nothing behind it, so re-seeding on one would return a graph of one node
    // and lose the picture the user was reading (§5.4).
    if (resolved) {
      this.reseeded.emit(ref);
    }
  }
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

function push(map: Map<string, string[]>, key: string, value: string): void {
  const existing = map.get(key);
  if (existing) {
    existing.push(value);
  } else {
    map.set(key, [value]);
  }
}

function sameHeights(a: ReadonlyMap<string, number>, b: ReadonlyMap<string, number>): boolean {
  if (a.size !== b.size) {
    return false;
  }
  for (const [key, value] of a) {
    if (b.get(key) !== value) {
      return false;
    }
  }
  return true;
}
