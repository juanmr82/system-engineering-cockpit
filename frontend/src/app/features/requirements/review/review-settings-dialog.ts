import { Component, computed, effect, inject, signal } from '@angular/core';
import { form } from '@angular/forms/signals';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { detailOf } from '../../../core/error/problem-details';
import {
  AttributeSettingsList,
  type AttributeSettingsField,
  type AttributeSettingsRow,
  type FixedColumnRow,
} from '../../../shared/attribute-settings/attribute-settings-list';
import { SEC_MODAL_DIALOG } from '../../../shared/dialog/modal-dialog.config';
import { ModulesApiService } from '../modules/modules-api.service';
import { ReviewApiService } from './review-api.service';

export interface ReviewSettingsDialogData {
  readonly ref: string;
  readonly name: string;
}

// The view's own columns (§5, "Fixed columns"). Not DOORS attributes, so they never come back from
// attribute discovery — the dialog lists them itself, always shown and therefore disabled. They
// are listed rather than merely described because a reviewer looking for "why can't I hide ID"
// should find ID where they went looking for it.
const FIXED_COLUMNS: readonly FixedColumnRow[] = [
  {
    label: 'ID',
    checked: { mandatory: true, visible: true, verification: false, excludedFromOpenPoints: false },
  },
  {
    label: 'Type',
    checked: { mandatory: false, visible: true, verification: false, excludedFromOpenPoints: false },
  },
  {
    label: 'Name',
    checked: { mandatory: false, visible: true, verification: false, excludedFromOpenPoints: false },
  },
  {
    label: 'References',
    checked: { mandatory: false, visible: true, verification: false, excludedFromOpenPoints: false },
  },
  {
    label: 'Comment',
    checked: { mandatory: false, visible: true, verification: false, excludedFromOpenPoints: false },
  },
];

/**
 * The attribute settings dialog of the Req review view (REQ_REVIEW.md §6).
 *
 * One gesture, one request, one server-side transaction (R7), and `mandatory` writes the *same*
 * stored rule the Modules dialog writes, so a change made in either is visible in the other.
 *
 * The list itself is `sec-attribute-settings-list`, shared with the Modules dialog's Object
 * attributes tab — which shows the same rows minus **Shown in table**, because that column
 * configures *this* view's table and nothing in the Modules view.
 */
@Component({
  selector: 'sec-review-settings-dialog',
  imports: [AttributeSettingsList, MatButtonModule, MatDialogModule, MatProgressBarModule],
  templateUrl: './review-settings-dialog.html',
  styleUrl: './review-settings-dialog.scss',
})
export class ReviewSettingsDialog {
  static open(dialog: MatDialog, data: ReviewSettingsDialogData) {
    return dialog.open<ReviewSettingsDialog, ReviewSettingsDialogData, boolean>(ReviewSettingsDialog, {
      ...SEC_MODAL_DIALOG,
      // Wide enough for four flag columns and still leave the attribute name room to be read: the
      // flag columns are a fixed width each, so every one added comes straight out of the name.
      width: '1040px',
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

  private readonly model = signal<{ attributes: AttributeSettingsRow[] }>({ attributes: [] });
  protected readonly settingsForm = form(this.model);

  protected readonly loading = computed(() => this.attributes.isLoading());
  protected readonly loadError = computed(() => this.attributes.error());

  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);
  protected readonly canSave = computed(() => this.settingsForm().dirty() && !this.saving());

  protected readonly allRows = computed(() => this.settingsForm().value().attributes);
  protected readonly fields = computed<AttributeSettingsField[]>(() =>
    Array.from(this.settingsForm.attributes),
  );

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
          excludedFromOpenPoints: attribute.excludedFromOpenPoints,
        })),
      });
    });
  }

  protected async save(): Promise<void> {
    this.saving.set(true);
    this.saveError.set(null);

    try {
      // The absolute state of every attribute, not just the ones on screen: the search filters the
      // view, never the payload. `systemLevel` is omitted rather than sent as null — this dialog
      // does not show it, and sending null would clear the module's classification.
      await this.reviewApi.saveSettings(this.data.ref, {
        // Built field by field rather than spread: Signal Forms tags each row object with a
        // symbol key of its own. JSON.stringify drops it, so nothing reaches the wire either way —
        // but a payload that names its four fields is the one that stays right when the row type
        // grows a fifth.
        attributeSettings: this.allRows().map((row) => ({
          name: row.name,
          mandatory: row.mandatory,
          visible: row.visible,
          verification: row.verification,
          excludedFromOpenPoints: row.excludedFromOpenPoints,
        })),
      });
      this.dialogRef.close(true);
    } catch (error) {
      // Never close on a failed write: there is no staging layer to recover the input from (R7).
      this.saveError.set(detailOf(error, 'Something went wrong saving these settings. Please try again.'));
    } finally {
      this.saving.set(false);
    }
  }

  protected cancel(): void {
    this.dialogRef.close(false);
  }
}
