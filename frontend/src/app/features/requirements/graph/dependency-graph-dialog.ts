import { Component, computed, debounced, inject, signal, viewChild } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { SEC_MODAL_DIALOG } from '../../../shared/dialog/modal-dialog.config';
import { GraphApi } from './graph-api.service';
import { GraphCanvas } from './graph-canvas';
import { DEFAULT_SCOPE, MAX_DEPTH, MIN_DEPTH } from './graph.model';
import type { DependencyGraph, GraphDirection, GraphLevelStrategy, GraphScope } from './graph.model';

export interface DependencyGraphDialogData {
  readonly seedRef: string;
  /** What the toolbar knew the seed was called. Only ever used until the response arrives. */
  readonly seedId: string | null;
}

/**
 * Wording for the two closed vocabularies the controls offer.
 *
 * Named by the relation, never by "upstream" and "downstream": an outgoing `refersTo` is read here
 * as *this requirement refines its target*, so following it goes **up** the decomposition — and two
 * words for one arrow pointing opposite ways is how a reviewer reads a traceability picture
 * backwards. The server's alias map says the same thing; this is the client's half of it.
 */
const DIRECTION_LABELS: Record<GraphDirection, string> = {
  OUTGOING: 'What these refine',
  INCOMING: 'What refines these',
  BOTH: 'Both directions',
};

const LEVEL_LABELS: Record<GraphLevelStrategy, string> = {
  MODULE_SYSTEM_LEVEL: 'System level of the module',
  OUTLINE_LEVEL: 'Outline level in the module',
  GRAPH_RANK: 'Position in this graph',
};

/**
 * The dependency graph dialog (docs/REQ_BREAKDOWN_GRAPH_VIEW.md §2).
 *
 * Opens at a fixed near-fullscreen size and fits the *diagram* inside it, rather than sizing itself
 * to the diagram: above about thirty nodes a graph is already wider than any screen, so "fit the
 * dialog to the diagram" collapses into "fullscreen" for every scope that needs it, and the extent
 * changes on every control change — which would leave the frame jumping under the cursor (§2.1).
 *
 * **Read-only.** Nothing in it writes, so R7's save contract has nothing to hold: there is no dirty
 * state, and Escape closing the dialog can never discard anything.
 */
@Component({
  selector: 'sec-dependency-graph-dialog',
  imports: [
    GraphCanvas,
    MatButtonModule,
    MatDialogModule,
    MatIconModule,
    MatMenuModule,
    MatProgressBarModule,
    MatTooltipModule,
  ],
  templateUrl: './dependency-graph-dialog.html',
  styleUrl: './dependency-graph-dialog.scss',
})
export class DependencyGraphDialog {
  private readonly data = inject<DependencyGraphDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject<MatDialogRef<DependencyGraphDialog>>(MatDialogRef);

  private readonly canvas = viewChild(GraphCanvas);

  protected readonly minDepth = MIN_DEPTH;
  protected readonly maxDepth = MAX_DEPTH;
  protected readonly directions: GraphDirection[] = ['BOTH', 'OUTGOING', 'INCOMING'];
  protected readonly strategies: GraphLevelStrategy[] = [
    'MODULE_SYSTEM_LEVEL',
    'OUTLINE_LEVEL',
    'GRAPH_RANK',
  ];

  /**
   * The seed can change: double-clicking a node re-roots the picture on it (§5.7). It starts as
   * the requirement the breakdown tree was open on.
   */
  protected readonly scope = signal<GraphScope>({ seedRef: this.data.seedRef, ...DEFAULT_SCOPE });

  /**
   * Debounced, because every control change is a round trip plus a re-layout (§6). The *signal* is
   * debounced rather than the handlers, so the buttons stay instant and only the request waits.
   */
  private readonly requested = debounced(this.scope, 250);

  protected readonly graph = httpResource<DependencyGraph>(() => {
    // `debounced` is a Resource, so it is empty for the first tick and its value has to be read
    // through `hasValue()`. Falling back to the live scope is what keeps the *first* request
    // immediate — a debounce on opening the dialog is 250ms of blank canvas for no benefit.
    const scope = this.requested.hasValue() ? this.requested.value() : this.scope();
    return GraphApi.url(scope);
  });

  protected readonly value = computed(() => (this.graph.hasValue() ? this.graph.value() : null));

  protected readonly seedName = computed(() => {
    const graph = this.value();
    const seedRef = this.scope().seedRef;
    return graph?.nodes.find((node) => node.card.ref === seedRef)?.card.id ?? this.data.seedId ?? '';
  });

  /** The header's one-line summary of what is on screen. */
  protected readonly summary = computed(() => {
    const graph = this.value();
    if (!graph) {
      return '';
    }
    const hops = graph.depth === 1 ? '1 hop' : `${graph.depth} hops`;
    const objects = graph.nodes.length === 1 ? '1 object' : `${graph.nodes.length} objects`;
    return `${this.seedName()} + ${hops}, ${objects}`;
  });

  protected readonly unresolved = computed(() => this.value()?.unresolvedModules ?? []);
  protected readonly truncated = computed(() => this.value()?.truncated ?? false);
  protected readonly empty = computed(() => (this.value()?.nodes.length ?? 0) <= 1);

  protected readonly directionLabel = computed(() => DIRECTION_LABELS[this.scope().direction]);
  protected readonly levelLabel = computed(() => LEVEL_LABELS[this.scope().levelStrategy]);

  protected readonly maximised = signal(false);
  protected readonly legendOpen = signal(true);

  protected labelFor(direction: GraphDirection): string {
    return DIRECTION_LABELS[direction];
  }

  protected strategyLabel(strategy: GraphLevelStrategy): string {
    return LEVEL_LABELS[strategy];
  }

  protected setDepth(depth: number): void {
    this.scope.update((scope) => ({ ...scope, depth: clamp(depth, MIN_DEPTH, MAX_DEPTH) }));
  }

  protected setDirection(direction: GraphDirection): void {
    this.scope.update((scope) => ({ ...scope, direction }));
  }

  protected setLevelStrategy(levelStrategy: GraphLevelStrategy): void {
    this.scope.update((scope) => ({ ...scope, levelStrategy }));
  }

  /** Double-click on a node: re-root the picture on it, keeping every other control as it was. */
  protected reseed(seedRef: string): void {
    this.scope.update((scope) => ({ ...scope, seedRef }));
  }

  protected zoomIn(): void {
    this.canvas()?.zoomIn();
  }

  protected zoomOut(): void {
    this.canvas()?.zoomOut();
  }

  protected fit(): void {
    this.canvas()?.fit();
  }

  protected resetZoom(): void {
    this.canvas()?.resetZoom();
  }

  /** The canvas owns the view transform, so the header reads its zoom rather than tracking one. */
  protected readonly zoomPercent = computed(() => this.canvas()?.zoomPercent() ?? 100);

  /** 92% ↔ 100% of the screen, with the chrome condensed. Genuinely useful on a projector. */
  protected toggleMaximised(): void {
    const next = !this.maximised();
    this.maximised.set(next);
    this.dialogRef.updateSize(next ? '100vw' : '92vw', next ? '100vh' : '92vh');
  }

  protected retry(): void {
    this.graph.reload();
  }

  protected close(): void {
    this.dialogRef.close();
  }

  /**
   * The one way to open it, so no call site can size it wrongly or forget the modal contract.
   *
   * Deliberately **not** `disableClose`-free: `SEC_MODAL_DIALOG` carries R7's contract and is
   * spread here like everywhere else. Escape is wired to close explicitly below, which is safe
   * precisely because this dialog writes nothing.
   */
  static open(dialog: MatDialog, data: DependencyGraphDialogData) {
    const ref = dialog.open<DependencyGraphDialog, DependencyGraphDialogData, void>(
      DependencyGraphDialog,
      {
        ...SEC_MODAL_DIALOG,
        width: '92vw',
        height: '92vh',
        maxWidth: 'none',
        minWidth: '960px',
        minHeight: '600px',
        panelClass: 'sec-graph-dialog',
        ariaLabel: 'Dependency graph',
        data,
      },
    );
    // A read-only dialog has nothing to lose, so Escape closes it — the reason `disableClose` is
    // set at all is to stop a *dirty* dialog being dismissed, and this one can never be dirty.
    ref.keydownEvents().subscribe((event) => {
      if (event.key === 'Escape') {
        ref.close();
      }
    });
    return ref;
  }
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}
