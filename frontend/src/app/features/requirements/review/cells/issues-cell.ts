import { Component, signal } from '@angular/core';
import type { ICellRendererAngularComp } from 'ag-grid-angular';
import type { ICellRendererParams } from 'ag-grid-community';
import type { TableRow } from '../review-table.model';

/**
 * The Issues cell: what the consistency checks found wrong with this object (`REQ_REVIEW.md` §5.3).
 *
 * Two kinds of finding share the list — a fixed rule's sentence and the bare name of a mandatory
 * attribute with no value — and the server composes both, so nothing here decides what is a
 * violation. Attribute names are shown raw, which is correct under R5: they are *content*, the
 * names the user chose in DOORS, not internal identifiers.
 *
 * Listed rather than counted: "3 issues" tells a reviewer to go and find out what they are, which
 * is the click this column exists to save.
 */
@Component({
  selector: 'sec-issues-cell',
  templateUrl: './issues-cell.html',
  styleUrl: './issues-cell.scss',
})
export class IssuesCell implements ICellRendererAngularComp {
  protected readonly missing = signal<string[]>([]);

  agInit(params: ICellRendererParams<TableRow>): void {
    this.update(params);
  }

  refresh(params: ICellRendererParams<TableRow>): boolean {
    this.update(params);
    return true;
  }

  private update(params: ICellRendererParams<TableRow>): void {
    this.missing.set(params.data?.row.issues ?? []);
  }
}
