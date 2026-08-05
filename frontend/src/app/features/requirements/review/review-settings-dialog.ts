import { Component, computed, effect, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { FormField, form, type FieldTree } from '@angular/forms/signals';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import type { ProblemDetails } from '../../../core/error/problem-details';
import { SEC_MODAL_DIALOG } from '../../../shared/dialog/modal-dialog.config';
import { matches } from '../../../shared/text/normalize';
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

/** The three editable flags, so the header's bulk actions can address them by name. */
type FlagName = 'mandatory' | 'visible' | 'verification';

// The view's own columns (§5, "Fixed columns"). Not DOORS attributes, so they never come back from
// attribute discovery — the dialog lists them itself, always shown and therefore disabled. They
// are listed rather than merely described because a reviewer looking for "why can't I hide ID"
// should find ID where they went looking for it.
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

/**
 * The attribute settings dialog of the Req review view (REQ_REVIEW.md §6).
 *
 * One gesture, one request, one server-side transaction (R7), and `mandatory` writes the *same*
 * stored rule the Modules dialog writes, so a change made in either is visible in the other.
 *
 * Built for the real case rather than the fixture: the reference modules carry 53 and 78
 * attributes, so the list is searchable and each column can be set for everything currently
 * listed. Bulk actions deliberately apply to the *filtered* rows — "search Verification, tick
 * every one" is the operation a reviewer actually performs.
 */
@Component({
  selector: 'sec-review-settings-dialog',
  imports: [
    FormField,
    MatButtonModule,
    MatCheckboxModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
  ],
  templateUrl: './review-settings-dialog.html',
  styleUrl: './review-settings-dialog.scss',
})
export class ReviewSettingsDialog {
  static open(dialog: MatDialog, data: ReviewSettingsDialogData) {
    return dialog.open<ReviewSettingsDialog, ReviewSettingsDialogData, boolean>(ReviewSettingsDialog, {
      ...SEC_MODAL_DIALOG,
      width: '880px',
      maxWidth: '94vw',
      // Tall on purpose: this dialog's content is a list of up to ~80 rows, and the height is the
      // only thing that decides how much of it a reviewer can see at once.
      height: '88vh',
      data,
    });
  }

  protected readonly data = inject<ReviewSettingsDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(MatDialogRef<ReviewSettingsDialog, boolean>);
  private readonly modulesApi = inject(ModulesApiService);
  private readonly reviewApi = inject(ReviewApiService);

  private readonly attributes = this.modulesApi.moduleAttributes(this.data.ref);

  protected readonly fixedColumns = FIXED_COLUMNS;
  protected readonly search = signal('');

  private readonly model = signal<{ attributes: AttributeFormRow[] }>({ attributes: [] });
  protected readonly settingsForm = form(this.model);

  protected readonly loading = computed(() => this.attributes.isLoading());
  protected readonly loadError = computed(() => this.attributes.error());

  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);
  protected readonly canSave = computed(() => this.settingsForm().dirty() && !this.saving());

  // Reading the form's value keeps this reactive: ticking a box re-runs the filter, so a row never
  // goes stale against the model it renders.
  protected readonly allRows = computed(() => this.settingsForm().value().attributes);
  protected readonly total = computed(() => this.allRows().length);

  protected readonly filtered = computed<AttributeField[]>(() => {
    const term = this.search();
    const rows = this.allRows();
    return Array.from(this.settingsForm.attributes).filter((_field, index) =>
      matches(rows[index]?.name ?? '', term),
    );
  });

  protected readonly filtering = computed(() => this.search().trim().length > 0);

  constructor() {
    // Seeded programmatically once the resource resolves — a reset of the model rather than an edit
    // through a bound control, so the form stays pristine and Save stays disabled until the user
    // actually changes something.
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

  /**
   * Sets one flag on every row currently listed.
   *
   * `markAsDirty` is not decoration: writing through the field's value signal updates the model,
   * but Save is gated on the form's dirty state, and a programmatic write is not an edit through a
   * bound control. Without it the user's bulk change would be un-saveable.
   */
  protected setAll(flag: FlagName, value: boolean): void {
    for (const field of this.filtered()) {
      const target = field[flag];
      target().value.set(value);
      target().markAsDirty();
    }
  }

  protected async save(): Promise<void> {
    this.saving.set(true);
    this.saveError.set(null);

    try {
      // The absolute state of every attribute, not just the ones on screen: the search filters the
      // view, never the payload. `systemLevel` is omitted rather than sent as null — this dialog
      // does not show it, and sending null would clear the module's classification.
      await this.reviewApi.saveSettings(this.data.ref, {
        attributeSettings: this.allRows().map((row) => ({ ...row })),
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
