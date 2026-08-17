import { Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AgGridAngular } from 'ag-grid-angular';
import type { ColDef, GridApi, GridReadyEvent } from 'ag-grid-community';
import { secGridOptions } from '../../../core/grid/sec-grid';
import { AuthStore } from '../../../core/auth/auth-store';
import { Role } from '../../../core/auth/roles';
import { detailOf } from '../../../core/error/problem-details';
import { RefusalPanel } from '../../../shared/refusal-panel/refusal-panel';
import { AccessApiService } from '../access-api.service';
import type { AccessCategory, AccessDefault } from '../access.model';
import { DefaultCategoryCell } from './cells/default-category-cell';

export type AccessDefaultRow = AccessDefault;

/** What the category-select cell renderer is allowed to ask the view for — the same
 *  one-function-per-need shape `ModulesCellContext` uses. */
export interface AccessDefaultsCellContext {
  readonly categories: () => readonly AccessCategory[];
  readonly categoryRef: (row: AccessDefaultRow) => string | null;
  readonly isDirty: (row: AccessDefaultRow) => boolean;
  readonly editCategory: (row: AccessDefaultRow, ref: string | null) => void;
}

/** No single `ref` exists for a `(sourceId, containerLabel)` pair — this is the one key both
 *  ag-grid's `getRowId` and the pending-edits buffer address a row by. */
function rowId(row: AccessDefaultRow): string {
  return `${row.sourceId}:${row.containerLabel}`;
}

/**
 * The Access views' Import defaults screen (spec §10.2 screen 4) — "new ‹source› ‹containers›
 * are visible to …", per `(sourceId, containerLabel)`.
 *
 * Modelled on `Modules`' own system-level batch save rather than a fresh Signal Forms dialog:
 * this is the identical shape — a short, fixed row set, one editable select cell each, one
 * whole-view Save — and there is no established pattern in this codebase yet for binding Signal
 * Forms across an ag-grid cell-renderer boundary. `ModuleLevelCell`'s "buffer + refreshCells"
 * approach is the closer, already-proven analog.
 */
@Component({
  selector: 'sec-access-defaults',
  imports: [AgGridAngular, MatButtonModule, MatIconModule, MatProgressBarModule, MatTooltipModule, RefusalPanel],
  templateUrl: './access-defaults.html',
  styleUrl: './access-defaults.scss',
})
export class AccessDefaults {
  private readonly authStore = inject(AuthStore);
  protected readonly api = inject(AccessApiService);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly checkingAccess = computed(() => this.authStore.isLoading());
  protected readonly canManage = computed(() => this.authStore.hasRole(Role.ACCESS_MANAGER));

  protected readonly gridOptions = secGridOptions<AccessDefaultRow>();
  protected readonly getRowId = (params: { data: AccessDefaultRow }): string => rowId(params.data);

  private gridApi: GridApi<AccessDefaultRow> | null = null;

  /** Pending edits, keyed by `rowId` and never by row position — the same reasoning `Modules`'
   *  own `levelEdits` buffer gives for its own. Present only when it differs from stored. */
  private readonly defaultEdits = signal<ReadonlyMap<string, string | null>>(new Map());
  // What the server confirmed it stored, laid over the loaded rows so a successful save clears
  // the dirty marks without refetching.
  private readonly savedDefaults = signal<ReadonlyMap<string, string | null>>(new Map());

  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);
  protected readonly dirtyCount = computed(() => this.defaultEdits().size);
  protected readonly canSave = computed(() => this.dirtyCount() > 0 && !this.saving());

  protected readonly rows = computed(() => this.api.defaults.value()?.defaults ?? []);

  protected readonly cellContext: AccessDefaultsCellContext = {
    categories: () => this.api.categories.value()?.categories ?? [],
    categoryRef: (row) => this.categoryRef(row),
    isDirty: (row) => this.defaultEdits().has(rowId(row)),
    editCategory: (row, ref) => this.editCategory(row, ref),
  };

  protected readonly columnDefs: ColDef<AccessDefaultRow>[] = [
    {
      colId: 'sourceId',
      headerName: 'Source',
      pinned: 'left',
      width: 140,
      valueGetter: (params) => params.data?.sourceId ?? '',
    },
    {
      colId: 'containerLabel',
      headerName: 'Container type',
      flex: 1,
      minWidth: 200,
      valueGetter: (params) => params.data?.containerLabel ?? '',
    },
    {
      colId: 'categoryRef',
      headerName: 'Default category',
      width: 220,
      sortable: false,
      cellRenderer: DefaultCategoryCell,
      cellClass: 'sec-grid__cell sec-grid__cell--custom',
    },
  ];

  protected onGridReady(event: GridReadyEvent<AccessDefaultRow>): void {
    this.gridApi = event.api;
  }

  protected retry(): void {
    this.api.defaults.reload();
  }

  private storedCategoryRef(row: AccessDefaultRow): string | null {
    const saved = this.savedDefaults();
    const key = rowId(row);
    return saved.has(key) ? (saved.get(key) ?? null) : row.categoryRef;
  }

  private categoryRef(row: AccessDefaultRow): string | null {
    const edits = this.defaultEdits();
    const key = rowId(row);
    return edits.has(key) ? (edits.get(key) ?? null) : this.storedCategoryRef(row);
  }

  private editCategory(row: AccessDefaultRow, ref: string | null): void {
    const key = rowId(row);
    const edits = new Map(this.defaultEdits());
    if (ref === this.storedCategoryRef(row)) {
      edits.delete(key);
    } else {
      edits.set(key, ref);
    }
    this.defaultEdits.set(edits);
  }

  protected async save(): Promise<void> {
    const edits = this.defaultEdits();
    if (edits.size === 0) {
      return;
    }

    this.saving.set(true);
    this.saveError.set(null);
    try {
      // The whole set, one transaction (R7) — every row goes back, not just the edited ones.
      const response = await this.api.saveDefaults({
        defaults: this.rows().map((row) => ({
          sourceId: row.sourceId,
          containerLabel: row.containerLabel,
          categoryRef: this.categoryRef(row),
        })),
      });

      const saved = new Map(this.savedDefaults());
      for (const entry of response.defaults) {
        saved.set(rowId(entry), entry.categoryRef);
      }
      this.savedDefaults.set(saved);
      this.defaultEdits.set(new Map());

      this.gridApi?.refreshCells({ columns: ['categoryRef'], force: true });

      this.snackBar.open(
        edits.size === 1 ? 'Import default saved' : `${edits.size} import defaults saved`,
        'Dismiss',
        { duration: 4000 },
      );
    } catch (error) {
      this.saveError.set(detailOf(error, 'Something went wrong saving these defaults. Please try again.'));
    } finally {
      this.saving.set(false);
    }
  }
}
