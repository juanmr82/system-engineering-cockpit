import { Component, input, output } from '@angular/core';

/**
 * One number of a census row.
 *
 * Deliberately **not** an echarts chart: a single number is a layout, not a plot, and reaching for
 * the charting library to draw one would be the wrong reading of ADR 0008. Because it is real DOM,
 * its colour stays entirely in CSS — `tone` selects a modifier class rather than carrying a token
 * value through TypeScript.
 */
export type KpiTone = 'plain' | 'open' | 'alert';

@Component({
  selector: 'sec-kpi-tile',
  templateUrl: './kpi-tile.html',
  styleUrl: './kpi-tile.scss',
})
export class KpiTile {
  readonly label = input.required<string>();
  /** Null while the value is still loading — rendered as a dash, never as a misleading zero. */
  readonly value = input.required<number | null>();
  readonly tone = input<KpiTone>('plain');
  /** A short sentence under the number, for a tile whose meaning is not obvious from its label. */
  readonly hint = input('');
  // Not `select`: that is a native DOM event name, and an output shadowing one is ambiguous at
  // every call site (@angular-eslint/no-output-native).
  readonly tileSelect = output<void>();
}
