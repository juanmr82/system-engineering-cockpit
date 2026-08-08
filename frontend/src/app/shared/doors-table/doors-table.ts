import { Component, computed, input, signal } from '@angular/core';
import { tableEntries, trackList } from './doors-table.model';
import type { DoorsTableView, TableAnomaly } from './doors-table.model';

/**
 * One reconstructed DOORS table (docs/DOORS_TABLES.md §5).
 *
 * **Not ag-grid, and this is the one exception to ADR 0006.** ag-grid is a flat,
 * column-definition-driven data table; what this draws is a nested grid inside one column of an
 * outer grid, whose column count and widths come from the data itself and differ per table. There
 * is no column definition to write. Every *data* table in the application is still ag-grid — this
 * is a piece of a document, the way a figure is.
 *
 * **A cell shows its `Object Text`. That is the whole of what a table shows.** No object ids, no
 * attribute values carried out beside it (§6.3), and no styling that sets the first row apart from
 * the rest — the header row is still `columnheader` for a screen reader, because it is still the
 * header row, but it is not drawn differently. The findings disclosure is the only thing on screen
 * that is not source data.
 *
 * Dumb by construction: it takes a view model the server assembled and renders it. No geometry is
 * derived here, nothing is fetched, and nothing is written — the whole feature is derivation at
 * read time and the imported data stays exactly as imported (§9, R1).
 *
 * **Width is fluid and entirely CSS.** The track list is a fraction list built from the server's
 * weights; when the containing column changes width the tracks recompute and every cell re-wraps
 * with no JavaScript at all. No pixel width is computed here, and none may be (§6.6).
 */
@Component({
  selector: 'sec-doors-table',
  templateUrl: './doors-table.html',
  styleUrl: './doors-table.scss',
  host: {
    // The two data-driven values the stylesheet needs. Both are *counts and ratios*, never measured
    // pixels: `--sec-doors-table-tracks` is the fraction list, and `--sec-doors-table-columns` lets
    // the stylesheet compute a minimum readable width in `rem` before it hands the table its own
    // horizontal scrollbar (§6.6).
    '[style.--sec-doors-table-tracks]': 'tracks()',
    '[style.--sec-doors-table-columns]': 'table().columnCount',
  },
})
export class DoorsTable {
  readonly table = input.required<DoorsTableView>();

  protected readonly anomaliesOpen = signal(false);

  protected readonly tracks = computed(() => trackList(this.table()));

  protected readonly entries = computed(() => tableEntries(this.table()));

  protected readonly anomalies = computed<TableAnomaly[]>(() => this.table().anomalies);

  /**
   * The warning affordance's own wording.
   *
   * A systems engineer needs to know the view may not match DOORS, and needs the DOORS id to go
   * and look — so the count is a sentence, not a badge with a number in it.
   */
  protected readonly anomalySummary = computed(() => {
    const count = this.anomalies().length;
    if (count === 0) {
      return '';
    }
    const errors = this.anomalies().filter((anomaly) => anomaly.severity === 'ERROR').length;
    const noun = count === 1 ? 'finding' : 'findings';
    return errors > 0
      ? `${count} ${noun} on this table, ${errors} of them serious`
      : `${count} ${noun} on this table`;
  });

  protected readonly hasErrors = computed(() =>
    this.anomalies().some((anomaly) => anomaly.severity === 'ERROR'),
  );

  protected toggleAnomalies(): void {
    this.anomaliesOpen.update((open) => !open);
  }
}
