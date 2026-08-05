import { Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { SEC_MODAL_DIALOG } from './modal-dialog.config';

export interface ConfirmDialogData {
  readonly title: string;
  readonly message: string;
  /** The affirmative button's wording. Name the action, never "OK". */
  readonly confirmLabel: string;
  readonly cancelLabel?: string;
}

// The one confirmation dialog. It exists for the R7 exit guard: a view that owns an editable table
// asks before losing pending edits (REQ_REVIEW.md §9.1). Deliberately not a window.confirm — a
// browser modal cannot be styled, cannot name the work at risk, and reads as a page error.
@Component({
  selector: 'sec-confirm-dialog',
  imports: [MatButtonModule, MatDialogModule],
  templateUrl: './confirm-dialog.html',
  styleUrl: './confirm-dialog.scss',
})
export class ConfirmDialog {
  static open(dialog: MatDialog, data: ConfirmDialogData) {
    return dialog.open<ConfirmDialog, ConfirmDialogData, boolean>(ConfirmDialog, {
      ...SEC_MODAL_DIALOG,
      width: '420px',
      data,
    });
  }

  protected readonly data = inject<ConfirmDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(MatDialogRef<ConfirmDialog, boolean>);

  protected close(confirmed: boolean): void {
    this.dialogRef.close(confirmed);
  }
}
