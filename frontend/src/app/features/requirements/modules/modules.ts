import { Component, computed, debounced, effect, inject, signal, viewChild } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatSort, MatSortModule } from '@angular/material/sort';
import { MatTableDataSource, MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { EmptyState } from '../../../shared/empty-state/empty-state';
import { ModuleSettingsDialog } from './module-settings-dialog';
import { normalize } from '../../../shared/text/normalize';
import { ModulesApiService } from './modules-api.service';
import type { ModuleRow, SearchableModuleRow } from './modules.model';

@Component({
  selector: 'sec-modules',
  imports: [
    EmptyState,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    MatSortModule,
    MatTableModule,
    MatTooltipModule,
  ],
  templateUrl: './modules.html',
  styleUrl: './modules.scss',
})
export class Modules {
  protected readonly api = inject(ModulesApiService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly search = signal('');
  private readonly debouncedSearch = debounced(this.search, 200);

  protected readonly displayedColumns = ['name', 'lastModified', 'path', 'level'] as const;
  protected readonly dataSource = new MatTableDataSource<SearchableModuleRow>([]);

  // The table (and its matSort host) only exists once the resource resolves and the @else branch
  // renders — plain, so it stays undefined until then rather than throwing (NG0951) the way
  // viewChild.required did while the loading branch was on screen.
  private readonly sort = viewChild(MatSort);

  protected readonly allRows = computed<SearchableModuleRow[]>(() =>
    (this.api.modules.value()?.rows ?? []).map((row) => ({
      ...row,
      searchText: normalize([row.name, row.lastModified, row.path, row.systemLevel?.label ?? ''].join(' ')),
    })),
  );

  protected readonly filtered = computed(() => {
    const term = normalize(this.debouncedSearch.value() ?? '');
    const rows = this.allRows();
    return term ? rows.filter((row) => row.searchText.includes(term)) : rows;
  });

  constructor() {
    // Sorting is by rendered value, so Last modified sorts as the string DOORS gave us — it is
    // free text, not ISO-8601, and must never be parsed into a Date (requirements-modules.md §3).
    this.dataSource.sortingDataAccessor = (row, columnId) => {
      switch (columnId) {
        case 'name':
          return row.name;
        case 'lastModified':
          return row.lastModified;
        case 'path':
          return row.path;
        case 'level':
          return row.systemLevel?.label ?? '';
        default:
          return '';
      }
    };

    // Filtering stays in the computed rather than MatTableDataSource.filter so it is testable
    // without a table; the data source is only a rendering detail.
    effect(() => {
      this.dataSource.data = this.filtered();
    });

    effect(() => {
      const sort = this.sort();
      if (sort) {
        this.dataSource.sort = sort;
      }
    });
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
