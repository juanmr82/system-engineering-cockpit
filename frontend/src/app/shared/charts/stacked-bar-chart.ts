import { Component, computed, input, output } from '@angular/core';
import { NgxEchartsDirective } from 'ngx-echarts';
import type { ECElementEvent } from 'echarts/core';
import { asPercentage, buildStackedOption, rowKeysInRenderOrder, sortRows } from './chart-options';
import type { ChartMode, ChartScale, ChartSort, ChartSelection, StackedRow, StackedSeries } from './chart.model';

/**
 * One stacked bar per row — the "which do I fix first" chart.
 *
 * Same contract as [BarChart]: a canvas plus an always-present visually-hidden table carrying the
 * same numbers, and click-through from either.
 */
@Component({
  selector: 'sec-stacked-bar-chart',
  imports: [NgxEchartsDirective],
  templateUrl: './stacked-bar-chart.html',
  styleUrl: './stacked-bar-chart.scss',
})
export class StackedBarChart {
  readonly rows = input.required<readonly StackedRow[]>();
  readonly series = input.required<readonly StackedSeries[]>();
  readonly caption = input.required<string>();
  readonly sort = input<ChartSort>('value');
  /** Accepted so every chart takes the same props; a stack is never drawn on a log axis. */
  readonly scale = input<ChartScale>('linear');
  readonly mode = input<ChartMode>('absolute');

  readonly segmentSelect = output<ChartSelection>();

  protected readonly option = computed(() =>
    buildStackedOption({
      rows: this.rows(),
      series: this.series(),
      sort: this.sort(),
      scale: this.scale(),
      mode: this.mode(),
    }),
  );

  protected readonly tableRows = computed(() => {
    const series = this.series();
    const percentage = this.mode() === 'percentage';
    return sortRows(this.rows(), series, this.sort()).map((row) => {
      const total = series.reduce((sum, s) => sum + (row.values[s.key] ?? 0), 0);
      return {
        key: row.key,
        label: row.label,
        cells: series.map((s) => {
          const value = row.values[s.key] ?? 0;
          return {
            seriesKey: s.key,
            shown: percentage ? `${asPercentage(value, total)}%` : `${value}`,
          };
        }),
      };
    });
  });

  protected onChartClick(event: ECElementEvent): void {
    const rowKey = rowKeysInRenderOrder(this.rows(), this.series(), this.sort())[event.dataIndex];
    const seriesKey = this.series()[event.seriesIndex ?? 0]?.key ?? null;
    if (rowKey !== undefined) {
      this.segmentSelect.emit({ rowKey, seriesKey });
    }
  }

  protected onRowSelect(rowKey: string): void {
    // From the table, the row is what was chosen; no single segment was named.
    this.segmentSelect.emit({ rowKey, seriesKey: null });
  }
}
