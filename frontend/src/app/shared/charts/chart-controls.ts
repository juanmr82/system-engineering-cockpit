import { Component, input, model } from '@angular/core';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatTooltipModule } from '@angular/material/tooltip';
import type { ChartMode, ChartScale, ChartSort } from './chart.model';

/**
 * The three toggles a band offers over its charts (requirements-statistics.md §8).
 *
 * There is no time-range control and there will not be one: nothing in the graph is timestamped,
 * so there is no history to zoom (§3.6). "Interactive" here means re-ranking and rescaling.
 *
 * `model()` rather than input+output: each toggle is genuinely two-way, and the band owns the
 * state so it dies with the band — no shared store, no cross-view state (R7).
 */
@Component({
  selector: 'sec-chart-controls',
  imports: [MatButtonToggleModule, MatTooltipModule],
  templateUrl: './chart-controls.html',
  styleUrl: './chart-controls.scss',
})
export class ChartControls {
  readonly mode = model<ChartMode>('absolute');
  readonly sort = model<ChartSort>('value');
  readonly scale = model<ChartScale>('linear');

  /** Hidden entirely for a stacked-only band, where a log axis would misrepresent the sum. */
  readonly showScale = input(true);

  /**
   * A log axis silently drops non-positive values, so it is offered only when every value is
   * above zero. The reason is said in a tooltip rather than left as a dead control.
   */
  readonly logAvailable = input(true);
}
