import type { EChartsCoreOption } from 'echarts/core';
import { chartTokens, type ChartToken } from './chart-theme';
import type { BarDatum, ChartMode, ChartScale, ChartSort, StackedRow, StackedSeries } from './chart.model';

// Pure functions producing echarts option objects (ADR 0008, mitigation 2).
//
// Charts render to a canvas, which jsdom cannot see and a screen reader cannot read. So the
// decisions live here, in functions a spec can call directly, and the components below are thin.
// **Specs assert what these return; never rendered pixels.**

/**
 * Log scale is only offered when every value is above zero.
 *
 * An echarts log axis silently drops non-positive values — a module with zero violations would
 * vanish from the chart rather than sit at the bottom of it, which reads as missing data. Rather
 * than clamping to an arbitrary epsilon (which invents a value the data does not have), the toggle
 * is disabled and the view says why.
 */
export function logAvailable(values: readonly number[]): boolean {
  return values.length > 0 && values.every((value) => value > 0);
}

/** Worst-first by default; ties and by-name sorting both fall back to the label, so the order is total. */
export function sortBars(data: readonly BarDatum[], sort: ChartSort): BarDatum[] {
  const sorted = [...data];
  return sort === 'name'
    ? sorted.sort((a, b) => a.label.localeCompare(b.label))
    : sorted.sort((a, b) => b.value - a.value || a.label.localeCompare(b.label));
}

export function sortRows(rows: readonly StackedRow[], series: readonly StackedSeries[], sort: ChartSort): StackedRow[] {
  const total = (row: StackedRow) => series.reduce((sum, s) => sum + (row.values[s.key] ?? 0), 0);
  const sorted = [...rows];
  return sort === 'name'
    ? sorted.sort((a, b) => a.label.localeCompare(b.label))
    : sorted.sort((a, b) => total(b) - total(a) || a.label.localeCompare(b.label));
}

/** A row's share of its own total, so rows of wildly different sizes stay comparable. */
export function asPercentage(value: number, total: number): number {
  return total === 0 ? 0 : Math.round((value / total) * 1000) / 10;
}

/**
 * Height of the strip below a chart's plot area, in pixels — where the legend and the axis name go.
 *
 * echarts positions both of those against the *container*, while `grid.containLabel` measures only
 * the axis tick labels. Neither knows the other exists, so nothing stops a legend being drawn
 * straight over the value axis's own numbers. This constant is the room they are each given, used
 * as `grid.bottom` on both builders and as the bar chart's `nameGap`, so the space reserved and the
 * distance the text is placed at can never drift apart. Specs assert it rather than a literal.
 */
export const AXIS_STRIP = 30;

interface BarOptionInput {
  readonly data: readonly BarDatum[];
  readonly sort: ChartSort;
  readonly scale: ChartScale;
  readonly mode: ChartMode;
  /** The denominator for percentage mode — the population, not the sum of the bars. */
  readonly total: number;
  readonly token: ChartToken;
  readonly valueName: string;
}

/**
 * A ranked horizontal bar chart.
 *
 * Horizontal because the labels are DOORS attribute names and module names — long, and containing
 * spaces, dots and umlauts. Rotated vertical labels would be unreadable for exactly the data this
 * application carries.
 */
export function buildBarOption(input: BarOptionInput): EChartsCoreOption {
  const tokens = chartTokens();
  const sorted = sortBars(input.data, input.sort);
  const percentage = input.mode === 'percentage';
  const values = sorted.map((datum) =>
    percentage ? asPercentage(datum.value, input.total) : datum.value,
  );
  const useLog = input.scale === 'log' && logAvailable(values);

  return {
    grid: { left: 8, right: 16, top: 8, bottom: AXIS_STRIP, containLabel: true },
    textStyle: { fontFamily: tokens['sec-font'], color: tokens['sec-ink-2'] },
    tooltip: {
      trigger: 'item',
      backgroundColor: tokens['sec-paper'],
      borderColor: tokens['sec-line'],
      textStyle: { color: tokens['sec-ink'] },
      valueFormatter: (value: unknown) => (percentage ? `${String(value)}%` : String(value)),
    },
    xAxis: {
      type: useLog ? 'log' : 'value',
      name: input.valueName,
      // Centred beneath the axis rather than hanging off its end. At `end` echarts places the name
      // level with the axis line, past the last tick, in a strip `containLabel` does not measure —
      // so a name as ordinary as "Violations" ran off the right edge and was clipped.
      nameLocation: 'middle',
      nameGap: AXIS_STRIP,
      nameTextStyle: { color: tokens['sec-ink-3'], fontSize: 10 },
      splitLine: { lineStyle: { color: tokens['sec-line-soft'] } },
      axisLabel: { color: tokens['sec-ink-3'], fontSize: 11 },
    },
    yAxis: {
      type: 'category',
      data: [...sorted].reverse().map((datum) => datum.label),
      axisLine: { lineStyle: { color: tokens['sec-line'] } },
      axisTick: { show: false },
      axisLabel: { color: tokens['sec-ink-2'], fontSize: 12 },
    },
    series: [
      {
        type: 'bar',
        name: input.valueName,
        // echarts draws a category axis bottom-up, so the highest bar reads at the top only if the
        // data is fed in reverse. Doing it here rather than in the sort keeps `sortBars` meaning
        // what it says; `barKeysInRenderOrder` below is the one place the reversal is undone.
        data: [...values].reverse(),
        itemStyle: { color: tokens[input.token] },
        barMaxWidth: 18,
      },
    ],
  };
}

interface StackedOptionInput {
  readonly rows: readonly StackedRow[];
  readonly series: readonly StackedSeries[];
  readonly sort: ChartSort;
  readonly scale: ChartScale;
  readonly mode: ChartMode;
}

/**
 * One stacked bar per row — the "which module do I fix first" chart.
 *
 * A log axis is refused outright here rather than degraded: segments of a stack are added
 * together, and a logarithmic axis makes the sum of two segments not the length of both, so the
 * picture would be quietly false rather than merely hard to read.
 */
export function buildStackedOption(input: StackedOptionInput): EChartsCoreOption {
  const tokens = chartTokens();
  const sorted = sortRows(input.rows, input.series, input.sort);
  const percentage = input.mode === 'percentage';
  const rowTotal = (row: StackedRow) =>
    input.series.reduce((sum, s) => sum + (row.values[s.key] ?? 0), 0);

  return {
    grid: { left: 8, right: 24, top: 8, bottom: AXIS_STRIP, containLabel: true },
    textStyle: { fontFamily: tokens['sec-font'], color: tokens['sec-ink-2'] },
    legend: {
      bottom: 0,
      // Scrolling, so the legend stays on one line whatever the labels say. A wrapping legend
      // grows upward into the chart, and the strip reserved above it is a fixed height — four
      // segment names on a narrow sheet would put the second line back over the axis.
      type: 'scroll',
      icon: 'roundRect',
      itemWidth: 10,
      itemHeight: 10,
      textStyle: { color: tokens['sec-ink-2'], fontSize: 11 },
    },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      backgroundColor: tokens['sec-paper'],
      borderColor: tokens['sec-line'],
      textStyle: { color: tokens['sec-ink'] },
      valueFormatter: (value: unknown) => (percentage ? `${String(value)}%` : String(value)),
    },
    xAxis: {
      // Never 'log': see the note above. The scale input is accepted so every chart in the view
      // takes the same props, and ignored here on purpose.
      type: 'value',
      max: percentage ? 100 : undefined,
      splitLine: { lineStyle: { color: tokens['sec-line-soft'] } },
      axisLabel: {
        color: tokens['sec-ink-3'],
        fontSize: 11,
        formatter: percentage ? '{value}%' : '{value}',
      },
    },
    yAxis: {
      type: 'category',
      data: [...sorted].reverse().map((row) => row.label),
      axisLine: { lineStyle: { color: tokens['sec-line'] } },
      axisTick: { show: false },
      axisLabel: { color: tokens['sec-ink-2'], fontSize: 12 },
    },
    series: input.series.map((s) => ({
      type: 'bar',
      stack: 'total',
      name: s.label,
      itemStyle: { color: tokens[s.token] },
      barMaxWidth: 22,
      data: [...sorted]
        .reverse()
        .map((row) =>
          percentage
            ? asPercentage(row.values[s.key] ?? 0, rowTotal(row))
            : (row.values[s.key] ?? 0),
        ),
    })),
  };
}

// --- Click mapping ----------------------------------------------------------------------------
//
// echarts reports a click as a `dataIndex` into the series it drew, and both builders above feed
// their data in reverse so the largest bar sits at the top. These two functions are the *only*
// place that reversal is expressed for lookup, so a click can never resolve to the wrong row
// because one call site remembered to reverse and another did not. Specs assert them directly.

export function barKeysInRenderOrder(data: readonly BarDatum[], sort: ChartSort): string[] {
  return sortBars(data, sort)
    .reverse()
    .map((datum) => datum.key);
}

export function rowKeysInRenderOrder(
  rows: readonly StackedRow[],
  series: readonly StackedSeries[],
  sort: ChartSort,
): string[] {
  return sortRows(rows, series, sort)
    .reverse()
    .map((row) => row.key);
}
