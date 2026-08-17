import { Component, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import type { ICellRendererAngularComp } from 'ag-grid-angular';
import type { ICellRendererParams } from 'ag-grid-community';
import type { GroupWithGrants } from '../../access.model';
import type { AccessGrantsCellContext } from '../access-grants';

/** One `PUT` per row (spec §9: "saving is per row") — a pinned action-column cell, the same
 *  fixed-grid-of-controls feel the checkbox columns beside it already have. */
@Component({
  selector: 'sec-grants-row-save-cell',
  imports: [MatButtonModule, MatIconModule, MatProgressSpinnerModule, MatTooltipModule],
  templateUrl: './grants-row-save-cell.html',
  styleUrl: './grants-row-save-cell.scss',
})
export class GrantsRowSaveCell implements ICellRendererAngularComp {
  protected readonly dirty = signal(false);
  protected readonly saving = signal(false);
  protected readonly name = signal('');
  private row: GroupWithGrants | null = null;
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
    this.context = context;
    this.name.set(row?.name ?? '');
    this.dirty.set(!!row && !!context && context.isRowDirty(row));
    this.saving.set(!!row && !!context && context.isSaving(row));
  }

  protected save(): void {
    if (this.row) {
      this.context?.saveRow(this.row);
    }
  }
}
