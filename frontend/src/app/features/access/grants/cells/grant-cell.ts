import { Component, signal } from '@angular/core';
import { MatCheckboxModule } from '@angular/material/checkbox';
import type { ICellRendererAngularComp } from 'ag-grid-angular';
import type { ICellRendererParams } from 'ag-grid-community';
import type { GroupWithGrants } from '../../access.model';
import type { AccessGrantsCellContext } from '../access-grants';

/** One cell of the grant matrix — whether this row's group may read the column's category. The
 *  category is identified by the column's own id (`params.column.getColId()`), which the parent
 *  sets to the category's `ref` for exactly this reason. */
@Component({
  selector: 'sec-grant-cell',
  imports: [MatCheckboxModule],
  templateUrl: './grant-cell.html',
  styleUrl: './grant-cell.scss',
})
export class GrantCell implements ICellRendererAngularComp {
  protected readonly checked = signal(false);
  protected readonly label = signal('');
  private row: GroupWithGrants | null = null;
  private categoryRef = '';
  private context?: AccessGrantsCellContext;

  agInit(params: ICellRendererParams<GroupWithGrants>): void {
    this.update(params);
  }

  refresh(params: ICellRendererParams<GroupWithGrants>): boolean {
    this.update(params);
    return true;
  }

  private update(params: ICellRendererParams<GroupWithGrants>): void {
    const row = params.data ?? null;
    const context = params.context as AccessGrantsCellContext | undefined;
    this.row = row;
    this.categoryRef = params.column?.getColId() ?? '';
    this.context = context;
    this.checked.set(!!row && !!context && context.isGranted(row, this.categoryRef));
    this.label.set(`Grant ${String(params.colDef?.headerName ?? '')} to ${row?.name ?? ''}`);
  }

  protected onChange(): void {
    const row = this.row;
    const context = this.context;
    if (!row || !context) {
      return;
    }
    context.toggleGrant(row, this.categoryRef);
    this.checked.set(context.isGranted(row, this.categoryRef));
  }
}
