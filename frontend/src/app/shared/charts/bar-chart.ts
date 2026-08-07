import { Component, computed, input, output } from '@angular/core';
import { NgxEchartsDirective } from 'ngx-echarts';
import type { ECElementEvent } from 'echarts/core';
import { asPercentage, barKeysInRenderOrder, buildBarOption, sortBars } from './chart-options';
import type { ChartToken } from './chart-theme';
import type { BarDatum, ChartMode, ChartScale, ChartSort } from './chart.model';

/**
 * A ranked horizontal bar chart, with the data table that makes it readable without the canvas.
 *
 * The table is not a fallback — it is always in the DOM, visually hidden (ADR 0008, mitigation 2).
 * It is what a screen reader reads, and what a jsdom spec can assert, because echarts draws to a
 * canvas that neither can see.
 */
@Component({
  selector: 'sec-bar-chart',
  imports: [NgxEchartsDirective],
  templateUrl: './bar-chart.html',
  styleUrl: './bar-chart.scss',
})
export class BarChart {
  readonly data = input.required<readonly BarDatum[]>();
  readonly caption = input.required<string>();
  readonly valueName = input('Count');
  readonly token = input<ChartToken>('sec-blue-mid');
  readonly sort = input<ChartSort>('value');
  readonly scale = input<ChartScale>('linear');
  readonly mode = input<ChartMode>('absolute');
  /** The population percentages are taken against — not the sum of the bars. */
  readonly total = input(0);

  /** The `key` of the bar that was clicked. Drill-through is the point of the chart (§8). */
  readonly barSelect = output<string>();

  protected readonly option = computed(() =>
    buildBarOption({
      data: this.data(),
      sort: this.sort(),
      scale: this.scale(),
      mode: this.mode(),
      total: this.total(),
      token: this.token(),
      valueName: this.valueName(),
    }),
  );

  /** The same rows the canvas draws, in reading order, for the hidden table. */
  protected readonly rows = computed(() =>
    sortBars(this.data(), this.sort()).map((datum) => ({
      ...datum,
      shown:
        this.mode() === 'percentage'
          ? `${asPercentage(datum.value, this.total())}%`
          : `${datum.value}`,
    })),
  );

  protected onChartClick(event: ECElementEvent): void {
    // echarts reports a position in the series it drew, which is reversed so the largest bar sits
    // at the top. `barKeysInRenderOrder` is the one place that reversal is expressed for lookup.
    const key = barKeysInRenderOrder(this.data(), this.sort())[event.dataIndex];
    if (key !== undefined) {
      this.barSelect.emit(key);
    }
  }

  protected onRowSelect(key: string): void {
    this.barSelect.emit(key);
  }
}
