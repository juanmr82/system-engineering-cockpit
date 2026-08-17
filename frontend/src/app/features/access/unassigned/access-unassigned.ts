import { Component, computed, debounced, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar } from '@angular/material/snack-bar';
import { AgGridAngular } from 'ag-grid-angular';
import type { ColDef, GridApi, GridReadyEvent, RowSelectionOptions } from 'ag-grid-community';
import { secGridOptions } from '../../../core/grid/sec-grid';
import { AuthStore } from '../../../core/auth/auth-store';
import { Role } from '../../../core/auth/roles';
import { detailOf } from '../../../core/error/problem-details';
import { EmptyState } from '../../../shared/empty-state/empty-state';
import { RefusalPanel } from '../../../shared/refusal-panel/refusal-panel';
import { normalize } from '../../../shared/text/normalize';
import { sourceLabel } from '../../../shared/text/source-label';
import { AccessApiService } from '../access-api.service';
import { AccessBadgeService } from '../access-badge.service';
import type { UnassignedContainer } from '../access.model';
import { AssignCategoriesDialog } from './assign-categories-dialog';

interface SearchableContainerRow extends UnassignedContainer {
  readonly searchText: string;
}

const ROW_SELECTION: RowSelectionOptions<SearchableContainerRow> = {
  mode: 'multiRow',
  checkboxes: true,
  headerCheckbox: true,
  enableClickSelection: false,
};

/**
 * The Access views' Unassigned screen (spec §10.2 screen 3) — every container with no direct
 * category, and how many of its members carry none at all. The one screen whose backend read
 * *and* write are deliberately exempt from `AccessCypher.visible()` (§16.2a): an access manager
 * who cannot yet grant themselves a category could otherwise never find the container to grant
 * one to.
 *
 * Assigning is bulk by design — select any number of rows, pick categories once in
 * `AssignCategoriesDialog`, and one `PUT` per container follows (R7's per-container transaction
 * unit, not one request for the whole selection). Confirmed with the user during planning: a
 * successful assign auto-triggers `POST /access/reconcile?scope=source&source=<touched source>`
 * for every distinct source among the containers just touched, so this screen's own acceptance
 * flow is one gesture rather than "assign, then remember to go reconcile."
 */
@Component({
  selector: 'sec-access-unassigned',
  imports: [
    AgGridAngular,
    EmptyState,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    RefusalPanel,
  ],
  templateUrl: './access-unassigned.html',
  styleUrl: './access-unassigned.scss',
})
export class AccessUnassigned {
  private readonly authStore = inject(AuthStore);
  protected readonly api = inject(AccessApiService);
  private readonly accessBadge = inject(AccessBadgeService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly checkingAccess = computed(() => this.authStore.isLoading());
  protected readonly canManage = computed(() => this.authStore.hasRole(Role.ACCESS_MANAGER));

  protected readonly search = signal('');
  private readonly debouncedSearch = debounced(this.search, 200);

  protected readonly gridOptions = { ...secGridOptions<SearchableContainerRow>(), rowSelection: ROW_SELECTION };
  protected readonly getRowId = (params: { data: SearchableContainerRow }): string => params.data.ref;

  private gridApi: GridApi<SearchableContainerRow> | null = null;
  protected readonly selectedCount = signal(0);
  protected readonly assigning = signal(false);

  protected readonly columnDefs: ColDef<SearchableContainerRow>[] = [
    {
      colId: 'name',
      headerName: 'Container',
      pinned: 'left',
      minWidth: 160,
      flex: 1,
      valueGetter: (params) => params.data?.name ?? '',
    },
    {
      colId: 'sourceId',
      headerName: 'Source',
      width: 140,
      valueGetter: (params) => (params.data ? sourceLabel(params.data.sourceId) : ''),
    },
    {
      colId: 'invisibleItemCount',
      headerName: 'Invisible items',
      width: 150,
      // Not `type: 'rightAligned'` — see `.sec-grid__header-cell--right`'s own comment: it drops
      // `sec-grid__header-cell` from the header rather than combining with it.
      headerClass: 'sec-grid__header-cell sec-grid__header-cell--right',
      cellClass: 'sec-grid__cell sec-grid__cell--right',
      valueGetter: (params) => params.data?.invisibleItemCount ?? 0,
    },
  ];

  protected readonly allRows = computed<SearchableContainerRow[]>(() =>
    (this.api.unassignedContainers.value()?.containers ?? []).map((row) => ({
      ...row,
      searchText: normalize([row.name, row.sourceId].join(' ')),
    })),
  );

  protected readonly filtered = computed(() => {
    const term = normalize(this.debouncedSearch.value() ?? '');
    const rows = this.allRows();
    return term ? rows.filter((row) => row.searchText.includes(term)) : rows;
  });

  protected onGridReady(event: GridReadyEvent<SearchableContainerRow>): void {
    this.gridApi = event.api;
  }

  protected onSelectionChanged(): void {
    this.selectedCount.set(this.gridApi?.getSelectedRows().length ?? 0);
  }

  protected retry(): void {
    this.api.unassignedContainers.reload();
  }

  protected openAssign(): void {
    const selected = this.gridApi?.getSelectedRows() ?? [];
    if (selected.length === 0) {
      return;
    }

    AssignCategoriesDialog.open(this.dialog, { containerCount: selected.length })
      .afterClosed()
      .subscribe((categoryRefs) => {
        if (categoryRefs && categoryRefs.length > 0) {
          void this.assign(selected, categoryRefs);
        }
      });
  }

  private async assign(rows: SearchableContainerRow[], categoryRefs: string[]): Promise<void> {
    this.assigning.set(true);
    try {
      // One PUT per container — the escape hatch's own transaction unit (R7), never one request
      // for the whole selection.
      await Promise.all(rows.map((row) => this.api.saveContainerCategories(row.ref, { categoryRefs })));

      const sources = Array.from(new Set(rows.map((row) => row.sourceId)));
      await Promise.all(sources.map((sourceId) => this.api.reconcileSource(sourceId)));

      this.api.unassignedContainers.reload();
      // The Containers screen (spec §10.2 screen 5) reads the same underlying state through its
      // own, separately-cached resource — the same staleness this screen's own write leaves
      // behind there that screen 5's write leaves here, found live while driving both on screen.
      this.api.containers.reload();
      this.accessBadge.refresh();
      this.gridApi?.deselectAll();

      this.snackBar.open(
        rows.length === 1 ? 'Assigned categories to 1 container' : `Assigned categories to ${rows.length} containers`,
        'Dismiss',
        { duration: 4000 },
      );
    } catch (error) {
      this.snackBar.open(
        detailOf(error, 'Something went wrong assigning categories. Please try again.'),
        'Dismiss',
        { duration: 6000 },
      );
    } finally {
      this.assigning.set(false);
    }
  }
}
