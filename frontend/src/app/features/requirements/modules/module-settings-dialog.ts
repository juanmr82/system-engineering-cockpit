import { Component, computed, effect, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { FormField, form } from '@angular/forms/signals';
import { MatButtonModule } from '@angular/material/button';
import {
  MAT_DIALOG_DATA,
  MatDialog,
  MatDialogModule,
  MatDialogRef,
} from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';
import { MatTabsModule } from '@angular/material/tabs';
import type { ProblemDetails } from '../../../core/error/problem-details';
import {
  AttributeSettingsList,
  type AttributeFlagName,
  type AttributeSettingsField,
  type AttributeSettingsRow,
} from '../../../shared/attribute-settings/attribute-settings-list';
import { SEC_MODAL_DIALOG } from '../../../shared/dialog/modal-dialog.config';
import { ModulesApiService } from './modules-api.service';

export interface ModuleSettingsDialogData {
  readonly ref: string;
  readonly name: string;
}

interface SettingsFormModel {
  systemLevel: string | null;
  attributes: AttributeSettingsRow[];
}

// **Shown in table** is deliberately absent. It configures the Req review table's columns, and
// this dialog belongs to the Modules view, which has no such table — offering it here would be
// offering a setting whose effect is nowhere on screen. The flag is still *carried* in the model
// and posted back unchanged, so opening this dialog cannot clear what the review dialog set.
const MODULE_FLAGS: readonly AttributeFlagName[] = ['mandatory', 'verification'];

function extractErrorDetail(error: unknown): string {
  if (error instanceof HttpErrorResponse && error.error) {
    const problem = error.error as Partial<ProblemDetails>;
    if (problem.detail) {
      return problem.detail;
    }
  }
  return 'Something went wrong saving these settings. Please try again.';
}

// The reference implementation for a Tier-2 write dialog (requirements-modules.md §4). No staging
// layer: the edited model lives only in this component and Save is one POST in one server-side
// transaction (CLAUDE.md R7). Signal Forms binds every editable control — no FormGroup anywhere.
@Component({
  selector: 'sec-module-settings-dialog',
  imports: [
    AttributeSettingsList,
    FormField,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatProgressBarModule,
    MatSelectModule,
    MatTableModule,
    MatTabsModule,
  ],
  templateUrl: './module-settings-dialog.html',
  styleUrl: './module-settings-dialog.scss',
})
export class ModuleSettingsDialog {
  // The dialog owns its own presentation, so no call site can size it wrongly or forget the modal
  // contract. Callers write ModuleSettingsDialog.open(dialog, data) and get a typed result back.
  static open(dialog: MatDialog, data: ModuleSettingsDialogData) {
    return dialog.open<ModuleSettingsDialog, ModuleSettingsDialogData, boolean>(ModuleSettingsDialog, {
      ...SEC_MODAL_DIALOG,
      width: '880px',
      maxWidth: '94vw',
      // Tall for the same reason the review settings dialog is: tab 2 is a list of up to ~80
      // attributes, and the height is the only thing deciding how many are visible at once.
      height: '88vh',
      data,
    });
  }

  protected readonly data = inject<ModuleSettingsDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(MatDialogRef<ModuleSettingsDialog, boolean>);
  private readonly api = inject(ModulesApiService);

  private readonly detail = this.api.moduleDetail(this.data.ref);
  private readonly attributes = this.api.moduleAttributes(this.data.ref);
  protected readonly systemLevels = this.api.systemLevels;

  protected readonly propertyColumns = ['label', 'value'] as const;
  protected readonly flags = MODULE_FLAGS;

  private readonly model = signal<SettingsFormModel>({ systemLevel: null, attributes: [] });
  protected readonly settingsForm = form(this.model);

  protected readonly loading = computed(() => this.detail.isLoading() || this.attributes.isLoading());
  protected readonly loadError = computed(() => this.detail.error() ?? this.attributes.error());
  protected readonly properties = computed(() => this.detail.value()?.properties ?? []);

  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);
  protected readonly canSave = computed(() => this.settingsForm().dirty() && !this.saving());

  protected readonly allRows = computed(() => this.settingsForm().value().attributes);
  protected readonly fields = computed<AttributeSettingsField[]>(() =>
    Array.from(this.settingsForm.attributes),
  );

  constructor() {
    // Seeds the form once both resources resolve. A programmatic reset, not an edit through a
    // bound control, so the form stays pristine and Save stays disabled until the user acts.
    effect(() => {
      const detail = this.detail.value();
      const attributes = this.attributes.value();
      if (!detail || !attributes) {
        return;
      }
      this.model.set({
        systemLevel: detail.systemLevel,
        attributes: attributes.attributes.map((attribute) => ({
          name: attribute.name,
          mandatory: attribute.mandatory,
          visible: attribute.visible,
          verification: attribute.verification,
        })),
      });
    });
  }

  protected async save(): Promise<void> {
    this.saving.set(true);
    this.saveError.set(null);

    const value = this.settingsForm().value();

    try {
      // The absolute state of every attribute, the same payload the review settings dialog posts,
      // reaching the same guarded meta writer in the same transaction as the system level.
      //
      // This replaced a mandatory-only *diff*, which sent just what the user changed and so left
      // an untouched policy's `__updatedAt` alone. Two write shapes for one stored rule was the
      // higher price: the two dialogs now edit the same flags through the same component, and one
      // of them silently meaning something different by Save is exactly the bug that costs a day.
      await this.api.saveSettings(this.data.ref, {
        systemLevel: value.systemLevel,
        // Built field by field rather than spread: Signal Forms tags each row object with a
        // symbol key of its own. JSON.stringify drops it, so nothing reaches the wire either way —
        // but a payload that names its four fields is the one that stays right when the row type
        // grows a fifth.
        attributeSettings: value.attributes.map((row) => ({
          name: row.name,
          mandatory: row.mandatory,
          visible: row.visible,
          verification: row.verification,
        })),
      });
      this.dialogRef.close(true);
    } catch (error) {
      // Never close on a failed write: without a staging layer there is no queue to recover the
      // user's input from, so the dialog stays open with everything they typed intact (R7).
      this.saveError.set(extractErrorDetail(error));
    } finally {
      this.saving.set(false);
    }
  }

  protected cancel(): void {
    this.dialogRef.close(false);
  }
}
