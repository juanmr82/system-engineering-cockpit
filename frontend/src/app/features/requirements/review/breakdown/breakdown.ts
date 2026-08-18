import { Component, computed, inject, input, linkedSignal } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { DependencyGraphDialog } from '../../graph/dependency-graph-dialog';
import { BreakdownRowComponent } from './breakdown-row';
import { MAX_ROWS, buildTree, flatten } from './breakdown.model';
import type { BreakdownResponse, BreakdownTree } from './breakdown.model';

/**
 * Requirements → Req review → Breakdown (docs/requirement-breakdown-tree.md).
 *
 * Where one requirement sits in the system's decomposition: the top-level requirement it
 * ultimately traces up to, and everything that decomposes it, down to L3/L4. A *breakdown tree*,
 * not a flat trace list — that is already the References column and is untouched by this.
 *
 * Pure read, and pure reading: nothing here navigates. The tree is a function of the imported graph
 * and of `:__AttributeSetting` configuration, both evaluated fresh on every request (§6), and the
 * only state the tab owns is which rows the reviewer has collapsed.
 */
@Component({
  selector: 'sec-breakdown',
  imports: [
    BreakdownRowComponent,
    MatButtonModule,
    MatIconModule,
    MatProgressBarModule,
    MatTooltipModule,
  ],
  templateUrl: './breakdown.html',
  styleUrl: './breakdown.scss',
})
export class Breakdown {
  private readonly dialog = inject(MatDialog);

  readonly itemRef = input.required<string>();

  // Created here, in the tab, so the request goes out when the tab is first opened and not before:
  // the Attributes tab is unaffected and keeps its own data source (§7).
  protected readonly breakdown = httpResource<BreakdownResponse>(
    () => `/api/v1/items/${this.itemRef()}/breakdown`,
  );

  protected readonly tree = computed(() => {
    const response = this.breakdown.value();
    return response ? buildTree(response) : null;
  });

  /**
   * Which rows the reviewer has closed.
   *
   * Collapsed, not expanded: every requirement shows its statement and its verification attributes
   * by default, and collapsing is what you do to a branch you are done with. That makes the empty
   * set the correct state for any tree, so `linkedSignal` only has to clear it when the tree
   * changes rather than recompute a default per tree.
   *
   * Keyed by rendered position, not by ref — a requirement drawn under two parents is two rows, and
   * collapsing one must leave the other open.
   */
  protected readonly collapsed = linkedSignal<BreakdownTree | null, ReadonlySet<string>>({
    source: this.tree,
    computation: () => new Set<string>(),
  });

  protected readonly rows = computed(() => {
    const tree = this.tree();
    return tree ? flatten(tree, this.collapsed()) : [];
  });

  protected readonly rootCount = computed(() => this.tree()?.roots.length ?? 0);
  protected readonly truncated = computed(() => this.tree()?.truncated ?? false);
  protected readonly capped = computed(() => this.tree()?.capped ?? false);
  /** Every node in the closure, not just the drawn ones — what the truncation footer counts. */
  protected readonly nodeCount = computed(() => this.breakdown.value()?.nodes.length ?? 0);
  protected readonly maxRows = MAX_ROWS;

  protected toggle(key: string): void {
    const next = new Set(this.collapsed());
    if (!next.delete(key)) {
      next.add(key);
    }
    this.collapsed.set(next);
  }

  /**
   * Whether there is anything to open a graph on.
   *
   * The scope is the requirement this tab is open on, and it only exists once the response has
   * arrived and confirmed the object is real — opening a graph on a reference that turned out to be
   * a 404 would put an error dialog on top of an error panel.
   */
  protected readonly hasScope = computed(() => this.breakdown.hasValue());

  /**
   * The same requirements, drawn as a graph rather than as a tree
   * (docs/REQ_BREAKDOWN_GRAPH_VIEW.md §2).
   *
   * Enabled only once there is a scope — the requirement this tab is open on. **Never an unscoped
   * whole-module graph** (§8): a module is twelve thousand objects, which is an unreadable hairball
   * and a rendering problem this feature does not need to have.
   */
  protected openGraph(): void {
    DependencyGraphDialog.open(this.dialog, {
      seedRef: this.itemRef(),
      seedId: this.selectedId(),
    });
  }

  /** What the tree already knows the opened requirement is called, so the dialog's header is
   *  populated before its own response arrives. */
  private readonly selectedId = computed(() => {
    const response = this.breakdown.value();
    return response?.nodes.find((node) => node.ref === response.selectedRef)?.id ?? null;
  });

  protected retry(): void {
    this.breakdown.reload();
  }
}
