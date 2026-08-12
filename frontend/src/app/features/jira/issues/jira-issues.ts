import { Component, computed, debounced, inject, linkedSignal, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorModule } from '@angular/material/paginator';
import type { PageEvent } from '@angular/material/paginator';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatDialog } from '@angular/material/dialog';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AgGridAngular } from 'ag-grid-angular';
import type { ColDef, GetRowIdParams, SortChangedEvent } from 'ag-grid-community';
import { secGridOptions } from '../../../core/grid/sec-grid';
import { EmptyState } from '../../../shared/empty-state/empty-state';
import { JiraIssuesApiService } from './jira-issues-api.service';
import { JiraColumnsDialog } from '../columns/jira-columns-dialog';
import { JiraKeyCell } from './cells/jira-key-cell';
import { JiraLinkCell } from './cells/jira-link-cell';
import { JiraLinksCell } from './cells/jira-links-cell';
import { JiraValueCell } from './cells/jira-value-cell';
import type {
  JiraColumn,
  JiraIssueRow,
  JiraIssuesPage,
  JiraIssuesQuery,
} from './jira-issues.model';

/** Page sizes the paginator offers. The largest is the server's own cap; asking for more is futile. */
const PAGE_SIZES = [25, 50, 100, 200];

/**
 * JIRA → Issues (`docs/JIRA_ISSUES_FEATURE_SPEC.md` §13.2).
 *
 * ## Server-side everything
 *
 * Paging, sorting and searching all round-trip. The search reads **every field** of every issue,
 * not the page in hand and not only the key and the summary (ADR 0014 point 22). This is the one table in the application that
 * cannot load its rows and filter them in the browser: 784 issues on the reference instance is
 * already more than a page, and a real instance is tens of thousands. Everything below follows from
 * that — the query is a signal, the resource re-runs when it changes, and the grid holds one page.
 *
 * ## Three fixed columns, and the rest chosen
 *
 * Type, Key and the JIRA link are the *fixed* columns — the ones a user cannot remove. They are
 * declared here and never described by the server, which is what makes them impossible to hide.
 * Everything between Key and the link is the user's own choice out of the field catalogue, and it
 * arrives **in the same response as the values** — fetched apart, a table can draw last request's
 * headers over this request's cells.
 *
 * ## The grid sorts nothing
 *
 * ag-grid Community has no server-side row model, so the grid holds one page and the *server* owns
 * the order. `comparator` returning 0 on the sortable column is what stops the grid re-sorting the
 * fifty rows it can see into an order that disagrees with the other fifteen pages — the header still
 * shows the indicator, and [onSortChanged] turns it into a request.
 */
@Component({
  selector: 'sec-jira-issues',
  imports: [
    AgGridAngular,
    EmptyState,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatPaginatorModule,
    MatProgressBarModule,
    MatTooltipModule,
  ],
  templateUrl: './jira-issues.html',
  styleUrl: './jira-issues.scss',
})
export class JiraIssues {
  private readonly api = inject(JiraIssuesApiService);

  protected readonly search = signal('');
  private readonly debouncedSearch = debounced(this.search, 250);

  protected readonly page = signal(0);
  protected readonly size = signal(PAGE_SIZES[1]);
  protected readonly sort = signal<JiraIssuesQuery['sort']>('key');
  private readonly dialog = inject(MatDialog);
  protected readonly direction = signal<JiraIssuesQuery['dir']>('asc');

  protected readonly pageSizes = PAGE_SIZES;

  /**
   * The request's identity, in one place.
   *
   * The search term is read from the debounced signal rather than the raw one, so typing costs one
   * request rather than one per keystroke — and it is *this* value, not the input's, that decides
   * whether the "no matches" state names a term the server has actually been asked about.
   */
  private readonly query = computed<JiraIssuesQuery>(() => ({
    page: this.page(),
    size: this.size(),
    sort: this.sort(),
    dir: this.direction(),
    q: this.debouncedSearch.value() ?? '',
  }));

  protected readonly issues = this.api.issues(this.query);

  /**
   * The last page the server answered, held across the request for the next one.
   *
   * **`httpResource` drops its value the moment a new request starts**, so a view that reads it
   * directly re-renders from nothing on every keystroke — and the search box that started the
   * request is inside that view. It is destroyed and re-created a quarter of a second after the
   * user types, taking the focus and the caret with it, which is exactly the "type one letter,
   * click again" behaviour this fixes. Latching the last page keeps one table on screen from the
   * first answer onwards; [issues].isLoading is how the reader is told a newer one is coming.
   *
   * `undefined` therefore means one thing only: nothing has answered yet.
   */
  private readonly lastPage = linkedSignal<JiraIssuesPage | undefined, JiraIssuesPage | undefined>({
    source: () => (this.issues.hasValue() ? this.issues.value() : undefined),
    computation: (page, previous) => page ?? previous?.value,
  });

  /** False only before the first answer — a reload keeps the page it already has. */
  protected readonly hasPage = computed(() => this.lastPage() !== undefined);

  /**
   * The page's rows, as an array ag-grid will accept.
   *
   * The wire types are `readonly` because nothing may edit a server response in place, and
   * `[rowData]` is typed mutable — so the copy is made here, once per page, rather than by widening
   * the model and losing the guarantee everywhere else.
   */
  protected readonly rows = computed<JiraIssueRow[]>(() => [...(this.lastPage()?.rows ?? [])]);

  protected readonly total = computed(() => this.lastPage()?.total ?? 0);

  /** The configured columns, as the server resolved them for this page. */
  protected readonly columns = computed<readonly JiraColumn[]>(() => this.lastPage()?.columns ?? []);

  /**
   * Whether the graph holds no issues at all, as opposed to none matching a search.
   *
   * The two need different words and different actions: one is an invitation to import, the other
   * to retype. `q` is what tells them apart, because `total` is the *filtered* count and is zero in
   * both cases.
   */
  protected readonly isEmpty = computed(
    () => this.hasPage() && this.total() === 0 && !this.query().q,
  );

  protected readonly hasNoMatches = computed(
    () => this.hasPage() && this.total() === 0 && this.query().q.length > 0,
  );

  protected readonly gridOptions = secGridOptions<JiraIssueRow>();

  /**
   * The grid's columns: the two fixed ones, the configured ones, then the link.
   *
   * A `computed` rather than a constant, because the middle of it is data — it changes when the
   * picker saves and when a field goes stale. Every column is keyed by `colId` and read with a
   * `valueGetter`: a `field` is never used in this application, because ag-grid reads a dot in one
   * as a property path (CLAUDE.md §6).
   */
  protected readonly columnDefs = computed<ColDef<JiraIssueRow>[]>(() => [
    {
      colId: 'issueType',
      headerName: 'Type',
      // The issue type's *icon* needs the icon proxy (spec §9.1), which does not exist: a direct
      // <img> to JIRA's own iconUrl would send the browser to a host it cannot authenticate
      // against, and the token is deliberately never in the browser (R5, spec §3). The name is what
      // the tooltip would have said anyway.
      valueGetter: (params) => params.data?.issueTypeName ?? '',
      width: 130,
      sortable: false,
    },
    {
      colId: 'key',
      headerName: 'Key',
      // The value getter is here for sorting and copy-paste; the renderer is what is seen.
      valueGetter: (params) => params.data?.key ?? '',
      cellRenderer: JiraKeyCell,
      width: 190,
      sortable: true,
      // The server sorted this page. Re-sorting the rows in hand would order fifty of the results
      // correctly and the other seven hundred and thirty-four not at all.
      comparator: () => 0,
    },
    ...this.columns().map<ColDef<JiraIssueRow>>((column) => ({
      colId: column.fieldId,
      // A stale column shows the field id, which is the only name JIRA left it with. That is not
      // the namespace leaking (R5): a JIRA field id is source data, exactly like an attribute name.
      headerName: column.name,
      headerTooltip: column.stale
        ? 'This field no longer exists in JIRA — it will disappear after the next import.'
        : undefined,
      valueGetter: (params) => params.data?.values[column.fieldId] ?? null,
      cellRenderer: JiraValueCell,
      // An array has no single value to order by, and a stale column has no values at all.
      sortable: column.sortable && !column.stale,
      comparator: () => 0,
      flex: 1,
      minWidth: 140,
      headerClass: column.stale
        ? 'sec-grid__header-cell sec-jira-issues__stale-header'
        : 'sec-grid__header-cell',
    })),
    {
      colId: 'relatedIssues',
      headerName: 'Related',
      // Fixed, and always immediately before the link out: the two are controls rather than values,
      // and a configured column appearing between them would put a value after the end of the data.
      valueGetter: (params) => params.data?.linkCount ?? 0,
      cellRenderer: JiraLinksCell,
      width: 92,
      sortable: false,
      resizable: false,
      cellClass: 'sec-grid__cell sec-jira-issues__link-cell',
    },
    {
      colId: 'openInJira',
      // Header-less by design (spec §13.2, point 14.6): the column holds one control, the icon says
      // what it does, and a header would name a thing that is not a property of the issue.
      headerName: '',
      cellRenderer: JiraLinkCell,
      width: 56,
      sortable: false,
      resizable: false,
      // A link is not a value: there is nothing to select, and letting it stretch would give the
      // last column the leftover width of the table.
      cellClass: 'sec-grid__cell sec-jira-issues__link-cell',
    },
  ]);

  /**
   * The row identity ag-grid keys its DOM by.
   *
   * `ref`, not the array index: a page change replaces every row, and an index-keyed grid would
   * reuse the first row's DOM for a different issue.
   */
  protected readonly getRowId = (params: GetRowIdParams<JiraIssueRow>): string => params.data.ref;

  protected onPage(event: PageEvent): void {
    this.page.set(event.pageIndex);
    this.size.set(event.pageSize);
  }

  /**
   * A header click becomes a request.
   *
   * Back to page zero, always: a sort changes which issues are on page four, so staying there shows
   * a page the user did not ask for and cannot reason about. Clearing the sort returns to the
   * server's default order, which is JIRA's own.
   */
  protected onSortChanged(event: SortChangedEvent<JiraIssueRow>): void {
    const sorted = event.api.getColumnState().find((column) => column.sort);

    // `colId` is the field id for a configured column and `key` for the fixed one, which is what
    // the server validates against — an id it did not itself put in `columns` is a 400, on purpose.
    this.sort.set(sorted?.colId ?? 'key');
    this.direction.set(sorted?.sort === 'desc' ? 'desc' : 'asc');
    this.page.set(0);
  }

  /**
   * Choose the columns.
   *
   * The dialog owns its own buffer and its own save (R7); this view only asks for a fresh page once
   * it has written, because the columns and the values are one answer to one question.
   */
  protected openColumns(): void {
    JiraColumnsDialog.open(this.dialog)
      .afterClosed()
      .subscribe((saved) => {
        if (saved) this.issues.reload();
      });
  }

  protected onSearch(value: string): void {
    this.search.set(value);
    // A filter changes the size of the result, so the page a reader was on may no longer exist.
    this.page.set(0);
  }

  protected retry(): void {
    this.issues.reload();
  }
}
