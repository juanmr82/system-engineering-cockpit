import { Component, computed, debounced, inject, signal, viewChild } from '@angular/core';
import { httpResource } from '@angular/common/http';
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
import { EmptyState } from '../../../shared/empty-state/empty-state';
import type { DoorsTableView, ModuleTablesResponse } from '../../../shared/doors-table/doors-table.model';
import { normalize } from '../../../shared/text/normalize';
import { ModulesApiService } from '../modules/modules-api.service';
import type { ModuleAttributesResponse } from '../modules/modules.model';
import { CommentCell } from './cells/comment-cell';
import { IdCell } from './cells/id-cell';
import { IssuesCell } from './cells/issues-cell';
import { ReferencesCell } from './cells/references-cell';
import { TableCell } from './cells/table-cell';
import { ItemDetailPanel } from './item-detail-panel';
import { ReviewApiService } from './review-api.service';
import { ReviewSettingsDialog } from './review-settings-dialog';
import { describe, isHeading, isTable, isTablePart, refGroup, renderValue } from './review-table.model';
import type { RefGroup, ReviewCellContext, TableRow } from './review-table.model';
import type { ModuleObjectsResponse, ReviewRow } from './review.model';
import { ThreadPanel } from './thread-panel';

// Deepest outline level with a heading style of its own. Past this a heading keeps the level-6
// treatment rather than fading into the body text it is meant to introduce.
const MAX_HEADING_LEVEL = 6;

// The detail panel's width, in pixels. The minimum is where the label column of the attribute
// list stops leaving room for a value; the maximum is what still leaves the table usable, which is
// the whole reason the panel is beside it rather than over it.
const PANEL_WIDTH_DEFAULT = 380;
const PANEL_WIDTH_MIN = 280;
const PANEL_WIDTH_MAX = 900;
// One keyboard press of the separator. Coarse enough to cross the range in a sensible number of
// presses, fine enough to land on a width you meant.
const PANEL_WIDTH_STEP = 24;

/**
 * Whether a row has a link that does not land on an imported object.
 *
 * Both directions and both kinds: a target DOORS deleted, and one whose module has not been
 * imported. `unresolvedCount` rather than a list because that is what `refGroup` keeps — the
 * unresolved targets have no id to show, so the column counts them rather than naming them.
 */
function hasUnresolvedLink(entry: { outgoing: RefGroup; incoming: RefGroup }): boolean {
  return (
    entry.outgoing.deleted.length > 0 ||
    entry.incoming.deleted.length > 0 ||
    entry.outgoing.unresolvedCount > 0 ||
    entry.incoming.unresolvedCount > 0
  );
}

/**
 * Requirements → Req review (docs/REQ_REVIEW.md).
 *
 * One module's objects in document order, with traceability, the module's chosen attributes and
 * a comment thread per object side by side. Every column but the five fixed ones is built at
 * runtime from what the module actually carries — nothing about DOORS attribute names is
 * hardcoded.
 *
 * The table is ag-grid Community (ADR 0006). It was a CSS grid inside a CDK viewport until two
 * real modules arrived carrying 78 and 53 attributes, at which point the identity of a row and
 * the comment box were both scrolled off the right-hand edge and there was no resize and no sort.
 * **ID is pinned left; nothing is pinned right.** Issues and Comment were, and they scroll now:
 * two pinned columns took 470px out of the scrollable area, which squeezed the Description column
 * — the one holding the prose — between two fixed blocks. They keep their place as the last two
 * columns instead.
 *
 * **No buffer, no exit guard, any more** (`docs/req-review-comment-threads.md` §1). Every reply in
 * the thread panel posts as its own request the moment it is sent, so there is nothing this view
 * holds that a navigation could lose — the R7 batch exception `REQ_REVIEW.md` §9.1 used to carve
 * out for the Comment column is retired along with it.
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

  protected readonly attributes = httpResource<ModuleAttributesResponse>(() => {
    const ref = this.moduleRef();
    return ref ? `/api/v1/modules/${ref}/attributes` : undefined;
  });

  /**
   * The module's embedded tables, already reconstructed (`docs/DOORS_TABLES.md` §4.3).
   *
   * A second request rather than part of `/objects`, because a table is a *different shape* from a
   * row — a dense matrix with its own geometry and its own findings — and because the flat list
   * loads and stays useful whether or not this one answers.
   *
   * It takes no parameters: a table draws its cells' `Object Text` and nothing else, so it does not
   * depend on which attributes the view is showing (§6.3 is not implemented).
   */
  protected readonly tables = httpResource<ModuleTablesResponse>(() => {
    const ref = this.moduleRef();
    return ref ? ReviewApiService.tablesUrl(ref) : undefined;
  });

  protected readonly search = signal('');
  private readonly debouncedSearch = debounced(this.search, 250);
  protected readonly requirementsOnly = signal(false);
  /** Narrows the table to objects a consistency check found something wrong with. */
  protected readonly issuesOnly = signal(false);

  /**
   * Narrows the table to objects whose links do not land on an imported object.
   *
   * **Two stored states, one filter, and the merge is deliberate.** A link can point at an object
   * DOORS deleted (`deletedInSource`) or at one no import has brought in yet (`:__UNDEFINED`), and
   * the model keeps those strictly apart because they ask for opposite fixes — one is repaired in
   * DOORS, the other by importing a module. A reviewer sweeping a module is not making that
   * distinction yet: they are asking *which links do not go anywhere I can see*, and the row itself
   * says which kind it is once they are looking at it.
   *
   * Its own filter rather than a search of the Issues column because a deleted target is the one
   * finding a reviewer cannot act on from inside this table: the stale link exists only in DOORS,
   * so the working pattern is to collect every row carrying one and take the list there.
   */
  protected readonly unresolvedLinksOnly = signal(false);

  /**
   * Narrows the table to requirements with no outgoing `refersTo` — nothing they refine.
   *
   * An outgoing `refersTo` reads as *refines*: `A -[:refersTo]-> B` means A refines B, which is the
   * convention the Breakdown tab states in words (CLAUDE.md R5). So a requirement with none is one
   * that decomposes nothing above it — either a genuine top-level requirement or one whose link was
   * never drawn, and telling those apart is the review this filter exists for.
   *
   * **Restricted to requirement-like objects**, whatever "Requirements only" is set to. Headings,
   * information objects and table structure never carry a `refersTo`, so without the restriction
   * the filter would return most of the module and say nothing.
   *
   * Unresolved targets still count as a parent: the link *was* drawn, the module it points into
   * simply has not been imported. Reporting those as parentless would be a finding about the import
   * queue dressed up as a finding about the requirement.
   */
  protected readonly withoutParents = signal(false);

  /**
   * Narrows the table to objects that carry a comment thread, resolved or not.
   *
   * Replaces the module-level "hide resolved threads" setting the review settings dialog used to
   * offer (`docs/req-review-comment-threads.md` §5) — that hid content from a table-wide switch a
   * reviewer had to remember was on; this is an ordinary session filter like the four beside it,
   * answering "which of these have any conversation on them" rather than deciding what a resolved
   * thread's cell looks like.
   */
  protected readonly withCommentsOnly = signal(false);

  protected readonly selectedItem = signal<string | null>(null);

  /**
   * How wide the detail panel is, dragged by the separator between it and the table.
   *
   * A fixed 380px was too narrow for an object carrying a long `Object Text` and too wide for one
   * carrying almost nothing, and which of those is on screen changes with every click.
   *
   * **Component state, not a stored preference.** It outlives opening and closing the panel, which
   * is what makes dragging it worth doing, and dies with the view. Persisting it would mean browser
   * storage — which CLAUDE.md §2 does sanction for exactly this kind of per-user, per-machine
   * preference — but no view in this application writes there yet, and starting is a decision worth
   * making deliberately rather than as a side effect of a resize handle.
   */
  protected readonly panelWidth = signal(PANEL_WIDTH_DEFAULT);

  // The separator reports its own range, so a screen reader reads "380 of 280 to 900" rather than
  // a bare number with nothing to measure it against.
  protected readonly panelWidthMin = PANEL_WIDTH_MIN;
  protected readonly panelWidthMax = PANEL_WIDTH_MAX;

  /** Where the pointer and the panel edge were when the drag began; null when not dragging. */
  private panelDragFrom: { readonly x: number; readonly width: number } | null = null;

  /** True while any column carries a sort, so the reset control can say whether it does anything. */
  protected readonly sorted = signal(false);

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
    // A table is neither a requirement nor muted context. It is a figure: it gets its own class so
    // the cell can drop its padding, and it is deliberately *not* dimmed — the numbers in it are
    // as normative as the sentences around it.
    if (data.table) {
      return ['sec-grid__row--table'];
    }
    return data.row.requirementLike ? [] : ['sec-grid__row--context'];
  };

  // What a cell renderer may reach. Deliberately these functions and not `this` — see
  // ReviewCellContext.
  protected readonly cellContext: ReviewCellContext = {
    openDetail: (ref) => this.openDetail(ref),
    openThread: (row) => this.openThread(row),
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
   * The module's tables, keyed by the ref of the object that owns each one.
   *
   * `hasValue()` guards the read: `resource.value()` **throws** while the resource is in an error
   * state, and an unguarded read inside a computed the template consumes tears down the whole view.
   * A failed tables request must cost the tables and nothing else — the requirements stay on screen.
   */
  private readonly tablesByRef = computed<ReadonlyMap<string, DoorsTableView>>(() => {
    const loaded = this.tables.hasValue() ? this.tables.value().tables : [];
    return new Map(loaded.map((table) => [table.ref, table]));
  });


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
        // Blank for a table — and blank in the *value*, not just hidden by the renderer, so a copy
        // and any future export agree with the screen. DOORS shows nothing in the ID column for a
        // table, its rows or its cells (`docs/DOORS_TABLES.md` §6.3); the id is still on every
        // cell's tooltip and behind the "Table object IDs" toggle.
        cellRendererSelector: (params) => (params.data?.table ? undefined : { component: IdCell }),
        cellClass: 'sec-grid__cell sec-grid__cell--custom',
        valueGetter: (params) => (params.data?.table ? '' : (params.data?.row.id ?? '')),
      },
      {
        colId: 'type',
        headerName: 'Type',
        width: 120,
        // Blank for a table, as it is in DOORS. A table object carries an `Object Type` — usually
        // TBD, because DOORS does not type the parts of an embedded table — and printing it says
        // nothing about the figure on the row and reads as a finding about it. Blanked in the
        // *value*, like the ID column, so a copy agrees with the screen.
        valueGetter: (params) => (params.data?.table ? '' : (params.data?.row.type ?? '')),
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
        // A table is drawn *here*, in the main text column at its full width, which is exactly
        // where DOORS draws it (`docs/DOORS_TABLES.md` §1). Only the rows that are a table get the
        // renderer; every other row keeps the plain text ag-grid lays out itself.
        cellRendererSelector: (params) => (params.data?.table ? { component: TableCell } : undefined),
        // Two of our own classes, because the cell has to stop being a flex box for the table to
        // fill it rather than shrink to its longest word — and ag-grid's own rule is injected after
        // ours (styles/_grid.scss).
        cellClass: (params) =>
          params.data?.table ? 'sec-grid__cell sec-grid__cell--table' : 'sec-grid__cell',
        // Still a value, even where a renderer draws the cell: it is what a copy picks up and what
        // the search matches on. For a table that is its cell text, read left to right, top to
        // bottom — the same words a reviewer would search for.
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
        /**
         * The object's own value — and **empty for a table**, deliberately.
         *
         * A table's attribute values belong to its individual bands, and the whole table occupies
         * one row here, so there is nothing outside it for them to line up with. Collapsing them
         * into this cell was tried and is untenable: the reference module's largest table carries
         * 247 values for one attribute, of which exactly one is distinct, and the cell measured
         * 9 000 pixels tall.
         *
         * They are not dropped. They are drawn *inside* the table, as trailing columns aligned to
         * the band each one belongs to — which is what §6.3 is actually protecting. See the note
         * on `DoorsTable.attributes`.
         */
        valueGetter: (params) => {
          const data = params.data;
          return !data || data.table ? '' : (data.cells[index] ?? '');
        },
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
        // Incoming links come from each module's own export, which states every link pointing
        // at it, so this list is complete as of that export (§5.1). A source whose module has
        // not been imported is *in* the list, as an unresolved reference — which is the thing a
        // reviewer needs to see, and the reason an empty list can now be trusted.
        headerTooltip:
          'Outgoing references first, then incoming. A reference whose module has not been imported is listed as not yet imported rather than left out.',
      },
      {
        colId: 'issues',
        // Second from last, immediately before Comment: a reviewer reading down the Comment column
        // is the person who needs to know the object is incomplete, and the finding beside the box
        // where they respond to it saves the scroll back.
        //
        // **No longer pinned.** Pinning it and Comment to the right kept both on screen at any
        // horizontal scroll, at the cost of two columns' width off the scrollable area — which on a
        // module with 78 attributes left the Description column squeezed between two fixed blocks.
        // They now scroll with everything else and simply arrive last.
        headerName: 'Issues',
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
        // Last, and no longer pinned — see the note on Issues above. Wider than Issues/References:
        // the cell now holds a preview of the thread itself, not a count.
        width: 240,
        sortable: false,
        // Off, deliberately — the opposite of every other column. This cell never dictates the
        // row's height; it fills whatever height Description's own autoHeight already produced,
        // via an escaping renderer (`comment-cell.scss`). Left on, ag-grid would measure the cell
        // once at creation — before the lazily-fetched preview arrives — the same trap
        // `TableCell`'s own doc comment describes for a different column.
        autoHeight: false,
        cellRenderer: CommentCell,
        cellClass: 'sec-grid__cell sec-grid__cell--custom',
        valueGetter: (params) => (params.data?.row.thread ? String(params.data.row.thread.count) : ''),
      },
    ];
  });

  protected readonly allRows = computed<TableRow[]>(() => {
    const columns = this.attributeColumns();
    const tables = this.tablesByRef();
    return (this.objects.value()?.rows ?? [])
      // The rows and cells *inside* a table are hidden: the table they belong to is drawn on its
      // container's row, so showing them as well would print every cell twice (§5). Filtered here
      // rather than in `filtered` so the "n shown" readout counts what is actually on screen.
      .filter((row) => !isTablePart(row))
      .map((row, order) => {
        const cells = columns.map((name) => renderValue(row.attributes[name]));
        const table = isTable(row) ? (tables.get(row.ref) ?? null) : null;
        // A table's Description *value* is its cell text in reading order — what a copy picks up
        // and what the search matches, since the drawn table is markup the search cannot see.
        const description = table
          ? table.rows
              .flatMap((band) => band.cells.map((cell) => cell.text))
              .filter(Boolean)
              .join(' ')
          : describe(row);
        return {
          row,
          cells,
          description,
          table,
          headingLevel: isHeading(row) ? row.level : 0,
          // The index into the server's order, which is document order. Captured here because it
          // is the only place that order is still known — once ag-grid sorts, it is gone.
          order,
          // No longer includes comment text: the row carries only a thread *summary*
          // (docs/req-review-comment-threads.md §4), and the full messages are not loaded until a
          // reviewer opens the thread panel. A small, accepted regression from the single-note
          // column, which held its one comment's text right here.
          searchText: normalize([row.id, row.type ?? '', description, ...cells].join(' ')),
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
    const withoutParents = this.withoutParents();
    const unresolvedLinksOnly = this.unresolvedLinksOnly();
    const withCommentsOnly = this.withCommentsOnly();
    return this.allRows().filter(
      (entry) =>
        (!requirementsOnly || entry.row.requirementLike) &&
        (!issuesOnly || entry.row.issues.length > 0) &&
        (!unresolvedLinksOnly || hasUnresolvedLink(entry)) &&
        (!withoutParents ||
          (entry.row.requirementLike && entry.row.references.outgoing.length === 0)) &&
        (!withCommentsOnly || entry.row.thread !== null) &&
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

  protected readonly selectedModuleName = computed(
    () => this.modulesApi.modules.value()?.rows.find((row) => row.ref === this.moduleRef())?.name ?? '',
  );

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

  // --- Detail panel width -----------------------------------------------------------------------

  /**
   * Pointer events rather than mouse events, and pointer *capture* rather than a document listener.
   *
   * Capture is what keeps the drag working when the pointer leaves the 8px handle, which it does
   * immediately — and it means the move and up events arrive on the handle itself, so there is no
   * global listener to attach, forget to remove, or fire while another view is on screen.
   */
  protected onPanelResizeStart(event: PointerEvent, handle: HTMLElement): void {
    handle.setPointerCapture(event.pointerId);
    this.panelDragFrom = { x: event.clientX, width: this.panelWidth() };
    // Otherwise the browser starts a text selection across the table under the pointer.
    event.preventDefault();
  }

  protected onPanelResizeMove(event: PointerEvent): void {
    const from = this.panelDragFrom;
    if (from) {
      // Dragging left widens the panel: it is the right-hand edge of the table being moved.
      this.setPanelWidth(from.width - (event.clientX - from.x));
    }
  }

  protected onPanelResizeEnd(): void {
    this.panelDragFrom = null;
  }

  /** The keyboard half of the separator, so the panel is resizable without a pointer. */
  protected nudgePanelWidth(steps: number): void {
    this.setPanelWidth(this.panelWidth() + steps * PANEL_WIDTH_STEP);
  }

  private setPanelWidth(width: number): void {
    this.panelWidth.set(
      Math.min(PANEL_WIDTH_MAX, Math.max(PANEL_WIDTH_MIN, Math.round(width))),
    );
  }

  // --- Module selection -------------------------------------------------------------------------

  protected selectModule(ref: string): void {
    if (ref === this.moduleRef()) {
      return;
    }
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

  // --- Panel and dialog -------------------------------------------------------------------------

  protected openDetail(ref: string): void {
    this.selectedItem.set(ref);
  }

  /**
   * Opens the thread panel for one row (`docs/req-review-comment-threads.md`). Every write it
   * makes is already committed by the time it closes — there is no buffer here to save — so the
   * only thing this does afterward is refresh the row's own thread summary, and only if the panel
   * reports something actually changed.
   */
  protected openThread(row: ReviewRow): void {
    ThreadPanel.open(this.dialog, {
      itemRef: row.ref,
      itemLabel: row.id,
      onItemMentionClick: (ref) => this.openDetail(ref),
    })
      .afterClosed()
      .subscribe((changed) => {
        if (changed) {
          this.objects.reload();
        }
      });
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
          // Not the tables: their content is `Object Text` and geometry, neither of which the
          // attribute settings dialog can change.
          this.snackBar.open('Attribute settings saved', 'Dismiss', { duration: 4000 });
        }
      });
  }

  protected retry(): void {
    this.objects.reload();
    this.attributes.reload();
    this.tables.reload();
  }

}
