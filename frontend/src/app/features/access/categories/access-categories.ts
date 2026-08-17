import { Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
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
import { AccessApiService } from '../access-api.service';
import type { AccessCategory } from '../access.model';
import { CategoryActionsCell } from './cells/category-actions-cell';
import { CategoryDialog } from './category-dialog';

/** What a Categories cell renderer is allowed to ask the view for, passed as the grid's `context`
 *  — the same one-function-per-need shape `ModulesCellContext` uses. */
export interface AccessCategoriesCellContext {
  readonly edit: (row: AccessCategory) => void;
  readonly remove: (row: AccessCategory) => void;
}

/**
 * The Access views' Categories screen (spec §10.2 screen 1). A denied route never redirects
 * (frontend/CLAUDE.md §8) — this component self-checks the role and renders `RefusalPanel` in
 * place, so the URL a hand-typed link landed on stays exactly where it was.
 */
@Component({
  selector: 'sec-access-categories',
  imports: [
    AgGridAngular,
    EmptyState,
    MatButtonModule,
    MatIconModule,
    MatProgressBarModule,
    RefusalPanel,
  ],
  templateUrl: './access-categories.html',
  styleUrl: './access-categories.scss',
})
export class AccessCategories {
  private readonly authStore = inject(AuthStore);
  protected readonly api = inject(AccessApiService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);

  // Waits on isLoading(), not just user(), before deciding to refuse: user() is also null while
  // /auth/me is still in flight, and a legitimate access manager must never see a flash of
  // "forbidden" on first load or a deep link (the same trap named in the phase-6 plan's step 7).
  protected readonly checkingAccess = computed(() => this.authStore.isLoading());
  protected readonly canManage = computed(() => this.authStore.hasRole(Role.ACCESS_MANAGER));

  protected readonly gridOptions = secGridOptions<AccessCategory>();
  protected readonly getRowId = (params: { data: AccessCategory }): string => params.data.ref;

  protected readonly actionError = signal<string | null>(null);

  protected readonly cellContext: AccessCategoriesCellContext = {
    edit: (row) => this.openEdit(row),
    remove: (row) => this.remove(row),
  };

  protected readonly columnDefs: ColDef<AccessCategory>[] = [
    {
      colId: 'name',
      headerName: 'Name',
      pinned: 'left',
      minWidth: 140,
      maxWidth: 320,
      valueGetter: (params) => params.data?.name ?? '',
    },
    {
      colId: 'key',
      headerName: 'Key',
      width: 180,
      cellClass: 'sec-grid__cell sec-access-categories__key-cell',
      valueGetter: (params) => params.data?.key ?? '',
    },
    {
      colId: 'description',
      headerName: 'Description',
      flex: 1,
      minWidth: 220,
      valueGetter: (params) => params.data?.description ?? '',
      tooltipValueGetter: (params) => params.value as string,
    },
    {
      colId: 'everyGroup',
      headerName: 'Any group',
      width: 120,
      // "Yes" or absent, never "No" — an absence reads correctly for a boolean the same way an
      // empty DOORS attribute does (CLAUDE.md §11): this is a property this category either has
      // or does not, not a tri-state the user needs spelled out both ways.
      valueGetter: (params) => (params.data?.everyGroup ? 'Yes' : ''),
    },
    {
      colId: 'objectCount',
      headerName: 'Objects',
      width: 110,
      // Not `type: 'rightAligned'` — see `.sec-grid__header-cell--right`'s own comment: it drops
      // `sec-grid__header-cell` from the header rather than combining with it.
      headerClass: 'sec-grid__header-cell sec-grid__header-cell--right',
      cellClass: 'sec-grid__cell sec-grid__cell--right',
      valueGetter: (params) => params.data?.objectCount ?? 0,
    },
    {
      colId: 'groupCount',
      headerName: 'Groups',
      width: 110,
      headerClass: 'sec-grid__header-cell sec-grid__header-cell--right',
      cellClass: 'sec-grid__cell sec-grid__cell--right',
      valueGetter: (params) => params.data?.groupCount ?? 0,
    },
    {
      colId: 'actions',
      headerName: '',
      width: 100,
      sortable: false,
      resizable: false,
      cellRenderer: CategoryActionsCell,
      cellClass: 'sec-grid__cell sec-grid__cell--custom',
    },
  ];

  protected readonly rows = computed(() => this.api.categories.value()?.categories ?? []);

  protected retry(): void {
    this.api.categories.reload();
  }

  protected openCreate(): void {
    CategoryDialog.open(this.dialog, { category: null })
      .afterClosed()
      .subscribe((saved) => {
        if (saved) {
          this.api.categories.reload();
          this.snackBar.open('Category created', 'Dismiss', { duration: 4000 });
        }
      });
  }

  private openEdit(row: AccessCategory): void {
    CategoryDialog.open(this.dialog, { category: row })
      .afterClosed()
      .subscribe((saved) => {
        if (saved) {
          this.api.categories.reload();
          this.snackBar.open('Category updated', 'Dismiss', { duration: 4000 });
        }
      });
  }

  /**
   * Pre-empts the 409 with the counts the list row already carries (phase-6 plan §6.2), rather
   * than a blind attempt-then-parse-the-error round trip. The delete is still attempted on
   * confirm either way — the 409 that can follow (a category granted or assigned to in the
   * window between this row loading and the click) is a defensive backstop, surfaced as the
   * inline error below the table, not a reason to skip the request.
   */
  private remove(row: AccessCategory): void {
    const inUse = row.objectCount > 0 || row.groupCount > 0;
    const message = inUse
      ? `"${row.name}" is still granted to ${row.groupCount} group(s) and assigned to ` +
        `${row.objectCount} object(s). Deleting it now will fail until those are removed.`
      : `Delete "${row.name}"? This cannot be undone.`;

    ConfirmDialog.open(this.dialog, {
      title: 'Delete category',
      message,
      confirmLabel: inUse ? 'Delete anyway' : 'Delete',
    })
      .afterClosed()
      .subscribe((confirmed) => {
        if (confirmed) {
          void this.confirmedDelete(row);
        }
      });
  }

  private async confirmedDelete(row: AccessCategory): Promise<void> {
    this.actionError.set(null);
    try {
      await this.api.deleteCategory(row.ref);
      this.api.categories.reload();
      this.snackBar.open('Category deleted', 'Dismiss', { duration: 4000 });
    } catch (error) {
      this.actionError.set(detailOf(error, 'Something went wrong deleting this category. Please try again.'));
    }
  }
}
