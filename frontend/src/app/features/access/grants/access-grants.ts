import { Component, computed, inject, signal } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar } from '@angular/material/snack-bar';
import { AgGridAngular } from 'ag-grid-angular';
import type { ColDef, GridApi, GridReadyEvent } from 'ag-grid-community';
import { secGridOptions } from '../../../core/grid/sec-grid';
import { AuthStore } from '../../../core/auth/auth-store';
import { Role } from '../../../core/auth/roles';
import { detailOf } from '../../../core/error/problem-details';
import { ConfirmDialog } from '../../../shared/dialog/confirm-dialog';
import { RefusalPanel } from '../../../shared/refusal-panel/refusal-panel';
import { AccessApiService } from '../access-api.service';
import type { GroupWithGrants } from '../access.model';
import { GrantCell } from './cells/grant-cell';
import { GrantsRowSaveCell } from './cells/grants-row-save-cell';
import { SeesAllCell } from './cells/sees-all-cell';

/** What a Grants cell renderer is allowed to ask the view for. Three renderers share this one
 *  context — the checkbox matrix, the `seesAll` toggle, and the per-row save button — the same
 *  one-function-per-need shape `ModulesCellContext`/`AccessCategoriesCellContext` use. */
export interface AccessGrantsCellContext {
  readonly isGranted: (row: GroupWithGrants, categoryRef: string) => boolean;
  readonly toggleGrant: (row: GroupWithGrants, categoryRef: string) => void;
  readonly isRowDirty: (row: GroupWithGrants) => boolean;
  readonly isSaving: (row: GroupWithGrants) => boolean;
  readonly saveRow: (row: GroupWithGrants) => void;
  readonly requestSeesAllChange: (row: GroupWithGrants, next: boolean) => void;
}

function sameCategories(a: ReadonlySet<string>, b: ReadonlySet<string>): boolean {
  if (a.size !== b.size) {
    return false;
  }
  for (const ref of a) {
    if (!b.has(ref)) {
      return false;
    }
  }
  return true;
}

/**
 * The Access views' Grants screen (spec §10.2 screen 2). Rows are every `:__Group` ever seen,
 * columns are every category, built client-side from `AccessApiService`'s two list resources —
 * there is no server-built matrix (spec §9: "saving is per row").
 *
 * Grant toggles are buffered in this view's own state, per row, and saved with a per-row action
 * button — the same shape `Modules`' pending system-level edits use, one row at a time instead of
 * one batch (R7: this view's own buffer, dying with it). `seesAll` is a deliberately separate,
 * immediate write behind a confirmation (spec §9: "audited loudly") — never batched into the
 * pending-grants buffer.
 */
@Component({
  selector: 'sec-access-grants',
  imports: [AgGridAngular, MatProgressBarModule, RefusalPanel],
  templateUrl: './access-grants.html',
  styleUrl: './access-grants.scss',
})
export class AccessGrants {
  private readonly authStore = inject(AuthStore);
  protected readonly api = inject(AccessApiService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly checkingAccess = computed(() => this.authStore.isLoading());
  protected readonly canManage = computed(() => this.authStore.hasRole(Role.ACCESS_MANAGER));

  protected readonly gridOptions = secGridOptions<GroupWithGrants>();
  protected readonly getRowId = (params: { data: GroupWithGrants }): string => params.data.ref;

  private gridApi: GridApi<GroupWithGrants> | null = null;

  /** Pending grant edits, keyed by group ref and never by row position — the same reasoning
   *  `Modules`' `levelEdits` gives for its own buffer. Present only when it differs from stored. */
  private readonly grantEdits = signal<ReadonlyMap<string, ReadonlySet<string>>>(new Map());
  // What the server confirmed it stored, laid over the loaded rows so a successful per-row save
  // clears that row's dirty mark without refetching the whole matrix.
  private readonly savedGrants = signal<ReadonlyMap<string, string[]>>(new Map());
  private readonly savingRows = signal<ReadonlySet<string>>(new Set());

  protected readonly loading = computed(() => this.api.groups.isLoading() || this.api.categories.isLoading());
  protected readonly loadError = computed(() => this.api.groups.error() ?? this.api.categories.error());
  protected readonly categories = computed(() => this.api.categories.value()?.categories ?? []);
  protected readonly rows = computed(() => this.api.groups.value()?.groups ?? []);

  protected readonly cellContext: AccessGrantsCellContext = {
    isGranted: (row, categoryRef) => this.currentCategoryRefs(row).includes(categoryRef),
    toggleGrant: (row, categoryRef) => this.toggleGrant(row, categoryRef),
    isRowDirty: (row) => this.grantEdits().has(row.ref),
    isSaving: (row) => this.savingRows().has(row.ref),
    saveRow: (row) => void this.saveRow(row),
    requestSeesAllChange: (row, next) => this.requestSeesAllChange(row, next),
  };

  protected readonly columnDefs = computed<ColDef<GroupWithGrants>[]>(() => [
    {
      colId: 'name',
      headerName: 'Group',
      pinned: 'left',
      minWidth: 140,
      maxWidth: 320,
      valueGetter: (params) => params.data?.name ?? '',
    },
    {
      colId: 'key',
      headerName: 'Key',
      width: 200,
      cellClass: 'sec-grid__cell sec-access-grants__key-cell',
      valueGetter: (params) => params.data?.key ?? '',
    },
    ...this.categories().map(
      (category): ColDef<GroupWithGrants> => ({
        colId: category.ref,
        headerName: category.name,
        width: 110,
        sortable: false,
        cellRenderer: GrantCell,
        cellClass: 'sec-grid__cell sec-grid__cell--custom sec-access-grants__grant-cell',
      }),
    ),
    {
      colId: 'seesAll',
      headerName: 'Sees everything',
      width: 150,
      sortable: false,
      cellRenderer: SeesAllCell,
      cellClass: 'sec-grid__cell sec-grid__cell--custom sec-access-grants__sees-all-cell',
    },
    {
      colId: 'actions',
      headerName: '',
      width: 90,
      sortable: false,
      resizable: false,
      pinned: 'right',
      cellRenderer: GrantsRowSaveCell,
      cellClass: 'sec-grid__cell sec-grid__cell--custom',
    },
  ]);

  protected onGridReady(event: GridReadyEvent<GroupWithGrants>): void {
    this.gridApi = event.api;
  }

  protected retry(): void {
    this.api.groups.reload();
    this.api.categories.reload();
  }

  private storedCategoryRefs(row: GroupWithGrants): string[] {
    const saved = this.savedGrants();
    return saved.has(row.ref) ? (saved.get(row.ref) ?? []) : row.categoryRefs;
  }

  private currentCategoryRefs(row: GroupWithGrants): string[] {
    const edits = this.grantEdits();
    const edited = edits.get(row.ref);
    return edited ? Array.from(edited) : this.storedCategoryRefs(row);
  }

  private toggleGrant(row: GroupWithGrants, categoryRef: string): void {
    const current = new Set(this.currentCategoryRefs(row));
    if (current.has(categoryRef)) {
      current.delete(categoryRef);
    } else {
      current.add(categoryRef);
    }

    const stored = new Set(this.storedCategoryRefs(row));
    const edits = new Map(this.grantEdits());
    if (sameCategories(current, stored)) {
      edits.delete(row.ref);
    } else {
      edits.set(row.ref, current);
    }
    this.grantEdits.set(edits);
    this.refreshRow(row.ref);
  }

  private async saveRow(row: GroupWithGrants): Promise<void> {
    const categoryRefs = this.currentCategoryRefs(row);
    this.savingRows.set(new Set(this.savingRows()).add(row.ref));
    this.refreshRow(row.ref);

    try {
      const saved = await this.api.saveGrants(row.ref, { categoryRefs });

      const savedMap = new Map(this.savedGrants());
      savedMap.set(row.ref, saved.categoryRefs);
      this.savedGrants.set(savedMap);

      const edits = new Map(this.grantEdits());
      edits.delete(row.ref);
      this.grantEdits.set(edits);

      this.snackBar.open('Grants saved', 'Dismiss', { duration: 4000 });
    } catch (error) {
      this.snackBar.open(
        detailOf(error, 'Something went wrong saving these grants. Please try again.'),
        'Dismiss',
        { duration: 6000 },
      );
    } finally {
      const saving = new Set(this.savingRows());
      saving.delete(row.ref);
      this.savingRows.set(saving);
      this.refreshRow(row.ref);
    }
  }

  private requestSeesAllChange(row: GroupWithGrants, next: boolean): void {
    const message = next
      ? `Grant "${row.name}" visibility into everything, bypassing every category grant?`
      : `Remove "${row.name}"'s blanket visibility? Its members will then see only what its own grants cover.`;

    ConfirmDialog.open(this.dialog, {
      title: next ? 'Grant "Sees everything"' : 'Remove "Sees everything"',
      message,
      confirmLabel: next ? 'Grant' : 'Remove',
    })
      .afterClosed()
      .subscribe((confirmed) => {
        if (confirmed) {
          void this.applySeesAll(row, next);
        }
      });
  }

  private async applySeesAll(row: GroupWithGrants, next: boolean): Promise<void> {
    try {
      await this.api.setSeesAll(row.ref, { seesAll: next });
      // Rare and audited (spec §9), unlike a grant save — a reload rather than an overlay is
      // fine here, and it is what keeps this write's truth entirely server-confirmed.
      this.api.groups.reload();
    } catch (error) {
      this.snackBar.open(
        detailOf(error, 'Something went wrong updating this group. Please try again.'),
        'Dismiss',
        { duration: 6000 },
      );
      this.refreshRow(row.ref);
    }
  }

  private refreshRow(ref: string): void {
    const node = this.gridApi?.getRowNode(ref);
    if (node) {
      this.gridApi?.refreshCells({ rowNodes: [node], force: true });
    }
  }
}
