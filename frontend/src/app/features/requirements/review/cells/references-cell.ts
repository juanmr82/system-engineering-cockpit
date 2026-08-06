import { Component, signal } from '@angular/core';
import { MatTooltipModule } from '@angular/material/tooltip';
import type { ICellRendererAngularComp } from 'ag-grid-angular';
import type { ICellRendererParams } from 'ag-grid-community';
import type { RefGroup, ReviewCellContext, TableRow } from '../review-table.model';

/**
 * The References cell (§5.1): `refersTo` in both directions, grouped and labelled.
 *
 * All `refersTo` edges are untyped, so nothing here displays or implies satisfies / verifies /
 * refines — that semantics belongs to `:__Meta:__Link` and is a different feature (R2, Shape C).
 */
@Component({
  selector: 'sec-references-cell',
  imports: [MatTooltipModule],
  templateUrl: './references-cell.html',
  styleUrl: './references-cell.scss',
})
export class ReferencesCell implements ICellRendererAngularComp {
  protected readonly outgoing = signal<RefGroup | null>(null);
  protected readonly incoming = signal<RefGroup | null>(null);
  private context?: ReviewCellContext;

  agInit(params: ICellRendererParams<TableRow>): void {
    this.update(params);
  }

  refresh(params: ICellRendererParams<TableRow>): boolean {
    this.update(params);
    return true;
  }

  private update(params: ICellRendererParams<TableRow>): void {
    const data = params.data;
    // An empty group renders nothing at all, so a row with links in one direction only does not
    // pay for a label it cannot fill.
    this.outgoing.set(data && data.row.references.outgoing.length ? data.outgoing : null);
    this.incoming.set(data && data.row.references.incoming.length ? data.incoming : null);
    this.context = params.context as ReviewCellContext | undefined;
  }

  protected open(ref: string): void {
    this.context?.openDetail(ref);
  }
}
