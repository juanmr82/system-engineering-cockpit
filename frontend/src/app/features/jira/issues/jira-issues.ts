import { Component, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AgGridAngular } from 'ag-grid-angular';
import type { ColDef } from 'ag-grid-community';
import { secGridOptions } from '../../../core/grid/sec-grid';
import type { ProblemDetails } from '../../../core/error/problem-details';
import { EmptyState } from '../../../shared/empty-state/empty-state';
import { ImportReportDialog } from '../import-report-dialog/import-report-dialog';
import { JiraApiService, PAGE_SIZE } from '../jira-api.service';
import type { JiraColumn, JiraIssueRow } from '../jira.model';

/**
 * Renders one cell value.
 *
 * Exported and pure so the rules can be tested as a function. jsdom has no layout and a grid cell
 * is ag-grid's own DOM, so driving this through a rendered table would test the grid rather than
 * the formatting — the same reason `compareSystemLevels` is exported from the Modules view.
 *
 * A list is joined rather than shown as chips: `components.name` is `["Avionics", "Power"]`, and a
 * chip per element in an auto-height cell makes a two-element row twice the height of its
 * neighbours for no information gained.
 */
export function formatCellValue(value: unknown): string {
  if (value === null || value === undefined) {
    // Not "unknown" and not a dash: a JIRA field that a given issue does not carry is an absence,
    // and §6.4 requires it to render blank rather than as an error.
    return '';
  }
  if (Array.isArray(value)) {
    return value.map((item) => formatCellValue(item)).join(', ');
  }
  if (typeof value === 'boolean') {
    return value ? 'Yes' : 'No';
  }
  return String(value);
}

function extractErrorDetail(error: unknown, fallback: string): string {
  if (error instanceof HttpErrorResponse && error.error) {
    const problem = error.error as Partial<ProblemDetails>;
    if (problem.detail) {
      return problem.detail;
    }
  }
  return fallback;
}

/**
 * JIRA → Issues (`docs/jira-issues-dynamic-view-design.md`).
 *
 * The table is ag-grid, like every other table in the application (ADR 0006), and its columns are
 * not compiled in: the server resolves them from the admin's selection and the JIRA field
 * catalogue on every read, so this component never knows a field's name and never maps one to a
 * label of its own (R5).
 *
 * **Never `field`, always `colId` + `valueGetter`.** A flattened JIRA path carries dots —
 * `status.name` — and ag-grid reads a dot in `field` as a property path, so `field: 'status.name'`
 * would look for `row.status.name`, find nothing, and render blank with no error at all. That is
 * the identical trap DOORS attribute names set, arriving through a different door (CLAUDE.md §6).
 */
@Component({
  selector: 'sec-jira-issues',
  imports: [
    AgGridAngular,
    EmptyState,
    MatButtonModule,
    MatIconModule,
    MatProgressBarModule,
    MatTooltipModule,
    RouterLink,
  ],
  templateUrl: './jira-issues.html',
  styleUrl: './jira-issues.scss',
})
export class JiraIssues {
  protected readonly api = inject(JiraApiService);
  private readonly dialog = inject(MatDialog);

  protected readonly gridOptions = secGridOptions<JiraIssueRow>();
  protected readonly importing = signal(false);
  protected readonly actionError = signal<string | null>(null);

  protected readonly configured = computed(
    () => this.api.connection.hasValue() && this.api.connection.value().configured,
  );

  protected readonly issues = computed(() =>
    // hasValue() guards every read: reading a resource in an error state throws, and an unguarded
    // read inside a computed the template consumes tears down the whole view.
    this.api.issues.hasValue() ? this.api.issues.value() : null,
  );

  protected readonly rows = computed<JiraIssueRow[]>(() => this.issues()?.rows ?? []);
  protected readonly total = computed(() => this.issues()?.total ?? 0);
  protected readonly offset = computed(() => this.issues()?.offset ?? 0);

  protected readonly rangeLabel = computed(() => {
    const total = this.total();
    if (total === 0) {
      return 'No issues';
    }
    const from = this.offset() + 1;
    const to = Math.min(this.offset() + this.rows().length, total);
    return `${from}–${to} of ${total}`;
  });

  protected readonly canGoBack = computed(() => this.offset() > 0);
  protected readonly canGoForward = computed(
    () => this.offset() + this.rows().length < this.total(),
  );

  protected readonly columnDefs = computed<ColDef<JiraIssueRow>[]>(() =>
    (this.issues()?.columns ?? []).map((column) => this.toColumnDef(column)),
  );

  private toColumnDef(column: JiraColumn): ColDef<JiraIssueRow> {
    return {
      colId: column.path,
      headerName: column.label,
      valueGetter: (params) => this.readValue(params.data, column.path),
      // The key is what a reader scans down and copies out of; it is the row's identity, so it is
      // pinned and sized to its content. autoHeight has to be off for a column to be sized at all
      // — with it on, a cell's width stops being a function of its content and ag-grid says
      // nothing about it (CLAUDE.md §6).
      ...(column.path === KEY_PATH
        ? { pinned: 'left' as const, autoHeight: false, wrapText: false, width: 140 }
        : {}),
    };
  }

  private readValue(row: JiraIssueRow | undefined, path: string): string {
    if (!row) {
      return '';
    }
    // The two fixed columns come off the row itself rather than out of the value bag: the server
    // always sends them, and they are what identifies the row when no field has been selected yet.
    if (path === KEY_PATH) {
      return row.key;
    }
    if (path === ISSUE_TYPE_PATH) {
      return row.issueType;
    }
    return formatCellValue(row.values[path]);
  }

  protected readonly getRowId = (params: { data: JiraIssueRow }) => params.data.ref;

  protected retry(): void {
    this.api.issues.reload();
  }

  protected previousPage(): void {
    this.api.pageOffset.update((offset) => Math.max(0, offset - PAGE_SIZE));
  }

  protected nextPage(): void {
    this.api.pageOffset.update((offset) => offset + PAGE_SIZE);
  }

  /**
   * The button the whole flow hangs off: the click reaches the backend, the backend reads JIRA and
   * writes the graph, and the report comes back as the response.
   *
   * The table is reloaded before the dialog opens rather than after it closes, so the numbers the
   * report quotes and the rows behind it are the same run's.
   */
  protected async runImport(): Promise<void> {
    if (this.importing()) {
      return;
    }
    this.importing.set(true);
    this.actionError.set(null);
    try {
      const report = await this.api.runImport();
      this.api.pageOffset.set(0);
      this.api.issues.reload();
      ImportReportDialog.open(this.dialog, report);
    } catch (error) {
      this.actionError.set(
        extractErrorDetail(error, 'The import could not be started. Please try again.'),
      );
    } finally {
      this.importing.set(false);
    }
  }
}

/** The two fixed columns, matching JiraFieldId.fixedColumns on the server. */
const KEY_PATH = 'key';
const ISSUE_TYPE_PATH = 'issuetype.name';
