import { Component, signal } from '@angular/core';
import type { ICellRendererAngularComp } from 'ag-grid-angular';
import type { ICellRendererParams } from 'ag-grid-community';
import type { AccessCategory } from '../../access.model';
import type { AccessDefaultsCellContext, AccessDefaultRow } from '../access-defaults';

/**
 * The default category for one `(sourceId, containerLabel)` pair — editable in place, writing to
 * the view's own `rowId`-keyed buffer rather than to the graph. The same real `<select>`-in-a-
 * renderer shape `ModuleLevelCell` uses, not an ag-grid cell editor (R7: one buffer, not two
 * staging concepts side by side).
 */
@Component({
  selector: 'sec-default-category-cell',
  templateUrl: './default-category-cell.html',
  styleUrl: './default-category-cell.scss',
})
export class DefaultCategoryCell implements ICellRendererAngularComp {
  protected readonly selected = signal<string>('');
  protected readonly options = signal<readonly AccessCategory[]>([]);
  protected readonly dirty = signal(false);
  protected readonly label = signal('');

  private row: AccessDefaultRow | null = null;
  private context?: AccessDefaultsCellContext;

  agInit(params: ICellRendererParams<AccessDefaultRow>): void {
    this.update(params);
  }

  refresh(params: ICellRendererParams<AccessDefaultRow>): boolean {
    this.update(params);
    return true;
  }

  private update(params: ICellRendererParams<AccessDefaultRow>): void {
    const row = params.data ?? null;
    const context = params.context as AccessDefaultsCellContext | undefined;
    this.row = row;
    this.context = context;
    this.options.set(context?.categories() ?? []);
    this.label.set(row ? `Default category for ${row.sourceId} ${row.containerLabel}` : 'Default category');
    this.selected.set(row && context ? (context.categoryRef(row) ?? '') : '');
    this.dirty.set(!!row && !!context && context.isDirty(row));
  }

  protected onChange(ref: string): void {
    const row = this.row;
    const context = this.context;
    if (!row || !context) {
      return;
    }
    context.editCategory(row, ref === '' ? null : ref);
    this.selected.set(ref);
    this.dirty.set(context.isDirty(row));
  }
}
