import { Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import {
  MAT_DIALOG_DATA,
  MatDialog,
  MatDialogModule,
  MatDialogRef,
} from '@angular/material/dialog';
import { SEC_MODAL_DIALOG } from '../../../shared/dialog/modal-dialog.config';
import { AccessApiService } from '../access-api.service';

export interface AssignCategoriesDialogData {
  readonly containerCount: number;
}

/**
 * Picks the categories to assign to every container selected on the Unassigned screen (spec
 * §10.2 screen 3). A selection list, not an edit of one domain object, so it keeps its own plain
 * signal rather than a Signal Forms model — there is nothing here shaped like a form.
 *
 * Resolves to the chosen category refs, or `undefined` on cancel. The caller does the actual
 * per-container `PUT`s (one transaction each, R7) and the follow-up reconcile — this dialog only
 * decides *which* categories.
 */
@Component({
  selector: 'sec-assign-categories-dialog',
  imports: [MatButtonModule, MatCheckboxModule, MatDialogModule],
  templateUrl: './assign-categories-dialog.html',
  styleUrl: './assign-categories-dialog.scss',
})
export class AssignCategoriesDialog {
  static open(dialog: MatDialog, data: AssignCategoriesDialogData) {
    return dialog.open<AssignCategoriesDialog, AssignCategoriesDialogData, string[] | undefined>(
      AssignCategoriesDialog,
      { ...SEC_MODAL_DIALOG, width: '420px', data },
    );
  }

  protected readonly data = inject<AssignCategoriesDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(MatDialogRef<AssignCategoriesDialog, string[] | undefined>);
  protected readonly api = inject(AccessApiService);

  protected readonly categories = computed(() => this.api.categories.value()?.categories ?? []);
  private readonly selected = signal<ReadonlySet<string>>(new Set());
  protected readonly canConfirm = computed(() => this.selected().size > 0);

  protected isSelected(ref: string): boolean {
    return this.selected().has(ref);
  }

  protected toggle(ref: string): void {
    const next = new Set(this.selected());
    if (next.has(ref)) {
      next.delete(ref);
    } else {
      next.add(ref);
    }
    this.selected.set(next);
  }

  protected confirm(): void {
    this.dialogRef.close(Array.from(this.selected()));
  }

  protected cancel(): void {
    this.dialogRef.close(undefined);
  }
}
