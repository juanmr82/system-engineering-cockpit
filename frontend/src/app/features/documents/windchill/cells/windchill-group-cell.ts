import { Component, signal } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import type { ICellRendererAngularComp } from 'ag-grid-angular';
import type { ICellRendererParams } from 'ag-grid-community';
import type { WindchillGridContext, WindchillGridRow } from '../windchill-documents.model';

/**
 * The first column, for both kinds of row.
 *
 * One renderer rather than two, because the two things it draws are the two halves of one idea:
 * the disclosure on a group header, and the indent on a row that sits under one. Splitting them
 * would put the indent's width in one file and the control's width in another, and those two have
 * to agree or the column reads as ragged.
 *
 * ## Where the arrow's direction comes from, and where it must not
 *
 * From [isExpanded], which reads the view's own signal through `context` — **never from the row
 * data**. ag-grid refreshes a cell only when its value getter's output changed, and a header's
 * folder, name and number read the same open or shut, so a state carried in row data is a state
 * ag-grid never redraws: the versions below vanish and the arrow goes on pointing down. Reading a
 * signal inside this template makes Angular redraw the arrow for the ordinary reason.
 *
 * The row is a signal for a related reason: ag-grid updates a row in place and calls [refresh], and
 * a plain field written there never marks an OnPush view dirty in a zoneless application.
 *
 * ## Why the group can be collapsed at all
 *
 * ag-grid Community has no row grouping, so this is not a grid feature being configured — the view
 * owns the row array and this button asks it to rebuild one. That is why the callback arrives
 * through `context`: a renderer is built by ag-grid at runtime and is not a child of the component,
 * so it has no inputs and no outputs to bind.
 */
@Component({
  selector: 'sec-windchill-group-cell',
  imports: [MatIconModule],
  templateUrl: './windchill-group-cell.html',
  styleUrl: './windchill-group-cell.scss',
})
export class WindchillGroupCell implements ICellRendererAngularComp {
  protected readonly row = signal<WindchillGridRow | undefined>(undefined);
  private context?: WindchillGridContext;

  agInit(params: ICellRendererParams<WindchillGridRow>): void {
    this.update(params);
  }

  refresh(params: ICellRendererParams<WindchillGridRow>): boolean {
    this.update(params);
    return true;
  }

  /** Live, from the view's own signal. See the class note on why this is not `params.data`. */
  protected isExpanded(number: string): boolean {
    return this.context?.isExpanded(number) ?? true;
  }

  protected toggle(): void {
    const entry = this.row();
    // The number, not the row key: the key carries the expanded state and so changes across the
    // toggle it would be naming.
    if (entry?.kind === 'group') this.context?.toggleGroup(entry.number);
  }

  private update(params: ICellRendererParams<WindchillGridRow>): void {
    this.row.set(params.data);
    this.context = params.context as WindchillGridContext | undefined;
  }
}
