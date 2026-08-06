import { Component, signal } from '@angular/core';
import type { ICellRendererAngularComp } from 'ag-grid-angular';
import type { ICellRendererParams } from 'ag-grid-community';
import type { ReviewCellContext, TableRow } from '../review-table.model';

/**
 * The ID cell: the object's DOORS id, as the control that opens the detail panel (§7).
 *
 * A real `<button>` rather than a click handler on the cell, so it is reachable by keyboard and
 * announced as an action. The id itself is display only — module-local, never a key (R6).
 */
@Component({
  selector: 'sec-id-cell',
  templateUrl: './id-cell.html',
  styleUrl: './id-cell.scss',
})
export class IdCell implements ICellRendererAngularComp {
  protected readonly id = signal('');
  private ref = '';
  private context?: ReviewCellContext;

  agInit(params: ICellRendererParams<TableRow>): void {
    this.update(params);
  }

  // Returning true keeps this instance and re-reads it, instead of ag-grid destroying and
  // recreating the component on every refresh.
  refresh(params: ICellRendererParams<TableRow>): boolean {
    this.update(params);
    return true;
  }

  private update(params: ICellRendererParams<TableRow>): void {
    this.id.set(params.data?.row.id ?? '');
    this.ref = params.data?.row.ref ?? '';
    this.context = params.context as ReviewCellContext | undefined;
  }

  protected open(): void {
    if (this.ref) {
      this.context?.openDetail(this.ref);
    }
  }
}
