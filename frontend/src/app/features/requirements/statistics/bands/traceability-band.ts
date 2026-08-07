import { Component, computed, input, output, signal } from '@angular/core';
import { ChartControls } from '../../../../shared/charts/chart-controls';
import { StackedBarChart } from '../../../../shared/charts/stacked-bar-chart';
import type {
  ChartMode,
  ChartSelection,
  ChartSort,
  StackedRow,
  StackedSeries,
} from '../../../../shared/charts/chart.model';
import type { DanglingTarget, ModuleStatistics, Parentage } from '../statistics.model';

/**
 * Band 3 — traceability (requirements-statistics.md §6).
 *
 * Three segments, not two. A requirement whose only link points at an object no import has reached
 * is an *import-scope* problem; one with no link at all is a *data* problem. They are fixed by
 * different people, so they are never merged (§12.4).
 *
 * `A -[refersTo]-> B` reads as "A refines B", so the parent is the outgoing target — the same
 * convention as the Breakdown tab, and it must not be inverted here.
 */
const SERIES: readonly StackedSeries[] = [
  { key: 'hasParent', label: 'Has a parent', token: 'sec-highlight-verified' },
  { key: 'parentNotImported', label: 'Parent not yet imported', token: 'sec-highlight-undefined' },
  { key: 'orphans', label: 'No parent', token: 'sec-highlight-error' },
];

@Component({
  selector: 'sec-traceability-band',
  imports: [ChartControls, StackedBarChart],
  templateUrl: './traceability-band.html',
  styleUrl: './traceability-band.scss',
})
export class TraceabilityBand {
  readonly parentage = input<Parentage | null>(null);
  readonly modules = input<readonly ModuleStatistics[]>([]);
  readonly danglingTargets = input<readonly DanglingTarget[]>([]);
  readonly modulesWithoutSystemLevel = input<readonly string[]>([]);

  readonly moduleSelect = output<string>();

  protected readonly mode = signal<ChartMode>('absolute');
  protected readonly sort = signal<ChartSort>('value');

  protected readonly series = SERIES;

  /** Only modules the question applies to — an L0 module has nothing above it to refine. */
  protected readonly rows = computed<StackedRow[]>(() =>
    this.modules()
      .filter((module) => module.parentage.applicable)
      .map((module) => ({
        key: module.ref,
        label: module.name,
        values: {
          hasParent: module.parentage.hasParent,
          parentNotImported: module.parentage.parentNotImported,
          orphans: module.parentage.orphans,
        },
      })),
  );

  protected readonly orphanCount = computed(() => this.parentage()?.orphans ?? 0);

  protected readonly danglingCount = computed(() =>
    this.modules().reduce((sum, module) => sum + module.danglingLinks, 0),
  );

  /**
   * The dangling targets split by whether we can say which module they are.
   *
   * A target module carries a name only once it has been imported (§6.2: "named where the module
   * node exists"), and the placeholder the importer leaves behind holds the name of the *object*
   * that was linked to, never of its module. So for a module nothing has reached, the only
   * identifier in the graph is its `doors://` URL, which R5 keeps off the screen.
   *
   * Listing those one per line printed the same sentence as many times as there were modules,
   * which reads as a repeated row rather than as three distinct modules we cannot name. The count
   * is the part worth keeping — it is how many imports would clear the links reported above.
   */
  protected readonly namedTargets = computed(() =>
    this.danglingTargets().filter((target): target is DanglingTarget & { name: string } =>
      target.name !== null,
    ),
  );

  protected readonly unnamedTargetCount = computed(
    () => this.danglingTargets().length - this.namedTargets().length,
  );

  protected onSegmentSelect(selection: ChartSelection): void {
    this.moduleSelect.emit(selection.rowKey);
  }
}
