import { Component, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import type { ICellRendererAngularComp } from 'ag-grid-angular';
import type { ICellRendererParams } from 'ag-grid-community';
import type { AccessCategory } from '../../access.model';
import type { AccessCategoriesCellContext } from '../access-categories';

/** Edit and delete, as a peer pair — the same "icon buttons at the row's own edge" shape
 *  `ModuleNameCell`'s settings gear uses, just two of them instead of one. */
@Component({
  selector: 'sec-category-actions-cell',
  imports: [MatButtonModule, MatIconModule, MatTooltipModule],
  templateUrl: './category-actions-cell.html',
  styleUrl: './category-actions-cell.scss',
})
export class CategoryActionsCell implements ICellRendererAngularComp {
  protected readonly name = signal('');
  private row: AccessCategory | null = null;
  private context?: AccessCategoriesCellContext;

  agInit(params: ICellRendererParams<AccessCategory>): void {
    this.update(params);
  }

  refresh(params: ICellRendererParams<AccessCategory>): boolean {
    this.update(params);
    return true;
  }

  private update(params: ICellRendererParams<AccessCategory>): void {
    this.row = params.data ?? null;
    this.name.set(params.data?.name ?? '');
    this.context = params.context as AccessCategoriesCellContext | undefined;
  }

  protected edit(): void {
    if (this.row) {
      this.context?.edit(this.row);
    }
  }

  protected remove(): void {
    if (this.row) {
      this.context?.remove(this.row);
    }
  }
}
