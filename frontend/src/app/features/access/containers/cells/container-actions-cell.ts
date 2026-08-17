import { Component, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import type { ICellRendererAngularComp } from 'ag-grid-angular';
import type { ICellRendererParams } from 'ag-grid-community';
import type { ContainerCategories } from '../../access.model';
import type { AccessContainersCellContext } from '../access-containers';

/** Edit, and — only once there is something to clear — Clear, the same "icon buttons at the
 *  row's own edge" shape `CategoryActionsCell` uses. */
@Component({
  selector: 'sec-container-actions-cell',
  imports: [MatButtonModule, MatIconModule, MatTooltipModule],
  templateUrl: './container-actions-cell.html',
  styleUrl: './container-actions-cell.scss',
})
export class ContainerActionsCell implements ICellRendererAngularComp {
  protected readonly name = signal('');
  protected readonly hasCategories = signal(false);
  private row: ContainerCategories | null = null;
  private context?: AccessContainersCellContext;

  agInit(params: ICellRendererParams<ContainerCategories>): void {
    this.update(params);
  }

  refresh(params: ICellRendererParams<ContainerCategories>): boolean {
    this.update(params);
    return true;
  }

  private update(params: ICellRendererParams<ContainerCategories>): void {
    this.row = params.data ?? null;
    this.name.set(params.data?.name ?? '');
    this.hasCategories.set((params.data?.categoryRefs.length ?? 0) > 0);
    this.context = params.context as AccessContainersCellContext | undefined;
  }

  protected edit(): void {
    if (this.row) {
      this.context?.edit(this.row);
    }
  }

  protected clear(): void {
    if (this.row) {
      this.context?.clear(this.row);
    }
  }
}
