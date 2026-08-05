import { Component, computed, debounced, inject, signal, viewChild } from '@angular/core';
import { HttpErrorResponse, httpResource } from '@angular/common/http';
import { ScrollingModule } from '@angular/cdk/scrolling';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelect, MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, Router } from '@angular/router';
import type { ProblemDetails } from '../../../core/error/problem-details';
import { ConfirmDialog } from '../../../shared/dialog/confirm-dialog';
import { EmptyState } from '../../../shared/empty-state/empty-state';
import { ModulesApiService } from '../modules/modules-api.service';
import { ItemDetailPanel } from './item-detail-panel';
import { ReviewApiService } from './review-api.service';
import { ReviewSettingsDialog } from './review-settings-dialog';
import type { ModuleObjectsResponse, Reference, ReviewComment, ReviewRow } from './review.model';

// A row plus everything the view derives from it once, at load: the search haystack and the
// visible attribute values in column order. Recomputing either per keystroke over ~1 000 rows is
// what makes a table feel slow.
interface TableRow {
  readonly row: ReviewRow;
  readonly cells: string[];
  readonly searchText: string;
  readonly outgoing: RefGroup;
  readonly incoming: RefGroup;
}

/**
 * One direction of the References cell, split at load.
 *
 * Unresolved targets are counted rather than listed. Each one would otherwise render the same
 * sentence — "Not yet imported", with no id to tell them apart, because a placeholder has none —
 * and against the reference module that is three identical phrases in a 46px row, clipped. The
 * count says the same thing in the space available, and the tooltip names the modules to import.
 */
interface RefGroup {
  readonly resolved: Reference[];
  readonly unresolvedCount: number;
  readonly unresolvedTooltip: string;
}

function refGroup(references: Reference[]): RefGroup {
  const resolved = references.filter((reference) => reference.resolved);
  const unresolved = references.filter((reference) => !reference.resolved);
  const modules = [
    ...new Set(unresolved.map((reference) => reference.moduleName).filter((name) => !!name)),
  ];

  return {
    resolved,
    unresolvedCount: unresolved.length,
    unresolvedTooltip: modules.length
      ? `Not yet imported. Import ${modules.join(', ')} to see ${unresolved.length === 1 ? 'it' : 'them'}.`
      : 'Not yet imported, and neither is the module these objects belong to.',
  };
}

// Case- and accent-insensitive, the same contract the Modules search follows: what the user sees
// is what gets searched.
function normalize(value: string): string {
  return value
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLocaleLowerCase();
}

function renderValue(value: unknown): string {
  // "" from DOORS means the attribute exists and is empty, which is not the same as absent: it
  // renders as an empty cell, never as a fallback (CLAUDE.md §11).
  return value === null || value === undefined ? '' : String(value);
}

function extractErrorDetail(error: unknown): string {
  if (error instanceof HttpErrorResponse && error.error) {
    const problem = error.error as Partial<ProblemDetails>;
    if (problem.detail) {
      return problem.detail;
    }
  }
  return 'Something went wrong saving these comments. Please try again.';
}

/**
 * Requirements → Req review (docs/REQ_REVIEW.md).
 *
 * One module's objects in document order, with traceability, the module's chosen attributes and
 * one comment per object side by side. Every column but the five fixed ones is built at runtime
 * from what the module actually carries — nothing about DOORS attribute names is hardcoded.
 *
 * The comment buffer is this component's own state and dies with it (R7): no store, no staging
 * layer, no global save. Because a table *can* be navigated away from, unlike a modal, this view
 * guards its own exit — see canLeaveReview in review.guard.ts.
 */
@Component({
  selector: 'sec-requirement-review',
  imports: [
    EmptyState,
    ItemDetailPanel,
    MatButtonModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    MatSelectModule,
    MatTooltipModule,
    ScrollingModule,
  ],
  templateUrl: './requirement-review.html',
  styleUrl: './requirement-review.scss',
  host: {
    // The tab-close half of the exit guard. The in-app half is the router guard; neither one is a
    // global store, and both are scoped to this view's own buffer (§9.1).
    '(window:beforeunload)': 'onBeforeUnload($event)',
  },
})
export class RequirementReview {
  protected readonly modulesApi = inject(ModulesApiService);
  private readonly reviewApi = inject(ReviewApiService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly router = inject(Router);

  /** Fixed row height, in px. CDK virtual scroll needs one, and 984 rows need CDK virtual scroll. */
  protected readonly rowHeight = 46;

  // The selected module lives in the URL so a view is shareable and survives reload (§2): seeded
  // from the query parameter once, and written back on every change.
  protected readonly moduleRef = signal<string | null>(
    inject(ActivatedRoute).snapshot.queryParamMap.get('module'),
  );

  // Cancelling the confirmation must put the control back: Material has already moved its own
  // value by the time selectionChange fires, and re-rendering an unchanged binding would not
  // undo that.
  private readonly moduleSelect = viewChild(MatSelect);

  protected readonly objects = httpResource<ModuleObjectsResponse>(() => {
    const ref = this.moduleRef();
    return ref ? ReviewApiService.objectsUrl(ref) : undefined;
  });

  protected readonly attributes = httpResource<{ attributes: { name: string; visible: boolean }[] }>(() => {
    const ref = this.moduleRef();
    return ref ? `/api/v1/modules/${ref}/attributes` : undefined;
  });

  protected readonly search = signal('');
  private readonly debouncedSearch = debounced(this.search, 250);
  protected readonly requirementsOnly = signal(false);

  protected readonly selectedItem = signal<string | null>(null);

  // Keyed by ref, never by row position, so filtering, sorting or hiding a column cannot lose an
  // edit (§5.2). A ref maps to the text currently in its box; absence means "not edited".
  private readonly commentEdits = signal<ReadonlyMap<string, string>>(new Map());

  // What the server confirmed it stored, laid over the loaded rows. A successful save clears the
  // dirty marks *without reloading the table* (§5.2), which is exactly what the save response
  // exists for: the server decides what was written, and a null entry means the note was deleted.
  private readonly savedComments = signal<ReadonlyMap<string, ReviewComment | null>>(new Map());

  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);

  // The attribute columns for this module, in the module's own attribute order. Never hardcoded:
  // switching to a module with a different attribute set changes the columns with no code change.
  protected readonly attributeColumns = computed(() =>
    (this.attributes.value()?.attributes ?? []).filter((attribute) => attribute.visible).map((a) => a.name),
  );

  // A DOORS attribute name may contain spaces, dots, slashes and umlauts, so it is never used as a
  // key, a class or a URL part — only as a display label. Cells are addressed by index.
  protected readonly columnTemplate = computed(() =>
    ['150px', '120px', 'minmax(240px, 2fr)', ...this.attributeColumns().map(() => 'minmax(160px, 1fr)'), '220px', '260px'].join(' '),
  );

  protected readonly allRows = computed<TableRow[]>(() => {
    const columns = this.attributeColumns();
    return (this.objects.value()?.rows ?? []).map((row) => {
      const cells = columns.map((name) => renderValue(row.attributes[name]));
      return {
        row,
        cells,
        searchText: normalize(
          [row.id, row.type ?? '', row.name, ...cells, row.comment?.text ?? ''].join(' '),
        ),
        outgoing: refGroup(row.references.outgoing),
        incoming: refGroup(row.references.incoming),
      };
    });
  });

  protected readonly filtered = computed(() => {
    const term = normalize(this.debouncedSearch.value() ?? '');
    const requirementsOnly = this.requirementsOnly();
    return this.allRows().filter(
      (entry) =>
        (!requirementsOnly || entry.row.requirementLike) &&
        (!term || entry.searchText.includes(term)),
    );
  });

  protected readonly total = computed(() => this.objects.value()?.total ?? 0);
  protected readonly truncated = computed(() => this.objects.value()?.truncated ?? false);

  protected readonly dirtyCount = computed(() => this.commentEdits().size);
  protected readonly canSave = computed(() => this.dirtyCount() > 0 && !this.saving());

  protected readonly selectedModuleName = computed(
    () => this.modulesApi.modules.value()?.rows.find((row) => row.ref === this.moduleRef())?.name ?? '',
  );

  /** True while the reviewer has comments that have not been written to the graph. */
  hasPendingComments(): boolean {
    return this.commentEdits().size > 0;
  }

  protected onBeforeUnload(event: BeforeUnloadEvent): void {
    if (this.hasPendingComments()) {
      event.preventDefault();
    }
  }

  // --- Module selection -------------------------------------------------------------------------

  protected async selectModule(ref: string): Promise<void> {
    if (ref === this.moduleRef()) {
      return;
    }
    if (!(await this.confirmDiscard())) {
      const select = this.moduleSelect();
      if (select) {
        select.value = this.moduleRef();
      }
      return;
    }
    this.commentEdits.set(new Map());
    this.savedComments.set(new Map());
    this.search.set('');
    this.selectedItem.set(null);
    this.moduleRef.set(ref);
    // The module is part of the address, not of a store: reloading or sharing the URL reopens the
    // same table (§2). Replace rather than push — choosing a module is not a navigation step.
    void this.router.navigate([], { queryParams: { module: ref }, replaceUrl: true });
  }

  /** Resolves true when it is safe to drop the buffer: nothing pending, or the user said so. */
  async confirmDiscard(): Promise<boolean> {
    if (!this.hasPendingComments()) {
      return true;
    }
    const count = this.dirtyCount();
    const confirmed = await new Promise<boolean | undefined>((resolve) => {
      ConfirmDialog.open(this.dialog, {
        title: 'Discard unsaved comments?',
        message:
          count === 1
            ? 'One comment has not been saved yet. Leaving now discards it.'
            : `${count} comments have not been saved yet. Leaving now discards them.`,
        confirmLabel: 'Discard',
        cancelLabel: 'Keep editing',
      })
        .afterClosed()
        .subscribe(resolve);
    });
    return confirmed === true;
  }

  // --- Comments ---------------------------------------------------------------------------------

  /** What the box shows: the pending edit, else what was last saved, else what was loaded. */
  protected commentText(row: ReviewRow): string {
    const edit = this.commentEdits().get(row.ref);
    return edit ?? this.storedText(row);
  }

  // The stored value a pending edit is measured against — the overlay first, because after a save
  // the loaded row still carries the text the server has already replaced.
  private storedText(row: ReviewRow): string {
    const saved = this.savedComments();
    return saved.has(row.ref) ? (saved.get(row.ref)?.text ?? '') : (row.comment?.text ?? '');
  }

  protected isDirty(row: ReviewRow): boolean {
    return this.commentEdits().has(row.ref);
  }

  protected editComment(row: ReviewRow, text: string): void {
    const edits = new Map(this.commentEdits());
    if (text === this.storedText(row)) {
      // Typed back to where it started: not an edit any more, so it must not be saved as one.
      edits.delete(row.ref);
    } else {
      edits.set(row.ref, text);
    }
    this.commentEdits.set(edits);
  }

  protected async saveComments(): Promise<void> {
    const moduleRef = this.moduleRef();
    const edits = this.commentEdits();
    if (!moduleRef || edits.size === 0) {
      return;
    }

    this.saving.set(true);
    this.saveError.set(null);
    try {
      // One request, one transaction: either every comment is written or none is, and on failure
      // the edits stay on screen (§5.2).
      const response = await this.reviewApi.saveComments(moduleRef, {
        comments: [...edits].map(([ref, text]) => ({ ref, text })),
      });

      // The server's answer, not the request, is what the table now shows — and it is applied as
      // an overlay rather than by refetching, so the reviewer keeps their scroll position (§5.2).
      const saved = new Map(this.savedComments());
      for (const entry of response.saved) {
        saved.set(entry.ref, entry.comment);
      }
      this.savedComments.set(saved);
      this.commentEdits.set(new Map());

      this.snackBar.open(
        edits.size === 1 ? 'Comment saved' : `${edits.size} comments saved`,
        'Dismiss',
        { duration: 4000 },
      );
    } catch (error) {
      this.saveError.set(extractErrorDetail(error));
    } finally {
      this.saving.set(false);
    }
  }

  // --- Panel and dialog -------------------------------------------------------------------------

  protected openDetail(ref: string): void {
    this.selectedItem.set(ref);
  }

  // §7 asks for a message when an unresolved target is clicked. It is a tooltip on a plain span
  // instead of a snackbar on a button: the same wording, naming the same module, but the target
  // never looks clickable in the first place — which is what §5.1 asks for.

  protected closeDetail(): void {
    this.selectedItem.set(null);
  }

  protected openSettings(): void {
    const ref = this.moduleRef();
    if (!ref) {
      return;
    }
    ReviewSettingsDialog.open(this.dialog, { ref, name: this.selectedModuleName() })
      .afterClosed()
      .subscribe((saved) => {
        if (saved) {
          // Columns come from the attribute list, so that is what has to be re-read. Pending
          // comments are keyed by object and survive the column change untouched (§6).
          this.attributes.reload();
          this.snackBar.open('Attribute settings saved', 'Dismiss', { duration: 4000 });
        }
      });
  }

  protected retry(): void {
    this.objects.reload();
    this.attributes.reload();
  }

  // Rows are addressed by ref: recycling a rendered row must never carry one object's comment box
  // over to another (§5.2, the edit buffer is keyed by object).
  protected readonly trackByRef = (_index: number, entry: TableRow): string => entry.row.ref;
}
