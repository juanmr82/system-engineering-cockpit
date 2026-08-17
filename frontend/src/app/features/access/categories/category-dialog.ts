import { Component, computed, inject, signal } from '@angular/core';
import { FormField, form } from '@angular/forms/signals';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import {
  MAT_DIALOG_DATA,
  MatDialog,
  MatDialogModule,
  MatDialogRef,
} from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { detailOf } from '../../../core/error/problem-details';
import { SEC_MODAL_DIALOG } from '../../../shared/dialog/modal-dialog.config';
import { AccessApiService } from '../access-api.service';
import type { AccessCategory } from '../access.model';

/** `category: null` opens the dialog in create mode; a category opens it in edit mode. */
export interface CategoryDialogData {
  readonly category: AccessCategory | null;
}

interface CategoryFormModel {
  key: string;
  name: string;
  description: string;
  everyGroup: boolean;
}

/**
 * Create or rename an access category (spec §10.2 screen 1). Modelled directly on
 * `ModuleSettingsDialog` — Signal Forms binds every editable control, and Save is one POST/PATCH
 * in one server-side transaction, no staging layer (CLAUDE.md R7).
 *
 * Unlike `ModuleSettingsDialog` there is no resource to await: every field this dialog edits is
 * already in hand from the row that opened it, so the model is seeded directly rather than
 * through an `effect()`.
 *
 * **`key` is immutable after creation** — shown as a read-only readout in edit mode, an editable
 * field only in create mode, and never sent on the rename request at all (there is nothing to
 * rename it to; `UpdateAccessCategoryRequest` has no `key` field).
 */
@Component({
  selector: 'sec-category-dialog',
  imports: [
    FormField,
    MatButtonModule,
    MatCheckboxModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
  ],
  templateUrl: './category-dialog.html',
  styleUrl: './category-dialog.scss',
})
export class CategoryDialog {
  static open(dialog: MatDialog, data: CategoryDialogData) {
    return dialog.open<CategoryDialog, CategoryDialogData, boolean>(CategoryDialog, {
      ...SEC_MODAL_DIALOG,
      width: '480px',
      data,
    });
  }

  protected readonly data = inject<CategoryDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(MatDialogRef<CategoryDialog, boolean>);
  private readonly api = inject(AccessApiService);

  protected readonly isEdit = this.data.category !== null;

  private readonly model = signal<CategoryFormModel>({
    key: this.data.category?.key ?? '',
    name: this.data.category?.name ?? '',
    description: this.data.category?.description ?? '',
    everyGroup: this.data.category?.everyGroup ?? false,
  });
  protected readonly categoryForm = form(this.model);

  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);

  // Not `dirty()`: a fresh create form starts empty rather than pre-filled, so "has the user
  // changed anything" is the wrong question here — "are the required fields filled in" is.
  protected readonly canSave = computed(() => {
    const value = this.categoryForm().value();
    const requiredFilled = value.name.trim().length > 0 && (this.isEdit || value.key.trim().length > 0);
    return requiredFilled && !this.saving();
  });

  protected async save(): Promise<void> {
    this.saving.set(true);
    this.saveError.set(null);
    const value = this.categoryForm().value();

    try {
      const editing = this.data.category;
      if (editing) {
        await this.api.renameCategory(editing.ref, {
          name: value.name,
          description: value.description,
          everyGroup: value.everyGroup,
        });
      } else {
        await this.api.createCategory({
          key: value.key,
          name: value.name,
          description: value.description,
          everyGroup: value.everyGroup,
        });
      }
      this.dialogRef.close(true);
    } catch (error) {
      // Never close on a failed write: there is no staging layer to recover the input from (R7).
      this.saveError.set(detailOf(error, 'Something went wrong saving this category. Please try again.'));
    } finally {
      this.saving.set(false);
    }
  }

  protected cancel(): void {
    this.dialogRef.close(false);
  }
}
