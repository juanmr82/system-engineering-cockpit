import { Component, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import type { ICellRendererAngularComp } from 'ag-grid-angular';
import type { ICellRendererParams } from 'ag-grid-community';
import type { ModulesCellContext, SearchableModuleRow } from '../modules.model';

/** The module name, with the gear that opens its settings dialog as a peer of the text. */
@Component({
  selector: 'sec-module-name-cell',
  imports: [MatButtonModule, MatIconModule, MatTooltipModule],
  templateUrl: './module-name-cell.html',
  styleUrl: './module-name-cell.scss',
})
export class ModuleNameCell implements ICellRendererAngularComp {
  protected readonly name = signal('');
  private row: SearchableModuleRow | null = null;
  private context?: ModulesCellContext;

  agInit(params: ICellRendererParams<SearchableModuleRow>): void {
    this.update(params);
  }

  refresh(params: ICellRendererParams<SearchableModuleRow>): boolean {
    this.update(params);
    return true;
  }

  private update(params: ICellRendererParams<SearchableModuleRow>): void {
    this.row = params.data ?? null;
    this.name.set(params.data?.name ?? '');
    this.context = params.context as ModulesCellContext | undefined;
  }

  protected openSettings(): void {
    if (this.row) {
      this.context?.openSettings(this.row);
    }
  }
}
