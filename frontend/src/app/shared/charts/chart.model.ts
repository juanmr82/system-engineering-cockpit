import type { ChartToken } from './chart-theme';

// The vocabulary every chart in the application speaks. Deliberately small: these shapes cover a
// ranked bar and a stacked bar, which is what docs/features/requirements-statistics.md needs.
// A new shape is a new type here, not an `any` at a call site.

/** One bar of a ranked chart. `key` is what a click reports; `label` is what a reader sees. */
export interface BarDatum {
  readonly key: string;
  readonly label: string;
  readonly value: number;
}

/** One segment kind of a stacked chart — a column of the stack, shared by every row. */
export interface StackedSeries {
  readonly key: string;
  readonly label: string;
  readonly token: ChartToken;
}

/** One row of a stacked chart, carrying a value per series key. */
export interface StackedRow {
  readonly key: string;
  readonly label: string;
  readonly values: Readonly<Record<string, number>>;
}

/**
 * Linear or log. There is no time axis anywhere in this application — nothing in the graph is
 * timestamped and R2 forbids storing a derived value to build history from — so "scale" never
 * means a date range (requirements-statistics.md §3.6).
 */
export type ChartScale = 'linear' | 'log';

/** Absolute counts, or each row as a percentage of its own total. */
export type ChartMode = 'absolute' | 'percentage';

/** Worst-first to triage, by name to look one up. */
export type ChartSort = 'value' | 'name';

/** What a click on a bar or a segment reports back. */
export interface ChartSelection {
  readonly rowKey: string;
  readonly seriesKey: string | null;
}
