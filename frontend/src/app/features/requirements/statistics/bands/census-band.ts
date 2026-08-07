import { Component, input, output } from '@angular/core';
import { KpiTile } from '../../../../shared/charts/kpi-tile';
import type { Census } from '../statistics.model';

/** Which band a tile explains — clicking a tile scrolls to it (§4). */
export type BandAnchor = 'completeness' | 'traceability' | 'cycles';

/**
 * Band 1 — the census (requirements-statistics.md §4).
 *
 * The loop count arrives from a different resource than the rest, so it is a separate input and
 * is null until Band 4 answers. A null renders as a dash, never as a zero: "no loops found" and
 * "not counted yet" are opposite claims and the tile must not make the wrong one.
 */
@Component({
  selector: 'sec-census-band',
  imports: [KpiTile],
  templateUrl: './census-band.html',
  styleUrl: './census-band.scss',
})
export class CensusBand {
  readonly census = input<Census | null>(null);
  readonly loops = input<number | null>(null);
  readonly scopedToModule = input(false);

  readonly anchorSelect = output<BandAnchor>();
}
