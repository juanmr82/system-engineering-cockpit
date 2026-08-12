import { Component, computed, input, signal } from '@angular/core';
import { FormField, type FieldTree } from '@angular/forms/signals';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { matches } from '../text/normalize';

/** The per-module attribute flags. A dialog shows the subset it can actually write. */
export type AttributeFlagName =
  | 'mandatory'
  | 'visible'
  | 'verification'
  | 'excludedFromOpenPoints';

/**
 * One editable row.
 *
 * Every flag is carried even by a dialog that shows a subset, because both dialogs post the
 * *absolute* state of every attribute — a hidden flag has to travel back unchanged rather than
 * being sent as false, which would silently clear what the other dialog set.
 */
export interface AttributeSettingsRow {
  name: string;
  mandatory: boolean;
  visible: boolean;
  verification: boolean;
  excludedFromOpenPoints: boolean;
}

export type AttributeSettingsField = FieldTree<AttributeSettingsRow, number>;

/**
 * A column the view always shows, listed for orientation and not editable.
 *
 * These are not attributes and never come back from attribute discovery, so the dialog that owns
 * a table states its own. A dialog with no table of its own passes none.
 */
export interface FixedColumnRow {
  readonly label: string;
  readonly checked: Readonly<Record<AttributeFlagName, boolean>>;
}

// The wording of the flags, from the server's alias map (R5, `domain/Aliases.kt`
// `attributeSettingLabels`). Stated once here rather than in each dialog's template — which is
// how the two dialogs came to describe the same stored flag with the same words.
const FLAG_LABELS: Readonly<Record<AttributeFlagName, string>> = {
  mandatory: 'Mandatory',
  visible: 'Shown in table',
  verification: 'Verification attribute',
  excludedFromOpenPoints: 'Exclude from TBD/TBC statistics',
};

/**
 * The searchable attribute list both settings dialogs are built from.
 *
 * Extracted from the Req review dialog when the Modules dialog needed the same thing minus one
 * column (REQ_REVIEW.md §6). It owns the search box, the count, the bulk actions and the rows; the
 * dialog around it owns the heading, the hint, and what Save does.
 *
 * Built for the real case rather than the fixture: the reference modules carry 53 and 78
 * attributes, so the list is searchable and each column can be set for everything currently
 * listed. Bulk actions deliberately apply to the *filtered* rows — "search Verification, tick
 * every one" is the operation a reviewer actually performs.
 */
@Component({
  selector: 'sec-attribute-settings-list',
  imports: [
    FormField,
    MatButtonModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
  ],
  templateUrl: './attribute-settings-list.html',
  styleUrl: './attribute-settings-list.scss',
})
export class AttributeSettingsList {
  /** Every row's field, in order. The caller passes a computed over its own form. */
  readonly fields = input.required<readonly AttributeSettingsField[]>();

  /** Which flags this dialog can write, left to right. */
  readonly flags = input<readonly AttributeFlagName[]>([
    'mandatory',
    'visible',
    'verification',
    'excludedFromOpenPoints',
  ]);

  readonly fixedColumns = input<readonly FixedColumnRow[]>([]);

  protected readonly search = signal('');

  protected readonly total = computed(() => this.fields().length);
  protected readonly filtering = computed(() => this.search().trim().length > 0);

  // Reading each field's value keeps this reactive: ticking a box re-runs the filter, so a row
  // never goes stale against the model it renders.
  protected readonly filtered = computed<readonly AttributeSettingsField[]>(() => {
    const term = this.search();
    return this.fields().filter((field) => matches(field().value().name, term));
  });

  protected label(flag: AttributeFlagName): string {
    return FLAG_LABELS[flag];
  }

  // A method rather than `attribute[flag]` in the template: the indexed access is what tells the
  // template compiler the result is one boolean field and not a union of the whole row.
  protected flagField(attribute: AttributeSettingsField, flag: AttributeFlagName) {
    return attribute[flag];
  }

  /**
   * Sets one flag on every row currently listed.
   *
   * `markAsDirty` is not decoration: writing through the field's value signal updates the model,
   * but Save is gated on the form's dirty state, and a programmatic write is not an edit through a
   * bound control. Without it the user's bulk change would be un-saveable.
   */
  protected setAll(flag: AttributeFlagName, value: boolean): void {
    for (const field of this.filtered()) {
      const target = this.flagField(field, flag);
      target().value.set(value);
      target().markAsDirty();
    }
  }
}
