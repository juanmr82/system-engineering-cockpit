import { Component, computed, inject, linkedSignal, signal } from '@angular/core';
import { CdkDrag, CdkDropList, type CdkDragDrop } from '@angular/cdk/drag-drop';
import { ScrollingModule } from '@angular/cdk/scrolling';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatChipsModule } from '@angular/material/chips';
import {
  MatDialogModule,
  MatDialogRef,
  type MatDialog,
} from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { SEC_MODAL_DIALOG } from '../../../shared/dialog/modal-dialog.config';
import { matches } from '../../../shared/text/normalize';
import { JiraColumnsApiService } from './jira-columns-api.service';
import type { JiraColumn, JiraField } from './jira-columns.model';

/** Which part of the catalogue the list is showing. */
type SourceFilter = 'all' | 'system' | 'custom';

/**
 * "Select fields to display" (spec §13.3).
 *
 * ## Two panes, not one table with a drag handle
 *
 * The spec draws one virtual-scrolled table whose rows carry a checkbox and a drag handle. That
 * shape does not survive contact with the data: the catalogue is over a thousand rows and only the
 * handful that are ticked can be ordered, so the handles are invisible almost everywhere and the
 * one gesture that matters — putting Status before Assignee — means finding two rows hundreds
 * apart in a scrolling list. Choosing and ordering are two questions, so they get two panes: the
 * catalogue on the left, and the chosen columns, in order, on the right.
 *
 * ## Nothing is written until Save
 *
 * A checkbox is a change to *this dialog's* buffer and nothing else (R7): one dialog, one Save, one
 * request, one transaction. Cancel discards, which is the only thing that makes ticking a box
 * safe to do experimentally.
 *
 * ## Stale columns
 *
 * A chosen field JIRA no longer has cannot appear in the catalogue pane — it is not in the
 * catalogue. It appears at the bottom of the chosen pane, under its own heading, with a remove
 * control (§13.4). It is never removed automatically: the user chose it, and a column that
 * disappeared on its own looks like a bug.
 */
@Component({
  selector: 'sec-jira-columns-dialog',
  imports: [
    CdkDrag,
    CdkDropList,
    MatButtonModule,
    MatCheckboxModule,
    MatChipsModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    MatTooltipModule,
    ScrollingModule,
  ],
  templateUrl: './jira-columns-dialog.html',
  styleUrl: './jira-columns-dialog.scss',
})
export class JiraColumnsDialog {
  private readonly dialogRef = inject<MatDialogRef<JiraColumnsDialog, boolean>>(MatDialogRef);
  private readonly api = inject(JiraColumnsApiService);

  // Created here rather than held on the service: the catalogue is over a thousand rows and only
  // this dialog wants it, so it is fetched when a dialog opens and not when a page loads.
  protected readonly fields = this.api.fieldCatalogue();
  private readonly defaults = this.api.defaults();
  protected readonly columns = this.api.columns;

  protected readonly search = signal('');
  protected readonly sourceFilter = signal<SourceFilter>('all');
  protected readonly selectedOnly = signal(false);

  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  /**
   * The dialog's buffer: the chosen field ids, in order.
   *
   * A `linkedSignal` and not a copy taken in the constructor, because the resource answers *after*
   * the dialog is on screen — a plain signal initialised from it would hold the empty list forever.
   * The source is `undefined` until the server has answered, which is what lets the computation
   * seed exactly once: on the transition into a value. Every later run keeps `previous.value`, so a
   * user's ticking is never overwritten by a reload.
   */
  protected readonly chosen = linkedSignal<readonly JiraColumn[] | undefined, readonly string[]>({
    source: () => (this.columns.hasValue() ? this.columns.value() : undefined),
    computation: (columns, previous) => {
      if (columns === undefined) return previous?.value ?? [];
      return previous?.source === undefined ? columns.map((column) => column.fieldId) : previous.value;
    },
  });

  /** Everything the picker knows about a field, by id — including the stale ones, which is none. */
  private readonly fieldsById = computed(() => {
    const byId = new Map<string, JiraField>();
    for (const field of this.fields.hasValue() ? this.fields.value() : []) {
      byId.set(field.fieldId, field);
    }
    return byId;
  });

  protected readonly isLoading = computed(() => this.fields.isLoading() || this.columns.isLoading());

  /** Every offerable field, filtered by the search box and the two filters. */
  protected readonly visibleFields = computed<readonly JiraField[]>(() => {
    const term = this.search().trim();
    const source = this.sourceFilter();
    const onlySelected = this.selectedOnly();
    const chosen = new Set(this.chosen());

    return (this.fields.hasValue() ? this.fields.value() : []).filter((field) => {
      if (source === 'system' && field.custom) return false;
      if (source === 'custom' && !field.custom) return false;
      if (onlySelected && !chosen.has(field.fieldId)) return false;
      // The id has to match too: searching `customfield_23700` is how a user finds one of the
      // fifteen names that cover thirty-three fields.
      return !term || matches(field.name, term) || matches(field.fieldId, term);
    });
  });

  protected readonly totalFields = computed(() =>
    this.fields.hasValue() ? this.fields.value().length : 0,
  );

  /** The chosen columns as rows to draw: name and type where known, and stale where not. */
  protected readonly chosenRows = computed(() =>
    this.chosen().map((fieldId) => {
      const field = this.fieldsById().get(fieldId);
      return {
        fieldId,
        name: field?.name ?? fieldId,
        schemaType: field?.schemaType ?? null,
        ambiguousName: field?.ambiguousName ?? false,
        stale: field === undefined,
      };
    }),
  );

  protected readonly staleRows = computed(() => this.chosenRows().filter((row) => row.stale));
  protected readonly liveRows = computed(() => this.chosenRows().filter((row) => !row.stale));

  protected isChosen(fieldId: string): boolean {
    return this.chosen().includes(fieldId);
  }

  /**
   * Tick or untick a field.
   *
   * A newly ticked column goes to the end, which is the only position that needs no rule: it is
   * where a person looking at the chosen list expects the thing they just added to appear.
   */
  protected toggle(fieldId: string, checked: boolean): void {
    this.chosen.update((ids) =>
      checked ? (ids.includes(fieldId) ? ids : [...ids, fieldId]) : ids.filter((id) => id !== fieldId),
    );
  }

  protected remove(fieldId: string): void {
    this.chosen.update((ids) => ids.filter((id) => id !== fieldId));
  }

  /**
   * Reorder by dragging.
   *
   * The indices are positions in the *live* rows, which is what the list draws. A stale row is in
   * the buffer and not in that list, so the move is applied to the live ids and the buffer is
   * rebuilt from them — never by index arithmetic across two lists of different lengths.
   */
  protected reorder(event: CdkDragDrop<unknown>): void {
    const live = this.liveRows().map((row) => row.fieldId);
    const moved = live[event.previousIndex];
    if (moved === undefined || event.previousIndex === event.currentIndex) return;

    live.splice(event.previousIndex, 1);
    live.splice(event.currentIndex, 0, moved);

    // The stale ids keep their relative order and follow the live ones, which is also how they are
    // drawn. Their position is not something a user can act on.
    this.chosen.set([...live, ...this.staleRows().map((row) => row.fieldId)]);
  }

  /** Back to the server's defaults — its list, fetched, never a copy held here. */
  protected resetToDefaults(): void {
    if (this.defaults.hasValue()) {
      this.chosen.set(this.defaults.value().map((column) => column.fieldId));
    }
  }

  protected async save(): Promise<void> {
    this.saving.set(true);
    this.error.set(null);

    try {
      await this.api.save(this.chosen());
      this.dialogRef.close(true);
    } catch {
      // The dialog stays open with the user's choice intact (R7): without a staging layer there is
      // no queue to recover from, so closing on a failed write would discard the work silently.
      this.error.set('Could not save the columns. Nothing has been changed.');
    } finally {
      this.saving.set(false);
    }
  }

  protected cancel(): void {
    this.dialogRef.close(false);
  }

  /**
   * Open the picker.
   *
   * A static `open` so no call site can size it wrongly or forget the modal contract (CLAUDE.md
   * §6). Resolves to true when the columns were changed, which is the caller's cue to reload.
   */
  static open(dialog: MatDialog) {
    return dialog.open<JiraColumnsDialog, undefined, boolean>(JiraColumnsDialog, {
      ...SEC_MODAL_DIALOG,
      width: '900px',
      height: '640px',
    });
  }
}
