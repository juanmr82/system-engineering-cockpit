import { Component, computed, debounced, inject, signal } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar } from '@angular/material/snack-bar';
import { AgGridAngular } from 'ag-grid-angular';
import type { ColDef } from 'ag-grid-community';
import { secGridOptions } from '../../../core/grid/sec-grid';
import { AuthStore } from '../../../core/auth/auth-store';
import { Role } from '../../../core/auth/roles';
import { detailOf } from '../../../core/error/problem-details';
import { ConfirmDialog } from '../../../shared/dialog/confirm-dialog';
import { EmptyState } from '../../../shared/empty-state/empty-state';
import { RefusalPanel } from '../../../shared/refusal-panel/refusal-panel';
import { normalize } from '../../../shared/text/normalize';
import { AccessApiService } from '../access-api.service';
import { AccessBadgeService } from '../access-badge.service';
import type { ContainerCategories } from '../access.model';
import { AssignCategoriesDialog } from '../unassigned/assign-categories-dialog';
import { ContainerActionsCell } from './cells/container-actions-cell';

interface SearchableContainerRow extends ContainerCategories {
  readonly searchText: string;
  readonly categoryNames: string;
}

/** What a Containers cell renderer is allowed to ask the view for, the same one-function-per-need
 *  shape `AccessCategoriesCellContext` uses. */
export interface AccessContainersCellContext {
  readonly edit: (row: ContainerCategories) => void;
  readonly clear: (row: ContainerCategories) => void;
}

/**
 * The Access views' Containers screen (spec §10.2 screen 5) — "change the grant of any container
 * on demand," every container of every source with its current category set, editable at any
 * time. The Unassigned queue (screen 3) stays exactly what it always was — the never-yet-graded
 * view, opened after an import — and this is a second, general way in for a container that has
 * already been assigned once and needs re-grading.
 *
 * Backed by `?state=all` on the same read path Unassigned's `?state=unassigned` uses
 * (`AccessCypher.containersWithCategories`), and the same write path (`PUT
 * /access/containers/{ref}/categories`, already an unconditional whole-set replace) plus the same
 * per-source reconcile Unassigned's own assign flow triggers — a grant changed here has to reach
 * the container's members the same way it does from the queue.
 */
@Component({
  selector: 'sec-access-containers',
  imports: [
    AgGridAngular,
    EmptyState,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    RefusalPanel,
  ],
  templateUrl: './access-containers.html',
  styleUrl: './access-containers.scss',
})
export class AccessContainers {
  private readonly authStore = inject(AuthStore);
  protected readonly api = inject(AccessApiService);
  private readonly accessBadge = inject(AccessBadgeService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly checkingAccess = computed(() => this.authStore.isLoading());
  protected readonly canManage = computed(() => this.authStore.hasRole(Role.ACCESS_MANAGER));

  protected readonly search = signal('');
  private readonly debouncedSearch = debounced(this.search, 200);

  protected readonly gridOptions = secGridOptions<SearchableContainerRow>();
  protected readonly getRowId = (params: { data: SearchableContainerRow }): string => params.data.ref;

  protected readonly actionError = signal<string | null>(null);

  protected readonly cellContext: AccessContainersCellContext = {
    edit: (row) => this.openEdit(row),
    clear: (row) => this.confirmClear(row),
  };

  private readonly categoryNameByRef = computed(
    () => new Map(this.api.categories.value()?.categories.map((category) => [category.ref, category.name]) ?? []),
  );

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
      width: 120,
      valueGetter: (params) => params.data?.sourceId ?? '',
    },
    {
      colId: 'categories',
      headerName: 'Categories',
      flex: 1,
      minWidth: 220,
      valueGetter: (params) => params.data?.categoryNames ?? '',
      tooltipValueGetter: (params) => params.value as string,
    },
    {
      colId: 'actions',
      headerName: '',
      width: 100,
      sortable: false,
      resizable: false,
      cellRenderer: ContainerActionsCell,
      cellClass: 'sec-grid__cell sec-grid__cell--custom',
    },
  ];

  protected readonly allRows = computed<SearchableContainerRow[]>(() => {
    const names = this.categoryNameByRef();
    return (this.api.containers.value()?.containers ?? []).map((row) => {
      const categoryNames =
        row.categoryRefs.length === 0
          ? 'Not yet assigned'
          : row.categoryRefs.map((ref) => names.get(ref) ?? ref).join(', ');
      return { ...row, categoryNames, searchText: normalize([row.name, row.sourceId, categoryNames].join(' ')) };
    });
  });

  protected readonly filtered = computed(() => {
    const term = normalize(this.debouncedSearch.value() ?? '');
    const rows = this.allRows();
    return term ? rows.filter((row) => row.searchText.includes(term)) : rows;
  });

  protected retry(): void {
    this.api.containers.reload();
  }

  private openEdit(row: ContainerCategories): void {
    AssignCategoriesDialog.open(this.dialog, { containerCount: 1, initialSelection: row.categoryRefs })
      .afterClosed()
      .subscribe((categoryRefs) => {
        if (categoryRefs && categoryRefs.length > 0) {
          void this.save(row, categoryRefs);
        }
      });
  }

  private confirmClear(row: ContainerCategories): void {
    ConfirmDialog.open(this.dialog, {
      title: 'Clear categories',
      message: `Remove every category from "${row.name}"? It becomes invisible to everyone until it is assigned again.`,
      confirmLabel: 'Clear',
    })
      .afterClosed()
      .subscribe((confirmed) => {
        if (confirmed) {
          void this.save(row, []);
        }
      });
  }

  private async save(row: ContainerCategories, categoryRefs: string[]): Promise<void> {
    this.actionError.set(null);
    try {
      await this.api.saveContainerCategories(row.ref, { categoryRefs });
      await this.api.reconcileSource(row.sourceId);
      this.api.containers.reload();
      this.accessBadge.refresh();
      this.snackBar.open('Categories updated', 'Dismiss', { duration: 4000 });
    } catch (error) {
      this.actionError.set(detailOf(error, 'Something went wrong updating categories. Please try again.'));
    }
  }
}
