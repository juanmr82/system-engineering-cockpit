import { Component, ElementRef, inject, signal } from '@angular/core';
import type { OnDestroy } from '@angular/core';
import type { ICellRendererAngularComp } from 'ag-grid-angular';
import type { GridApi, ICellRendererParams, IRowNode } from 'ag-grid-community';
import { DoorsTable } from '../../../../shared/doors-table/doors-table';
import type { DoorsTableView } from '../../../../shared/doors-table/doors-table.model';
import type { TableRow } from '../review-table.model';

/**
 * An embedded DOORS table, drawn in the Description column of the row that owns it.
 *
 * That is where DOORS itself draws it (`docs/DOORS_TABLES.md` §1): inside the main text column, at
 * the column's full width, with the surrounding display columns continuing to the left and right.
 * The **ID and Type columns are blank** for a table, as they are in DOORS — blanked in those
 * columns' own `valueGetter`s, so a copy and any future export agree with the screen, rather than
 * hidden here.
 *
 * **The one place ag-grid draws something that is not a grid cell of its own** (ADR 0006). The
 * table has a column count and column widths that come from the data and differ per table, so
 * there is no column definition to write — see the note on {@link DoorsTable}.
 */
@Component({
  selector: 'sec-table-cell',
  imports: [DoorsTable],
  templateUrl: './table-cell.html',
  styleUrl: './table-cell.scss',
})
export class TableCell implements ICellRendererAngularComp, OnDestroy {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  protected readonly table = signal<DoorsTableView | null>(null);

  /** What this instance was built for, so {@link refresh} can tell a re-read from a new table. */
  private builtFor: DoorsTableView | null = null;

  private api: GridApi | null = null;
  private node: IRowNode | null = null;
  private observer: ResizeObserver | null = null;
  private reportedHeight = 0;
  private frame = 0;

  agInit(params: ICellRendererParams<TableRow>): void {
    this.update(params);
    this.builtFor = this.table();
    this.watchHeight();
  }

  ngOnDestroy(): void {
    this.observer?.disconnect();
    cancelAnimationFrame(this.frame);
  }

  /**
   * Re-read in place, **except** when the table itself changed — then hand the cell back.
   *
   * This is the whole fix for a row that would otherwise stay 46px tall with a forty-one-row table
   * spilling over every requirement beneath it. `autoHeight` measures a cell **once, when it is
   * created**, and this cell is created before the tables request answers — so at measuring time
   * its content is nothing. Returning `false` makes ag-grid destroy and rebuild the cell, which is
   * the documented way to get a fresh measurement; `resetRowHeights()` is not, and ag-grid says so
   * in the console ("makes no sense when using Auto Row Height").
   *
   * Found by measuring the running page, because the stylesheet and the specs both look correct.
   *
   * Everything else — the id toggle, a comment saved elsewhere in the row — is a re-read, and
   * rebuilding for those would reset the findings disclosure under the reader's cursor.
   */
  refresh(params: ICellRendererParams<TableRow>): boolean {
    const incoming = params.data?.table ?? null;
    if (incoming !== this.builtFor) {
      return false;
    }
    this.update(params);
    return true;
  }

  private update(params: ICellRendererParams<TableRow>): void {
    this.api = params.api;
    this.node = params.node;
    this.table.set(params.data?.table ?? null);
  }

  /**
   * Report the drawn table's height to ag-grid whenever it changes.
   *
   * **Belt and braces, and both parts earned their place in the browser.** `autoHeight` measures a
   * cell when it is created, and this cell is created before the tables request answers — so
   * rebuilding it ({@link refresh} returning false) gets a fresh measurement, but *when* ag-grid
   * takes that measurement relative to Angular rendering the table inside it is not something this
   * component controls, and on a slow load it measured an empty cell and left the row 46px tall
   * with a forty-one-row table spilling over every requirement beneath it.
   *
   * Measuring our own content and stating it is deterministic. It also covers the case §6.6 is
   * about: dragging the Description column narrower re-wraps every cell and makes the table
   * taller, and the row has to follow.
   *
   * `resetRowHeights()` is deliberately **not** used — ag-grid rejects it for an auto-height
   * column, in as many words, in the console.
   *
   * Two guards against the `ResizeObserver loop` error §6.5 warns about: nothing layout-affecting
   * is written inside the callback, and a height equal to the last one reported does nothing at
   * all, which is what stops the observer feeding itself.
   */
  private watchHeight(): void {
    if (this.observer || typeof ResizeObserver === 'undefined') {
      return;
    }
    this.observer = new ResizeObserver(() => {
      const height = Math.round(this.host.nativeElement.getBoundingClientRect().height);
      if (height === 0 || height === this.reportedHeight) {
        return;
      }
      this.reportedHeight = height;
      cancelAnimationFrame(this.frame);
      this.frame = requestAnimationFrame(() => {
        this.node?.setRowHeight(height);
        this.api?.onRowHeightChanged();
      });
    });
    this.observer.observe(this.host.nativeElement);
  }
}
