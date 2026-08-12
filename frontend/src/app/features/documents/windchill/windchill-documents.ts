import { Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { RouterLink } from '@angular/router';
import { AgGridAngular } from 'ag-grid-angular';
import type { ColDef, GetRowIdParams, RowClassParams, SortChangedEvent } from 'ag-grid-community';
import { secGridOptions } from '../../../core/grid/sec-grid';
import { EmptyState } from '../../../shared/empty-state/empty-state';
import { matches } from '../../../shared/text/normalize';
import { WindchillDocumentsApiService } from './windchill-documents-api.service';
import { WindchillGroupCell } from './cells/windchill-group-cell';
import { WindchillLinkCell } from './cells/windchill-link-cell';
import type {
  WindchillDocumentRow,
  WindchillGridContext,
  WindchillGridRow,
  WindchillSortField,
} from './windchill-documents.model';

/**
 * Documents → Windchill.
 *
 * ## Everything is in the browser, and that is the design
 *
 * The view asks for every document once and then never talks to the server again until an import
 * happens. Searching, sorting and grouping are all local, which is what makes the search instant —
 * no debounce, no request per keystroke, no page that can be stale relative to the box that filtered
 * it. Production starts at ~1 500 documents and the server caps the response well above that; the
 * day the cap is reached this becomes server-side paging, which is a design change rather than a
 * bigger number, and [truncated] is what will say so.
 *
 * This is the opposite call from the JIRA Issues table, deliberately. That set is tens of thousands
 * and has no grouping; this one is small and cannot be grouped correctly any other way — see below.
 *
 * ## Grouping, without row grouping
 *
 * ag-grid Community has no row grouping (Enterprise only), so the header over the versions of one
 * document is an ordinary row this view synthesises. That has one real consequence: **the grid must
 * not sort**, because a sort that moved rows would separate a header from its versions. Every
 * column carries `comparator: () => 0`, the header still shows the indicator, and [onSortChanged]
 * turns the click into a reorder of the row array — groups move, versions stay inside their group.
 *
 * A document whose number is unique gets no header at all. A header is a finding aid for something
 * that is otherwise indistinguishable from its neighbours, and one row is not that.
 */
@Component({
  selector: 'sec-windchill-documents',
  imports: [
    AgGridAngular,
    EmptyState,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    RouterLink,
  ],
  templateUrl: './windchill-documents.html',
  styleUrl: './windchill-documents.scss',
})
export class WindchillDocuments {
  private readonly api = inject(WindchillDocumentsApiService);

  protected readonly documents = this.api.documents;

  /**
   * The search term, read straight rather than debounced.
   *
   * Nothing is fetched, so there is nothing to debounce: the cost of a keystroke is one pass over
   * an array the browser already holds. Debouncing here would add latency to buy nothing.
   */
  protected readonly search = signal('');

  /** Groups the reader has closed, by key. Closed rather than open, so the default is "show me". */
  private readonly collapsed = signal<ReadonlySet<string>>(new Set());

  protected readonly sort = signal<WindchillSortField | null>(null);
  protected readonly direction = signal<'asc' | 'desc'>('asc');

  private readonly rowsFromServer = computed<readonly WindchillDocumentRow[]>(() =>
    this.documents.hasValue() ? this.documents.value().rows : [],
  );

  protected readonly hasAnswer = computed(() => this.documents.hasValue());

  protected readonly total = computed(() => this.rowsFromServer().length);

  protected readonly truncated = computed(
    () => this.documents.hasValue() && this.documents.value().truncated,
  );

  protected readonly hostConfigured = computed(
    () => this.documents.hasValue() && this.documents.value().hostConfigured,
  );

  /** No documents at all, as opposed to none matching a search — different sentences, different acts. */
  protected readonly isEmpty = computed(() => this.hasAnswer() && this.total() === 0);

  /**
   * How many versions each number has **in the whole set**.
   *
   * Counted before the search, on purpose: whether a document has several versions is a fact about
   * the document, not about what is currently being looked for. Counting after would make a header
   * appear and disappear as a reader typed, which reads as the data changing.
   */
  private readonly versionsByNumber = computed<ReadonlyMap<string, number>>(() => {
    const counts = new Map<string, number>();
    for (const row of this.rowsFromServer()) {
      counts.set(row.number, (counts.get(row.number) ?? 0) + 1);
    }
    return counts;
  });

  /** Documents matching the search, in the server's order. Every displayed field is searched. */
  private readonly matching = computed<readonly WindchillDocumentRow[]>(() => {
    const term = this.search().trim();
    if (!term) return this.rowsFromServer();

    return this.rowsFromServer().filter((row) =>
      matches(
        [row.folderLocation, row.name, row.number, row.version, row.state].join(' '),
        term,
      ),
    );
  });

  protected readonly matchCount = computed(() => this.matching().length);

  protected readonly hasNoMatches = computed(
    () => this.hasAnswer() && this.total() > 0 && this.matchCount() === 0,
  );

  /**
   * The row array the grid draws: headers and documents interleaved, in one flat list.
   *
   * Built in three steps that have to stay in this order. Group first, so a sort can move whole
   * groups rather than rows; sort the groups; then flatten, dropping the versions of a group the
   * reader has closed. Filtering happened before all of it, which is why a search can empty a group
   * without removing its header — the header still names a document that matched.
   */
  protected readonly rows = computed<WindchillGridRow[]>(() => {
    const versions = this.versionsByNumber();
    const collapsed = this.collapsed();

    // Insertion order is the server's order, so a group's versions stay newest-first for free.
    const groups = new Map<string, WindchillDocumentRow[]>();
    for (const row of this.matching()) {
      const bucket = groups.get(row.number);
      if (bucket) bucket.push(row);
      else groups.set(row.number, [row]);
    }

    const ordered = this.orderGroups([...groups.entries()]);
    const out: WindchillGridRow[] = [];

    for (const [number, members] of ordered) {
      const total = versions.get(number) ?? members.length;
      const grouped = total > 1;

      if (grouped) {
        const expanded = !collapsed.has(number);
        const first = members[0];
        out.push({
          kind: 'group',
          key: `group:${number}`,
          number,
          // The header speaks for every version, so it shows what they share. Where they disagree —
          // and a document can be moved between folders between revisions — it shows the newest,
          // which is the one a reader means by "where is this document".
          folderLocation: first.folderLocation,
          name: first.name,
          versions: total,
        });
        if (!expanded) continue;
      }

      for (const document of members) {
        out.push({ kind: 'document', key: `doc:${document.ref}`, document, grouped });
      }
    }

    return out;
  });

  /**
   * What the group cell's disclosure calls, reached through ag-grid's `context`.
   *
   * **Bound on the template as `[context]`, not folded into [gridOptions].** `AgGridAngular`
   * declares `context` as an input of its own, and an input beats the same key inside a
   * `gridOptions` object — so a context passed only that way arrives at the renderer as
   * `undefined`, `this.context?.toggleGroup(...)` is a silent no-op, and the twisty does nothing
   * with no error anywhere. Every spec passed; the browser is where it showed.
   */
  protected readonly gridContext: WindchillGridContext = {
    toggleGroup: (number: string) => this.toggleGroup(number),
    // Reads the signal, so the arrow is redrawn by Angular rather than by ag-grid — see the model
    // file, where the reason is written out at length.
    isExpanded: (number: string) => !this.collapsed().has(number),
  };

  protected readonly gridOptions = {
    ...secGridOptions<WindchillGridRow>(),
    // The band on a group header. It is the same device as a DOORS heading row and, deliberately,
    // the same ground: a whole row that has to be findable among rows that look exactly like it
    // (CLAUDE.md §8, the third exception to "colour is a rail, never a background").
    getRowClass: (params: RowClassParams<WindchillGridRow>) =>
      params.data?.kind === 'group' ? 'sec-grid__row--group' : undefined,
  };

  protected readonly columnDefs = computed<ColDef<WindchillGridRow>[]>(() => [
    {
      colId: 'folderLocation',
      headerName: 'Folder location',
      // The value getter is for sorting and copy-paste; the renderer is what is seen, and it is the
      // one place the disclosure and the indent are drawn.
      valueGetter: (params) =>
        params.data?.kind === 'group'
          ? params.data.folderLocation
          : (params.data?.document.folderLocation ?? ''),
      cellRenderer: WindchillGroupCell,
      flex: 2,
      minWidth: 200,
      comparator: () => 0,
    },
    {
      colId: 'name',
      headerName: 'Name',
      valueGetter: (params) =>
        params.data?.kind === 'group' ? params.data.name : (params.data?.document.name ?? ''),
      flex: 2,
      minWidth: 180,
      comparator: () => 0,
    },
    {
      colId: 'number',
      headerName: 'Number',
      valueGetter: (params) =>
        params.data?.kind === 'group' ? params.data.number : (params.data?.document.number ?? ''),
      width: 220,
      comparator: () => 0,
    },
    {
      colId: 'version',
      headerName: 'Version',
      // Blank on a header, by design: a version is exactly what the rows under it disagree about,
      // so a header showing one would be speaking for rows it contradicts.
      valueGetter: (params) =>
        params.data?.kind === 'document' ? params.data.document.version : '',
      width: 120,
      comparator: () => 0,
    },
    {
      colId: 'state',
      headerName: 'State',
      valueGetter: (params) => (params.data?.kind === 'document' ? params.data.document.state : ''),
      width: 140,
      comparator: () => 0,
    },
    {
      colId: 'openInWindchill',
      // Header-less by design: the column holds one control, the icon says what it does, and a
      // header would name something that is not a property of the document.
      headerName: '',
      cellRenderer: WindchillLinkCell,
      width: 56,
      sortable: false,
      resizable: false,
      // The centring lives in styles/_grid.scss, never here: ag-grid builds cells at runtime, so a
      // component-scoped rule never reaches them (§6).
      cellClass: 'sec-grid__cell sec-grid__cell--control',
    },
  ]);

  /**
   * Row identity, and it is what makes collapsing cheap.
   *
   * Keyed by the row's own key rather than by index: collapsing a group replaces the array, and an
   * index-keyed grid would reuse the first row's DOM for a different document.
   */
  protected readonly getRowId = (params: GetRowIdParams<WindchillGridRow>): string =>
    params.data.key;

  protected onSearch(value: string): void {
    this.search.set(value);
  }

  /**
   * A header click becomes a reorder of the row array, never a grid sort.
   *
   * Clearing the sort returns to the server's own order — number ascending, newest version first —
   * which is the order the grouping was derived in.
   */
  protected onSortChanged(event: SortChangedEvent<WindchillGridRow>): void {
    const sorted = event.api.getColumnState().find((column) => column.sort);

    this.sort.set((sorted?.colId as WindchillSortField | undefined) ?? null);
    this.direction.set(sorted?.sort === 'desc' ? 'desc' : 'asc');
  }

  protected retry(): void {
    this.documents.reload();
  }

  /** Keyed by document number — the row key changes across the toggle, so it cannot be the key. */
  private toggleGroup(number: string): void {
    this.collapsed.update((current) => {
      const next = new Set(current);
      if (!next.delete(number)) next.add(number);
      return next;
    });
  }

  /**
   * Orders the groups, leaving the versions inside each one alone.
   *
   * A group is ordered by the value of its **newest** version for the sorted column. For the three
   * fields a group shares that is the group's own value; for version and state — which it has none
   * of — it is the newest version's, because that is the one a reader means when they sort a
   * document list by state.
   */
  private orderGroups(
    entries: [string, WindchillDocumentRow[]][],
  ): [string, WindchillDocumentRow[]][] {
    const field = this.sort();
    if (!field) return entries;

    const sign = this.direction() === 'desc' ? -1 : 1;
    return [...entries].sort(
      ([, left], [, right]) =>
        sign * left[0][field].localeCompare(right[0][field], undefined, { numeric: true }),
    );
  }
}
