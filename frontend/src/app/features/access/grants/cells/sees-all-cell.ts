import { Component, signal } from '@angular/core';
import { MatCheckboxModule } from '@angular/material/checkbox';
import type { MatCheckboxChange } from '@angular/material/checkbox';
import type { ICellRendererAngularComp } from 'ag-grid-angular';
import type { ICellRendererParams } from 'ag-grid-community';
import type { GroupWithGrants } from '../../access.model';
import type { AccessGrantsCellContext } from '../access-grants';

/**
 * `seesAll` — its own visually distinct column (spec §9), never folded into the grant matrix.
 *
 * The checkbox always reflects the *stored* value, never an optimistic click: `onChange` asks the
 * parent to confirm and write, and does not touch `checked` itself. A cancelled confirmation
 * therefore needs no explicit revert — the control was never moved to begin with, unlike the
 * review table's native `<select>`, which does need one (`requirement-review.ts`'s
 * `moduleSelect`) because that element bypasses Angular's own binding.
 */
@Component({
  selector: 'sec-sees-all-cell',
  imports: [MatCheckboxModule],
  templateUrl: './sees-all-cell.html',
  styleUrl: './sees-all-cell.scss',
})
export class SeesAllCell implements ICellRendererAngularComp {
  protected readonly checked = signal(false);
  protected readonly label = signal('');
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
    this.row = params.data ?? null;
    this.context = params.context as AccessGrantsCellContext | undefined;
    this.checked.set(this.row?.seesAll ?? false);
    this.label.set(`Sees everything for ${this.row?.name ?? ''}`);
  }

  protected onChange(event: MatCheckboxChange): void {
    if (this.row && this.context) {
      this.context.requestSeesAllChange(this.row, event.checked);
    }
    // Snap back to the stored value immediately; see the class doc for why no explicit revert is
    // needed on cancel.
    this.checked.set(this.row?.seesAll ?? false);
  }
}
