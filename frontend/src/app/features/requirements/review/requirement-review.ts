import { Component, computed, debounced, inject, signal, viewChild } from '@angular/core';
import { HttpErrorResponse, httpResource } from '@angular/common/http';
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
import { AgGridAngular } from 'ag-grid-angular';
import type { ColDef, GridApi, GridReadyEvent } from 'ag-grid-community';
import { secGridOptions } from '../../../core/grid/sec-grid';
import type { ProblemDetails } from '../../../core/error/problem-details';
import { ConfirmDialog } from '../../../shared/dialog/confirm-dialog';
import { EmptyState } from '../../../shared/empty-state/empty-state';
import { normalize } from '../../../shared/text/normalize';
import { ModulesApiService } from '../modules/modules-api.service';
import { CommentCell } from './cells/comment-cell';
import { IdCell } from './cells/id-cell';
import { IssuesCell } from './cells/issues-cell';
import { ReferencesCell } from './cells/references-cell';
import { ItemDetailPanel } from './item-detail-panel';
import { ReviewApiService } from './review-api.service';
import { ReviewSettingsDialog } from './review-settings-dialog';
import { describe, isHeading, isTableElement, refGroup, renderValue } from './review-table.model';
import type { RefGroup, ReviewCellContext, TableRow } from './review-table.model';
import type { ModuleObjectsResponse, ReviewComment, ReviewRow } from './review.model';

// Deepest outline level with a heading style of its own. Past this a heading keeps the level-6
// treatment rather than fading into the body text it is meant to introduce.
const MAX_HEADING_LEVEL = 6;

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
 * The table is ag-grid Community (ADR 0006). It was a CSS grid inside a CDK viewport until two
 * real modules arrived carrying 78 and 53 attributes, at which point the identity of a row and
 * the comment box were both scrolled off the right-hand edge and there was no resize and no sort.
 * ID is pinned left and Comment pinned right, so neither can leave the screen however far the
 * attributes run.
 *
 * The comment buffer is this component's own state and dies with it (R7): no store, no staging
 * layer, no global save. Because a table *can* be navigated away from, unlike a modal, this view
 * guards its own exit — see canLeaveReview in review.guard.ts.
 */
@Component({
  selector: 'sec-requirement-review',
  imports: [
    AgGridAngular,
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

  // The selected module lives in the URL so a view is shareable and survives reload (§2): seeded
  // from the query parameter once, and written back on every change.
  protected readonly moduleRef = signal<string | null>(
    inject(ActivatedRoute).snapshot.queryParamMap.get('module'),
  );

  // Cancelling the confirmation must put the control back: Material has already moved its own
  // value by the time selectionChange fires, and re-rendering an unchanged binding would not
  // undo that.
  private readonly moduleSelect = viewChild(MatSelect);

  private gridApi: GridApi<TableRow> | null = null;

  protected readonly objects = httpResource<ModuleObjectsResponse>(() => {
    const ref = this.moduleRef();
    return ref ? ReviewApiService.objectsUrl(ref) : undefined;
  });

  protected readonly attributes = httpResource<{
    attributes: { name: string; visible: boolean; fixed: boolean; mandatory: boolean }[];
  }>(() => {
    const ref = this.moduleRef();
    return ref ? `/api/v1/modules/${ref}/attributes` : undefined;
  });

  protected readonly search = signal('');
  private readonly debouncedSearch = debounced(this.search, 250);
  protected readonly requirementsOnly = signal(false);
  /** Narrows the table to objects a consistency check found something wrong with. */
  protected readonly issuesOnly = signal(false);

  protected readonly selectedItem = signal<string | null>(null);

  /** True while any column carries a sort, so the reset control can say whether it does anything. */
  protected readonly sorted = signal(false);

  // Keyed by ref, never by row position, so filtering, sorting or hiding a column cannot lose an
  // edit (§5.2). A ref maps to the text currently in its box; absence means "not edited".
  private readonly commentEdits = signal<ReadonlyMap<string, string>>(new Map());

  // What the server confirmed it stored, laid over the loaded rows. A successful save clears the
  // dirty marks *without reloading the table* (§5.2), which is exactly what the save response
  // exists for: the server decides what was written, and a null entry means the note was deleted.
  private readonly savedComments = signal<ReadonlyMap<string, ReviewComment | null>>(new Map());

  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);

  protected readonly gridOptions = secGridOptions<TableRow>();

  /**
   * The row's own classes (§5). Defined once, globally, in styles/_grid.scss.
   *
   * A heading is styled like a heading — H1 for outline level 1, H2 for level 2, and so on, across
   * the whole row and on a light blue ground. That replaces the old indent-by-level: the indent
   * was trying to draw a tree in a table that is a flat list, and it survived neither sorting nor
   * filtering, both of which reorder the rows it was measuring depth against. A heading's *style*
   * still says how deep it is, and it says it whatever order the rows are in.
   *
   * Information objects stay muted for the separate reason that they are context, not
   * requirements, and must not be read as one.
   */
  protected readonly getRowClass = (params: { data?: TableRow }): string[] => {
    const data = params.data;
    if (!data) {
      return [];
    }
    if (data.headingLevel > 0) {
      return ['sec-grid__row--heading', `sec-grid__row--h${Math.min(data.headingLevel, MAX_HEADING_LEVEL)}`];
    }
    return data.row.requirementLike ? [] : ['sec-grid__row--context'];
  };

  // What a cell renderer may reach. Deliberately these four functions and not `this` — see
  // ReviewCellContext.
  protected readonly cellContext: ReviewCellContext = {
    openDetail: (ref) => this.openDetail(ref),
    commentText: (row) => this.commentText(row),
    isDirty: (row) => this.isDirty(row),
    editComment: (row, text) => this.editComment(row, text),
  };

  // ag-grid identifies a row by this, not by its index, so filtering and sorting move rows around
  // without a comment box following the wrong object (§5.2).
  protected readonly getRowId = (params: { data: TableRow }): string => params.data.row.ref;

  // The attribute columns for this module, in the module's own attribute order. Never hardcoded:
  // switching to a module with a different attribute set changes the columns with no code change.
  //
  // `fixed` attributes are dropped: those are `Object Heading` and `Object Text`, which the
  // Description column already shows. The server decides which they are, so this filter does not
  // need to know their names (REQ_REVIEW.md §5).
  protected readonly attributeColumns = computed(() =>
    (this.attributes.value()?.attributes ?? [])
      .filter((attribute) => attribute.visible && !attribute.fixed)
      .map((a) => a.name),
  );

  /**
   * The grid's columns, rebuilt whenever the module's visible attribute set changes.
   *
   * **No column has a `field`.** ag-grid reads a dot in `field` as a property path, so
   * `field: 'REQ. Priorität'` would look for `row['REQ']['Priorität']` and render blank with no
   * error at all — and the reference module has attribute names exactly like that. Every column
   * carries a synthetic `colId` and reads through a `valueGetter` (ADR 0006).
   *
   * **Exactly one column has `flex`, and it is Description.** Every other column carries an
   * explicit width. Originally none did: a flex column takes its width from the space left over,
   * so dragging any column's edge recomputed every flex column beside it — widening one visibly
   * shrank its neighbours, and shrinking the last one before Comment pulled a further column into
   * view.
   *
   * All-explicit fixed that and introduced a ghost column. A module with no visible attributes
   * (SRD) totals 1 200px of columns in a 1 588px grid, and the 388px left over sits between
   * References and the pinned Comment — bounded by a rule on each side, carrying the row
   * background — which reads as a real, empty, unnamed column.
   *
   * One flex column answers both, and it is self-limiting rather than a compromise: flex only
   * distributes *leftover* space. A module with attribute columns overflows the viewport, so
   * there is no leftover, Description sits at its `minWidth`, and resizing any column reflows
   * nothing — the case the original complaint came from. A module without them has slack, and
   * Description absorbing it is exactly what should happen to the column holding the prose.
   */
  protected readonly columnDefs = computed<ColDef<TableRow>[]>(() => {
    const attributeColumns = this.attributeColumns();

    return [
      {
        colId: 'id',
        headerName: 'ID',
        // The first of the two columns that must never leave the screen: without it, past about
        // column six there is nothing on a row saying which requirement it is.
        pinned: 'left',
        width: 140,
        cellRenderer: IdCell,
        cellClass: 'sec-grid__cell sec-grid__cell--custom',
        valueGetter: (params) => params.data?.row.id ?? '',
      },
      {
        colId: 'type',
        headerName: 'Type',
        width: 120,
        valueGetter: (params) => params.data?.row.type ?? '',
      },
      {
        colId: 'description',
        // Was "Name", showing `__name`. A heading now reads as its outline number plus its heading
        // text and everything else as its requirement statement, which is what a reviewer is
        // actually reading down the page (§5). Always shown — there is no configuration that
        // removes it.
        headerName: 'Description',
        // The one flex column, so a module with few attributes has no unclaimed strip pretending
        // to be a column. `minWidth` is what keeps it honest when the table does overflow.
        flex: 1,
        minWidth: 380,
        valueGetter: (params) => params.data?.description ?? '',
        // Document order, which is `__sortKey` order — the segment-wise numeric expansion of the
        // outline number, so 4.3.2 sorts before 4.3.2-0 before 4.3.2-1. Comparing the outline
        // numbers as strings here is the exact mistake `__sortKey` exists to prevent, and
        // re-deriving the numeric comparison would be a second implementation of it.
        comparator: (_a, _b, nodeA, nodeB) => (nodeA.data?.order ?? 0) - (nodeB.data?.order ?? 0),
      },
      ...attributeColumns.map<ColDef<TableRow>>((name, index) => ({
        colId: `attr-${index}`,
        // The attribute name is a display label and nothing else — never a key, a class or a URL
        // part (CLAUDE.md §11). The value is addressed by index.
        headerName: name,
        width: 200,
        valueGetter: (params) => params.data?.cells[index] ?? '',
      })),
      {
        colId: 'references',
        headerName: 'References',
        width: 200,
        sortable: false,
        cellRenderer: ReferencesCell,
        cellClass: 'sec-grid__cell sec-grid__cell--custom',
        // The cell's *value*, as distinct from what the renderer draws. The renderer reads
        // `params.data` and would work without this, but the value is what a copy-to-clipboard
        // and any future export pick up, and a column with neither `field` nor `valueGetter` has
        // no value at all — so this column would export as blank while showing content.
        //
        // It describes the **unresolved** targets too, not just the resolved ones. A placeholder
        // carries no DOORS id, so listing ids alone gives an empty value for exactly the object
        // whose "3 not yet imported" matters most — 376 SRD objects are that shape.
        valueGetter: (params) => {
          const data = params.data;
          if (!data) {
            return '';
          }
          const describe = (group: RefGroup): string =>
            [
              ...group.resolved.map((reference) => reference.id ?? ''),
              group.unresolvedCount ? `${group.unresolvedCount} not yet imported` : '',
            ]
              .filter(Boolean)
              .join(' ');
          return [describe(data.outgoing), describe(data.incoming)].filter(Boolean).join(' ');
        },
        // Incoming links are incomplete by design — importers ingest out-links only, so an
        // incoming link exists only where the referencing module has itself been imported. An
        // empty list must never be read as "orphan requirement" (§5.1).
        headerTooltip:
          'Incoming references are only known for modules that have been imported. An empty list does not mean an object is unreferenced.',
      },
      {
        colId: 'issues',
        // Sits immediately before Comment, and pinned with it: a reviewer reading down the
        // Comment column is the person who needs to know the object is incomplete, and putting
        // the finding next to the box where they respond to it saves the scroll back.
        headerName: 'Issues',
        pinned: 'right',
        width: 190,
        sortable: false,
        cellRenderer: IssuesCell,
        cellClass: 'sec-grid__cell sec-grid__cell--custom',
        // The value is what a copy or an export picks up, and it is also what makes the cell
        // non-empty so the search can match it.
        valueGetter: (params) => (params.data?.row.issues ?? []).join(' '),
      },
      {
        colId: 'comment',
        headerName: 'Comment',
        // The second column that must never leave the screen. Reading a requirement and writing
        // the comment on it is the whole job, and with fourteen columns the box sat about nine
        // columns off the right-hand edge.
        pinned: 'right',
        width: 280,
        sortable: false,
        cellRenderer: CommentCell,
        // The one column that opts out of the shared wrap/autoHeight defaults, and it has to.
        // With `autoHeight` ag-grid nests the cell's content in a flex wrapper sized to that
        // content, and a textarea's intrinsic width is its `cols` — 20 characters — so the editor
        // collapsed to half its cell instead of filling it. Nothing is lost: a textarea wraps its
        // own text, and the row is already as tall as the wrapping columns beside it made it.
        wrapText: false,
        autoHeight: false,
        // Not `--custom`: the comment editor fills its cell edge to edge, so this cell has no
        // padding of its own at all — the editor supplies it (§5.2).
        cellClass: 'sec-grid__cell sec-grid__cell--editor',
      },
    ];
  });

  protected readonly allRows = computed<TableRow[]>(() => {
    const columns = this.attributeColumns();
    return (this.objects.value()?.rows ?? [])
      // Table structure is hidden for now (§5). Filtered here rather than in `filtered` so the
      // "n shown" readout counts what is actually on screen.
      .filter((row) => !isTableElement(row))
      .map((row, order) => {
        const cells = columns.map((name) => renderValue(row.attributes[name]));
        const description = describe(row);
        return {
          row,
          cells,
          description,
          headingLevel: isHeading(row) ? row.level : 0,
          // The index into the server's order, which is document order. Captured here because it
          // is the only place that order is still known — once ag-grid sorts, it is gone.
          order,
          searchText: normalize(
            [row.id, row.type ?? '', description, ...cells, row.comment?.text ?? ''].join(' '),
          ),
          outgoing: refGroup(row.references.outgoing),
          incoming: refGroup(row.references.incoming),
        };
      });
  });

  // Filtering stays this component's own, rather than ag-grid's quick filter: `normalize` strips
  // accents, and DOORS names carry umlauts a reviewer may be typing without (§3).
  protected readonly filtered = computed(() => {
    const term = normalize(this.debouncedSearch.value() ?? '');
    const requirementsOnly = this.requirementsOnly();
    const issuesOnly = this.issuesOnly();
    return this.allRows().filter(
      (entry) =>
        (!requirementsOnly || entry.row.requirementLike) &&
        (!issuesOnly || entry.row.issues.length > 0) &&
        (!term || entry.searchText.includes(term)),
    );
  });

  /**
   * How many loaded objects are missing a mandatory value — computed on read, never stored (R2).
   *
   * Counted over `allRows` rather than `filtered`, so the warning states a fact about the module
   * rather than about the current search: filtering the finding out of sight must not read as
   * having fixed it.
   */
  protected readonly issueCount = computed(
    () => this.allRows().filter((entry) => entry.row.issues.length > 0).length,
  );

  /**
   * Whether the module has any mandatory policy at all — which is *not* the same question as
   * whether it has violations, and the difference has to reach the user in words.
   *
   * Zero violations because every value is filled and zero violations because nobody has marked
   * anything mandatory look identical in a table, and an export sanitised for sharing blanks
   * `Object Type` so every object lands out of scope too (CLAUDE.md §10). A module with no policy
   * gets no reassuring green nothing — it gets no claim at all.
   */
  protected readonly hasMandatoryPolicy = computed(() =>
    (this.attributes.value()?.attributes ?? []).some((attribute) => attribute.mandatory),
  );

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

  // --- Grid -------------------------------------------------------------------------------------

  protected onGridReady(event: GridReadyEvent<TableRow>): void {
    this.gridApi = event.api;
  }

  protected onSortChanged(): void {
    this.sorted.set(
      (this.gridApi?.getColumnState() ?? []).some((column) => column.sort !== null && column.sort !== undefined),
    );
  }

  /**
   * Back to document order (§5).
   *
   * Document order is the order the rows were loaded in — `__sortKey`, never `objectNumber`, which
   * does not sort correctly as a string — so clearing every column's sort is all that is needed.
   */
  protected resetSort(): void {
    this.gridApi?.applyColumnState({ defaultState: { sort: null } });
    this.sorted.set(false);
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
    // A new module means a new attribute set, so a sort on a column that no longer exists would
    // otherwise persist as a sort on nothing.
    this.resetSort();
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

      // The comment cells hold their own text and dirty flag, so they have to be told the buffer
      // was cleared. Only that column, and only the rendered rows — this is not a reload.
      this.gridApi?.refreshCells({ columns: ['comment'], force: true });

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
          // **Both**, and the second one is not optional. The dialog writes three things: which
          // attributes are columns, which are verification attributes, and which are *mandatory*.
          // Columns come from the attribute list — but the mandatory verdict is computed per row
          // and arrives on the objects (§5.3), so reloading only the attributes left the Issues
          // column showing the answer to the previous policy until the browser was refreshed by
          // hand: newly mandatory attributes reported nothing, and un-ticked ones kept reporting.
          this.attributes.reload();
          this.objects.reload();
          // Pending comments are keyed by object ref and are held in this component, not on the
          // rows, so they survive both reloads untouched (§6).
          this.snackBar.open('Attribute settings saved', 'Dismiss', { duration: 4000 });
        }
      });
  }

  protected retry(): void {
    this.objects.reload();
    this.attributes.reload();
  }
}
