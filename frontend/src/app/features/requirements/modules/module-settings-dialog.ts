import { Component, computed, effect, inject, signal, viewChild } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { FormField, form, type FieldTree } from '@angular/forms/signals';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import {
  MAT_DIALOG_DATA,
  MatDialog,
  MatDialogModule,
  MatDialogRef,
} from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTable, MatTableModule } from '@angular/material/table';
import { MatTabsModule, type MatTabChangeEvent } from '@angular/material/tabs';
import type { ProblemDetails } from '../../../core/error/problem-details';
import { SEC_MODAL_DIALOG } from '../../../shared/dialog/modal-dialog.config';
import { ModulesApiService } from './modules-api.service';

export interface ModuleSettingsDialogData {
  readonly ref: string;
  readonly name: string;
}

interface AttributeFormRow {
  name: string;
  mandatory: boolean;
}

interface SettingsFormModel {
  systemLevel: string | null;
  attributes: AttributeFormRow[];
}

// The array-item field tree, pinned to the numeric key an array index produces — keeps
// attributeFields() and the #attributesTable view query in agreement.
type AttributeField = FieldTree<AttributeFormRow, number>;

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
    FormField,
    MatButtonModule,
    MatCheckboxModule,
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
      width: '760px',
      height: '620px',
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
  protected readonly attributeColumns = ['name', 'mandatory'] as const;

  private readonly model = signal<SettingsFormModel>({ systemLevel: null, attributes: [] });
  protected readonly settingsForm = form(this.model);

  private initialMandatory = new Set<string>();

  protected readonly loading = computed(() => this.detail.isLoading() || this.attributes.isLoading());
  protected readonly loadError = computed(() => this.detail.error() ?? this.attributes.error());
  protected readonly properties = computed(() => this.detail.value()?.properties ?? []);

  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);
  protected readonly canSave = computed(() => this.settingsForm().dirty() && !this.saving());

  // mat-tab-group measures lazily, so a sticky header rendered while its tab was hidden gets wrong
  // offsets; the header is re-measured on the first switch to tab 2 (CLAUDE.md §6).
  private readonly attributesTable = viewChild<MatTable<AttributeField>>('attributesTable');

  constructor() {
    // Seeds the form once both resources resolve. A programmatic reset, not an edit through a
    // bound control, so the form stays pristine and Save stays disabled until the user acts.
    effect(() => {
      const detail = this.detail.value();
      const attributes = this.attributes.value();
      if (!detail || !attributes) {
        return;
      }
      this.initialMandatory = new Set(
        attributes.attributes.filter((attribute) => attribute.mandatory).map((attribute) => attribute.name),
      );
      this.model.set({
        systemLevel: detail.systemLevel,
        attributes: attributes.attributes.map((attribute) => ({ ...attribute })),
      });
    });
  }

  protected attributeFields(): AttributeField[] {
    return Array.from(this.settingsForm.attributes);
  }

  protected onTabChange(event: MatTabChangeEvent): void {
    if (event.index === 1) {
      this.attributesTable()?.updateStickyHeaderRowStyles();
    }
  }

  protected async save(): Promise<void> {
    this.saving.set(true);
    this.saveError.set(null);

    const value = this.settingsForm().value();
    const currentlyMandatory = new Set(
      value.attributes.filter((attribute) => attribute.mandatory).map((attribute) => attribute.name),
    );
    // Only the diff goes to the server, so the audit timestamps stay meaningful — an untouched
    // policy keeps its original __updatedAt (requirements-modules.md §5.3).
    const add = [...currentlyMandatory].filter((name) => !this.initialMandatory.has(name));
    const remove = [...this.initialMandatory].filter((name) => !currentlyMandatory.has(name));

    try {
      await this.api.saveSettings(this.data.ref, {
        systemLevel: value.systemLevel,
        mandatoryAttributes: { add, remove },
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
