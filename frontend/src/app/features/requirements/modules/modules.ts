import { Component, computed, debounced, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AgGridAngular } from 'ag-grid-angular';
import type { ColDef, GridApi, GridReadyEvent, IRowNode } from 'ag-grid-community';
import { secGridOptions } from '../../../core/grid/sec-grid';
import type { ProblemDetails } from '../../../core/error/problem-details';
import { ConfirmDialog } from '../../../shared/dialog/confirm-dialog';
import { EmptyState } from '../../../shared/empty-state/empty-state';
import { ModuleLevelCell } from './cells/module-level-cell';
import { ModuleNameCell } from './cells/module-name-cell';
import { ModuleSettingsDialog } from './module-settings-dialog';
import { normalize } from '../../../shared/text/normalize';
import { ModulesApiService } from './modules-api.service';
import type {
  ModuleRow,
  ModulesCellContext,
  SearchableModuleRow,
  SystemLevelOption,
} from './modules.model';

function extractErrorDetail(error: unknown): string {
  if (error instanceof HttpErrorResponse && error.error) {
    const problem = error.error as Partial<ProblemDetails>;
    if (problem.detail) {
      return problem.detail;
    }
  }
  return 'Something went wrong saving these system levels. Please try again.';
}

/**
 * Orders two system-level labels, keeping a module with no level at the end.
 *
 * Exported so it can be tested as a function: the alternative is driving a header click through
 * ag-grid's DOM to find out what "descending" does to the unset rows, which tests the grid rather
 * than the rule.
 *
 * `isDescending` is not decoration. ag-grid multiplies a comparator's result by -1 for a
 * descending sort, so the unset side is pre-inverted here — without that, the modules with no
 * level would flip to the top the moment the column is clicked a second time. They are *absent*
 * from the hierarchy rather than at the bottom of it, and the end of the list is where that reads
 * correctly in both directions.
 */
export function compareSystemLevels(a: string, b: string, isDescending: boolean): number {
  if (a === b) {
    return 0;
  }
  if (!a || !b) {
    const unsetLast = !a ? 1 : -1;
    return isDescending ? -unsetLast : unsetLast;
  }
  return a.localeCompare(b);
}

/**
 * Requirements → Modules (docs/features/requirements-modules.md).
 *
 * The table is ag-grid, the same as the review table's — one table system in the application, not
 * two (ADR 0006). This view does not need pinning or column virtualization; it is here so that a
 * reviewer moving between the two views meets one set of column behaviours, and so that a fix to
 * the grid's look lands in both places.
 */
@Component({
  selector: 'sec-modules',
  imports: [
    AgGridAngular,
    EmptyState,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    MatTooltipModule,
  ],
  templateUrl: './modules.html',
  styleUrl: './modules.scss',
  host: {
    // The tab-close half of the exit guard; the in-app half is the router guard. Neither is a
    // global store, and both are scoped to this view's own buffer (R7).
    '(window:beforeunload)': 'onBeforeUnload($event)',
  },
})
export class Modules {
  protected readonly api = inject(ModulesApiService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly search = signal('');
  private readonly debouncedSearch = debounced(this.search, 200);

  protected readonly gridOptions = secGridOptions<SearchableModuleRow>();

  private gridApi: GridApi<SearchableModuleRow> | null = null;

  /**
   * Pending system-level changes, keyed by `ref` and never by row position, so searching or
   * sorting cannot lose an edit. Absence means "not edited"; a `null` value means the user chose
   * *Not set*, which is a change, not the lack of one.
   *
   * This view's own state, dying with it (R7): no store, no staging layer, no global save. Because
   * a table can be navigated away from, unlike a modal, the view guards its own exit — the same
   * amendment the review table's comments needed.
   */
  private readonly levelEdits = signal<ReadonlyMap<string, string | null>>(new Map());

  // What the server confirmed it stored, laid over the loaded rows so a successful save clears
  // the dirty marks without refetching the list.
  private readonly savedLevels = signal<ReadonlyMap<string, SystemLevelOption | null>>(new Map());

  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);

  protected readonly dirtyCount = computed(() => this.levelEdits().size);
  protected readonly canSave = computed(() => this.dirtyCount() > 0 && !this.saving());

  protected readonly cellContext: ModulesCellContext = {
    openSettings: (row) => this.openSettings(row),
    systemLevels: () => this.api.systemLevels.value()?.levels ?? [],
    levelCode: (row) => this.levelCode(row),
    isLevelDirty: (row) => this.levelEdits().has(row.ref),
    editLevel: (row, code) => this.editLevel(row, code),
  };

  protected readonly getRowId = (params: { data: SearchableModuleRow }): string => params.data.ref;

  /**
   * A fixed column set — unlike the review table, this view's shape does not come from the graph.
   *
   * Every one still uses `colId` and a `valueGetter` rather than `field`. None of these names
   * contains a dot today, but the rule is the rule: a `field` here would be a working example for
   * the next column somebody adds, and the failure it invites is a silently blank cell (ADR 0006).
   *
   * **The table opens sorted by system level, L0 first.** That is the order the modules are
   * *read* in — a segment specification is understood before the subsystem specifications that
   * refine it — and it is the order the level chips make legible at a glance. Alphabetical by
   * name was the previous default and put L0 and L4 next to each other by accident of spelling.
   *
   * **One sorted column, not two.** Name as an explicit tie-break works, and makes ag-grid render
   * its multi-sort position badges in the headers — `MODULE 2 ↑`, which reads as a column called
   * "Module 2". Within a level the order is alphabetical anyway: the server returns modules
   * ordered by name and `Array.prototype.sort` is stable, so equal levels keep the order they
   * arrived in. Seen in the browser, not in a stylesheet.
   */
  protected readonly columnDefs: ColDef<SearchableModuleRow>[] = [
    {
      colId: 'name',
      headerName: 'Module',
      width: 320,
      cellRenderer: ModuleNameCell,
      cellClass: 'sec-grid__cell sec-grid__cell--custom',
      valueGetter: (params) => params.data?.name ?? '',
    },
    {
      colId: 'lastModified',
      headerName: 'Last modified',
      width: 180,
      // Sorted as the string DOORS gave us. It is free text, not ISO-8601, and must never be
      // parsed into a Date (requirements-modules.md §3).
      valueGetter: (params) => params.data?.lastModified ?? '',
    },
    {
      colId: 'path',
      headerName: 'Path',
      // The one flex column here, for the same reason Description is in the review table: without
      // one, four fixed columns in a wide grid leave an unclaimed strip that reads as an empty
      // column. Path is the right one to absorb it — it is the longest value and the least
      // damaged by being wider than it needs.
      flex: 1,
      minWidth: 260,
      valueGetter: (params) => params.data?.path ?? '',
      tooltipValueGetter: (params) => params.value as string,
    },
    {
      colId: 'wordExportTitle',
      headerName: 'Word export title',
      width: 220,
      valueGetter: (params) => params.data?.wordExportTitle ?? '',
      tooltipValueGetter: (params) => params.value as string,
    },
    {
      colId: 'wordExportNumber',
      headerName: 'Word export number',
      width: 170,
      valueGetter: (params) => params.data?.wordExportNumber ?? '',
    },
    {
      colId: 'level',
      headerName: 'System level',
      width: 210,
      sort: 'asc',
      cellRenderer: ModuleLevelCell,
      cellClass: 'sec-grid__cell sec-grid__cell--custom',
      // Sorting by the shown wording, not by the stored code: the code never reaches the user, so
      // ordering by it would be ordering by something invisible (R5). The labels are "L0 – …" …
      // "L4 – …", so this sorts the hierarchy in order as a side effect of how they are worded.
      valueGetter: (params) => params.data?.systemLevel?.label ?? '',
      // A module with no level set sorts **last**, not first, and stays last when the sort is
      // reversed. Ascending, "" would otherwise lead the table with the modules that carry the
      // least information — and this column exists to make the hierarchy readable, so the rows
      // that are not in it belong at the end of it either way.
      comparator: (
        a: string,
        b: string,
        _nodeA: IRowNode<SearchableModuleRow>,
        _nodeB: IRowNode<SearchableModuleRow>,
        isDescending: boolean,
      ) => compareSystemLevels(a, b, isDescending),
    },
  ];

  protected readonly allRows = computed<SearchableModuleRow[]>(() =>
    (this.api.modules.value()?.rows ?? []).map((row) => ({
      ...row,
      searchText: normalize(
        [
          row.name,
          row.lastModified,
          row.path,
          row.wordExportTitle,
          row.wordExportNumber,
          row.systemLevel?.label ?? '',
        ].join(' '),
      ),
    })),
  );

  protected readonly filtered = computed(() => {
    const term = normalize(this.debouncedSearch.value() ?? '');
    const rows = this.allRows();
    return term ? rows.filter((row) => row.searchText.includes(term)) : rows;
  });

  // --- System level editing ---------------------------------------------------------------------

  /** True while the user has level changes that have not been written to the graph. */
  hasPendingLevels(): boolean {
    return this.levelEdits().size > 0;
  }

  protected onBeforeUnload(event: BeforeUnloadEvent): void {
    if (this.hasPendingLevels()) {
      event.preventDefault();
    }
  }

  /** What the select shows: the pending edit, else what was last saved, else what was loaded. */
  private levelCode(row: ModuleRow): string | null {
    const edits = this.levelEdits();
    return edits.has(row.ref) ? (edits.get(row.ref) ?? null) : this.storedCode(row);
  }

  // The stored value an edit is measured against — the overlay first, because after a save the
  // loaded row still carries the level the server has already replaced.
  private storedCode(row: ModuleRow): string | null {
    const saved = this.savedLevels();
    return saved.has(row.ref)
      ? (saved.get(row.ref)?.code ?? null)
      : (row.systemLevel?.code ?? null);
  }

  private editLevel(row: ModuleRow, code: string | null): void {
    const edits = new Map(this.levelEdits());
    if (code === this.storedCode(row)) {
      // Chosen back to where it started: not an edit any more, so it must not be saved as one.
      edits.delete(row.ref);
    } else {
      edits.set(row.ref, code);
    }
    this.levelEdits.set(edits);
  }

  protected async saveLevels(): Promise<void> {
    const edits = this.levelEdits();
    if (edits.size === 0) {
      return;
    }

    this.saving.set(true);
    this.saveError.set(null);
    try {
      // One request, one transaction: either every level is written or none is, and on failure
      // the edits stay on screen.
      const response = await this.api.saveSystemLevels({
        levels: [...edits].map(([ref, code]) => ({ ref, code })),
      });

      // The server's answer, not the request, is what the table now shows — applied as an overlay
      // rather than by refetching, so the user keeps their scroll position and their search.
      const saved = new Map(this.savedLevels());
      for (const entry of response.saved) {
        saved.set(entry.ref, entry.systemLevel);
      }
      this.savedLevels.set(saved);
      this.levelEdits.set(new Map());

      // The cells hold their own selection and dirty flag, so they have to be told the buffer was
      // cleared. Only that column, and only the rendered rows — this is not a reload.
      this.gridApi?.refreshCells({ columns: ['level'], force: true });

      this.snackBar.open(
        edits.size === 1 ? 'System level saved' : `${edits.size} system levels saved`,
        'Dismiss',
        { duration: 4000 },
      );
    } catch (error) {
      this.saveError.set(extractErrorDetail(error));
    } finally {
      this.saving.set(false);
    }
  }

  /** Resolves true when it is safe to drop the buffer: nothing pending, or the user said so. */
  async confirmDiscard(): Promise<boolean> {
    if (!this.hasPendingLevels()) {
      return true;
    }
    const count = this.dirtyCount();
    const confirmed = await new Promise<boolean | undefined>((resolve) => {
      ConfirmDialog.open(this.dialog, {
        title: 'Discard unsaved system levels?',
        message:
          count === 1
            ? 'One system level has not been saved yet. Leaving now discards it.'
            : `${count} system levels have not been saved yet. Leaving now discards them.`,
        confirmLabel: 'Discard',
        cancelLabel: 'Keep editing',
      })
        .afterClosed()
        .subscribe(resolve);
    });
    return confirmed === true;
  }

  protected onGridReady(event: GridReadyEvent<SearchableModuleRow>): void {
    this.gridApi = event.api;
  }

  protected retry(): void {
    this.api.modules.reload();
  }

  protected openSettings(row: ModuleRow): void {
    ModuleSettingsDialog.open(this.dialog, { ref: row.ref, name: row.name })
      .afterClosed()
      .subscribe((saved) => {
        if (saved) {
          // Reload rather than patching the row by hand — the server decides what was stored.
          this.api.modules.reload();
          this.snackBar.open('Module settings saved', 'Dismiss', { duration: 4000 });
        }
      });
  }
}
