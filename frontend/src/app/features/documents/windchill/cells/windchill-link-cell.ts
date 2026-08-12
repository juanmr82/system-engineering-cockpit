import { Component, signal } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import type { ICellRendererAngularComp } from 'ag-grid-angular';
import type { ICellRendererParams } from 'ag-grid-community';
import type { WindchillGridRow } from '../windchill-documents.model';

/**
 * The header-less last column: open this document in Windchill.
 *
 * **The href is `browseUrl`, never anything assembled here.** The document's stored identity is its
 * OData resource URL, and opening one shows raw JSON; the info page is derived by the server from
 * the object id and the configured host, so it is the one thing in this cell that must not be built
 * in the browser (R2, R5).
 *
 * Absent on a group row and on a deployment with no host configured. Both render as an empty cell
 * rather than a disabled control: there is nothing to open, and a dimmed icon would suggest there
 * is something to be enabled.
 *
 * The row is a **signal** for the reason `WindchillGroupCell` explains at length: ag-grid updates a
 * row in place and calls `refresh`, and a plain field written there never re-renders an OnPush view
 * in a zoneless application. Nothing here has been seen to go wrong — a document's link does not
 * change under it — and it is written this way so that it cannot.
 *
 * `rel="noopener noreferrer"` is not boilerplate — without `noopener` the opened page can reach back
 * through `window.opener`, and this application is what it would reach.
 */
@Component({
  selector: 'sec-windchill-link-cell',
  imports: [MatIconModule, MatTooltipModule],
  templateUrl: './windchill-link-cell.html',
  styleUrl: './windchill-link-cell.scss',
})
export class WindchillLinkCell implements ICellRendererAngularComp {
  protected readonly row = signal<WindchillGridRow | undefined>(undefined);

  agInit(params: ICellRendererParams<WindchillGridRow>): void {
    this.row.set(params.data);
  }

  refresh(params: ICellRendererParams<WindchillGridRow>): boolean {
    this.row.set(params.data);
    return true;
  }
}
