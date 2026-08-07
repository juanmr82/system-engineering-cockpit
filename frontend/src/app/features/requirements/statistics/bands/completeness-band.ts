import { Component, computed, input, output, signal } from '@angular/core';
import { BarChart } from '../../../../shared/charts/bar-chart';
import { ChartControls } from '../../../../shared/charts/chart-controls';
import { StackedBarChart } from '../../../../shared/charts/stacked-bar-chart';
import { logAvailable } from '../../../../shared/charts/chart-options';
import type {
  BarDatum,
  ChartMode,
  ChartScale,
  ChartSelection,
  ChartSort,
  StackedRow,
  StackedSeries,
} from '../../../../shared/charts/chart.model';
import type { AttributeCount, Completeness, ModuleStatistics } from '../statistics.model';

/**
 * Band 2 — completeness (requirements-statistics.md §5).
 *
 * The stack's four segments are exactly the four states an item can be in, and they must add to
 * the item count — so `clean` is not "everything else", it is the count the server computed with
 * the same rule the Req review table uses (§3.2).
 */
const SERIES: readonly StackedSeries[] = [
  { key: 'clean', label: 'No findings', token: 'sec-highlight-verified' },
  { key: 'openPoints', label: 'TBD / TBC', token: 'sec-highlight-tbd' },
  { key: 'mandatory', label: 'Mandatory attribute empty', token: 'sec-highlight-undefined' },
  { key: 'verification', label: 'Verification attribute empty', token: 'sec-highlight-meta' },
];

@Component({
  selector: 'sec-completeness-band',
  imports: [BarChart, ChartControls, StackedBarChart],
  templateUrl: './completeness-band.html',
  styleUrl: './completeness-band.scss',
})
export class CompletenessBand {
  readonly completeness = input<Completeness | null>(null);
  readonly mandatoryByAttribute = input<readonly AttributeCount[]>([]);
  readonly openPointsByAttribute = input<readonly AttributeCount[]>([]);
  readonly modules = input<readonly ModuleStatistics[]>([]);

  readonly moduleSelect = output<string>();
  readonly attributeSelect = output<string>();

  // Local to this band and dying with it — no shared store, no cross-view state (R7).
  protected readonly mode = signal<ChartMode>('absolute');
  protected readonly sort = signal<ChartSort>('value');
  protected readonly scale = signal<ChartScale>('linear');

  protected readonly series = SERIES;

  protected readonly mandatoryBars = computed<BarDatum[]>(() =>
    this.mandatoryByAttribute().map((entry) => ({
      key: entry.attribute,
      label: entry.attribute,
      value: entry.violations,
    })),
  );

  protected readonly openPointBars = computed<BarDatum[]>(() =>
    this.openPointsByAttribute().map((entry) => ({
      key: entry.attribute,
      label: entry.attribute,
      value: entry.violations,
    })),
  );

  protected readonly moduleRows = computed<StackedRow[]>(() =>
    this.modules().map((module) => ({
      key: module.ref,
      label: module.name,
      values: {
        clean: module.completeness.itemsClean,
        openPoints: module.completeness.itemsWithOpenPoints,
        mandatory: module.completeness.itemsMissingMandatory,
        verification: module.completeness.itemsMissingVerification,
      },
    })),
  );

  protected readonly itemCount = computed(() => this.completeness()?.items ?? 0);

  protected readonly logAvailable = computed(() =>
    logAvailable([...this.mandatoryBars(), ...this.openPointBars()].map((bar) => bar.value)),
  );

  /**
   * Zero violations with nothing configured is not a clean module (§3.4, §3.5). The two look
   * identical in a number, so the band says which it is in words.
   */
  protected readonly mandatoryUnconfigured = computed(
    () => this.completeness()?.mandatoryConfigured === false,
  );

  protected readonly verificationUnconfigured = computed(
    () => this.completeness()?.verificationConfigured === false,
  );

  protected onSegmentSelect(selection: ChartSelection): void {
    this.moduleSelect.emit(selection.rowKey);
  }
}
