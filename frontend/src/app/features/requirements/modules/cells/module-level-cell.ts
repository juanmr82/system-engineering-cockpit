import { Component, signal } from '@angular/core';
import type { ICellRendererAngularComp } from 'ag-grid-angular';
import type { ICellRendererParams } from 'ag-grid-community';
import type { ModulesCellContext, SearchableModuleRow, SystemLevelOption } from '../modules.model';

/**
 * The module's system level: a `:__Meta:__Classification` the application wrote, never something
 * DOORS said — hence the filled chip (R2, CLAUDE.md §8).
 *
 * **Editable in place**, writing to the view's own `ref`-keyed buffer rather than to the graph.
 * Not an ag-grid cell editor: that would be a second staging concept beside the buffer and R7
 * allows exactly one, so this is a real `<select>` in a renderer — the same shape as the review
 * table's comment box.
 *
 * The displayed wording is resolved server-side and arrives as `label`; the stored code (`L2`) is
 * never shown (R5). The code *is* used as a class suffix to pick the scale colour, which is
 * internal styling and never reaches the user as text.
 */
@Component({
  selector: 'sec-module-level-cell',
  templateUrl: './module-level-cell.html',
  styleUrl: './module-level-cell.scss',
})
export class ModuleLevelCell implements ICellRendererAngularComp {
  protected readonly selected = signal<string>('');
  protected readonly options = signal<readonly SystemLevelOption[]>([]);
  protected readonly dirty = signal(false);
  protected readonly label = signal('');

  private row: SearchableModuleRow | null = null;
  private context?: ModulesCellContext;

  agInit(params: ICellRendererParams<SearchableModuleRow>): void {
    this.update(params);
  }

  // True keeps this instance and re-reads it, which is what lets the view clear the dirty marks
  // after a save without the select losing focus or the table reloading.
  refresh(params: ICellRendererParams<SearchableModuleRow>): boolean {
    this.update(params);
    return true;
  }

  private update(params: ICellRendererParams<SearchableModuleRow>): void {
    const row = params.data ?? null;
    const context = params.context as ModulesCellContext | undefined;
    this.row = row;
    this.context = context;
    this.options.set(context?.systemLevels() ?? []);
    this.label.set(row ? `System level for ${row.name}` : 'System level');
    // Asked for rather than read off the row: the buffer is what the user last chose, and the row
    // is only what the server last stored.
    this.selected.set(row && context ? (context.levelCode(row) ?? '') : '');
    this.dirty.set(!!row && !!context && context.isLevelDirty(row));
  }

  protected onChange(code: string): void {
    const row = this.row;
    const context = this.context;
    if (!row || !context) {
      return;
    }
    // "" is the "Not set" option, which means clear the classification, not "no change".
    context.editLevel(row, code === '' ? null : code);
    this.selected.set(code);
    this.dirty.set(context.isLevelDirty(row));
  }
}
