import { Component, computed, effect, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { FormField, form, type FieldTree } from '@angular/forms/signals';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTableModule } from '@angular/material/table';
import type { ProblemDetails } from '../../../core/error/problem-details';
import { SEC_MODAL_DIALOG } from '../../../shared/dialog/modal-dialog.config';
import { ModulesApiService } from '../modules/modules-api.service';
import { ReviewApiService } from './review-api.service';

export interface ReviewSettingsDialogData {
  readonly ref: string;
  readonly name: string;
}

interface AttributeFormRow {
  name: string;
  mandatory: boolean;
  visible: boolean;
  verification: boolean;
}

type AttributeField = FieldTree<AttributeFormRow, number>;

// The view's own columns (§5, "Fixed columns"). They are not DOORS attributes and never come back
// from attribute discovery, so the dialog lists them itself: always shown, hence checked and
// disabled. ID is additionally always mandatory — a row nobody can identify is not reviewable.
interface FixedColumnRow {
  readonly label: string;
  readonly mandatory: boolean;
}

const FIXED_COLUMNS: readonly FixedColumnRow[] = [
  { label: 'ID', mandatory: true },
  { label: 'Type', mandatory: false },
  { label: 'Name', mandatory: false },
  { label: 'References', mandatory: false },
  { label: 'Comment', mandatory: false },
];

function extractErrorDetail(error: unknown): string {
  if (error instanceof HttpErrorResponse && error.error) {
    const problem = error.error as Partial<ProblemDetails>;
    if (problem.detail) {
      return problem.detail;
    }
  }
  return 'Something went wrong saving these settings. Please try again.';
}

// The attribute settings dialog of the Req review view (REQ_REVIEW.md §6). One gesture, one
// request, one server-side transaction (R7) — and the mandatory column writes the *same* stored
// rule the Modules dialog writes, so a change made in either is visible in the other.
@Component({
  selector: 'sec-review-settings-dialog',
  imports: [
    FormField,
    MatButtonModule,
    MatCheckboxModule,
    MatDialogModule,
    MatProgressBarModule,
    MatTableModule,
  ],
  templateUrl: './review-settings-dialog.html',
  styleUrl: './review-settings-dialog.scss',
})
export class ReviewSettingsDialog {
  static open(dialog: MatDialog, data: ReviewSettingsDialogData) {
    return dialog.open<ReviewSettingsDialog, ReviewSettingsDialogData, boolean>(ReviewSettingsDialog, {
      ...SEC_MODAL_DIALOG,
      width: '720px',
      height: '600px',
      data,
    });
  }

  protected readonly data = inject<ReviewSettingsDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(MatDialogRef<ReviewSettingsDialog, boolean>);
  private readonly modulesApi = inject(ModulesApiService);
  private readonly reviewApi = inject(ReviewApiService);

  private readonly attributes = this.modulesApi.moduleAttributes(this.data.ref);

  protected readonly fixedColumns = FIXED_COLUMNS;
  protected readonly attributeColumns = ['name', 'mandatory', 'visible', 'verification'] as const;

  private readonly model = signal<{ attributes: AttributeFormRow[] }>({ attributes: [] });
  protected readonly settingsForm = form(this.model);

  protected readonly loading = computed(() => this.attributes.isLoading());
  protected readonly loadError = computed(() => this.attributes.error());
  protected readonly attributeCount = computed(() => this.attributes.value()?.attributes.length ?? 0);

  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);
  protected readonly canSave = computed(() => this.settingsForm().dirty() && !this.saving());

  constructor() {
    // Seeded programmatically once the resource resolves, so the form stays pristine and Save
    // stays disabled until the user actually changes something.
    effect(() => {
      const attributes = this.attributes.value();
      if (!attributes) {
        return;
      }
      this.model.set({
        attributes: attributes.attributes.map((attribute) => ({
          name: attribute.name,
          mandatory: attribute.mandatory,
          visible: attribute.visible,
          verification: attribute.verification,
        })),
      });
    });
  }

  protected attributeFields(): AttributeField[] {
    return Array.from(this.settingsForm.attributes);
  }

  protected async save(): Promise<void> {
    this.saving.set(true);
    this.saveError.set(null);

    try {
      // The absolute state of every row the dialog showed, not a diff: the dialog holds the whole
      // list on screen, so this is unambiguous about what was unticked. `systemLevel` is omitted
      // rather than sent as null — this dialog does not show it, and sending null would clear it.
      await this.reviewApi.saveSettings(this.data.ref, {
        attributeSettings: this.settingsForm().value().attributes.map((row) => ({ ...row })),
      });
      this.dialogRef.close(true);
    } catch (error) {
      // Never close on a failed write: there is no staging layer to recover the input from (R7).
      this.saveError.set(extractErrorDetail(error));
    } finally {
      this.saving.set(false);
    }
  }

  protected cancel(): void {
    this.dialogRef.close(false);
  }
}
