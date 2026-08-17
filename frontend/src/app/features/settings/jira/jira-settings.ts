import { Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, RouterLink } from '@angular/router';
import { ImportRunStore } from '../../../core/import/import-run-store';
import { COUNTER_LABELS, type ImportRun, type ImportSchedule } from '../../../core/import/import.model';
import { JiraColumnsApiService } from '../../jira/columns/jira-columns-api.service';
import { JiraColumnsDialog } from '../../jira/columns/jira-columns-dialog';
import { JiraSettingsApiService } from './jira-settings-api.service';

/** The importer this page starts. The framework is generic; this page is not (spec §11). */
const JIRA_IMPORTER = 'jira';

/**
 * JIRA settings (`/settings/jira`, spec §13.5, ADR 0018).
 *
 * Three sections, in the order a person cares about them: does JIRA answer (and what it can see),
 * which columns the table shows, and then — only then — the import itself. There is no project
 * picker any more: RBAC is the gate (R8), so the importer brings in everything the configured token
 * can see and access categories decide who may read it.
 *
 * ## The token is not here, in any form
 *
 * It lives in `application.yaml` and never reaches the browser (spec §3, R5). *Test connection* is
 * how this page reports that the credential works, and the resolved JIRA user is the whole of what
 * it says about it.
 */
@Component({
  selector: 'sec-jira-settings',
  imports: [MatButtonModule, MatProgressBarModule, MatTooltipModule, RouterLink],
  templateUrl: './jira-settings.html',
  styleUrl: './jira-settings.scss',
})
export class JiraSettings {
  private readonly api = inject(JiraSettingsApiService);
  private readonly columnsApi = inject(JiraColumnsApiService);
  private readonly runs = inject(ImportRunStore);
  private readonly dialog = inject(MatDialog);
  private readonly router = inject(Router);

  protected readonly health = this.api.health;
  protected readonly projects = this.api.projects;
  protected readonly columns = this.columnsApi.columns;

  protected readonly error = signal<string | null>(null);
  protected readonly starting = signal(false);
  protected readonly lastRun = signal<ImportRun | null>(null);
  protected readonly scheduleInfo = signal<ImportSchedule | null>(null);

  /** The run this page started, or the one already going when it loaded. */
  protected readonly activeRun = computed(() => {
    const run = this.runs.run();
    return run?.status === 'RUNNING' ? run : null;
  });

  protected readonly columnSummary = computed(() => {
    const columns = this.columns.hasValue() ? this.columns.value() : [];
    return columns.map((column) => column.name);
  });

  protected readonly projectNames = computed(() =>
    (this.projects.hasValue() ? this.projects.value() : []).map((project) => project.key).join(', '),
  );

  protected readonly canImport = computed(() => this.activeRun() === null && !this.starting());

  constructor() {
    void this.loadRuns();
  }

  protected testConnection(): void {
    this.health.reload();
  }

  /** The picker owns its own buffer and its own save; this page only reloads what it shows. */
  protected openColumns(): void {
    JiraColumnsDialog.open(this.dialog);
  }

  protected async runImport(): Promise<void> {
    this.starting.set(true);
    this.error.set(null);

    try {
      await this.runs.start(JIRA_IMPORTER);
    } catch {
      this.error.set('Could not start the import. Check the connection above and try again.');
    } finally {
      this.starting.set(false);
    }
  }

  protected openConsole(): void {
    void this.router.navigate(['/settings/importers']);
  }

  protected counterLabel(key: string): string {
    return COUNTER_LABELS[key] ?? key;
  }

  protected counterEntries(run: ImportRun): { key: string; label: string; value: number }[] {
    return Object.entries(run.counters).map(([key, value]) => ({
      key,
      label: this.counterLabel(key),
      value,
    }));
  }

  /**
   * The last run, the live one if there is one, and the next scheduled one if there is one.
   *
   * Read once on load rather than polled: a run started from this page is watched over SSE, and a
   * run started elsewhere is somebody else's business until this page is opened again. The schedule
   * is likewise a snapshot — "next run at" moving slightly stale while the page sits open costs
   * nothing a reload does not already fix.
   */
  private async loadRuns(): Promise<void> {
    try {
      const [history, importers, schedule] = await Promise.all([
        this.runs.history(JIRA_IMPORTER, 1),
        this.runs.importers(),
        this.runs.schedule(JIRA_IMPORTER),
      ]);

      this.lastRun.set(history[0] ?? null);
      this.scheduleInfo.set(schedule);

      const active = importers.find((importer) => importer.importerId === JIRA_IMPORTER)?.activeRunId;
      if (active) await this.runs.watch(active);
    } catch {
      // The page is still usable without a history: every other section reads its own resource.
      this.lastRun.set(null);
    }
  }
}
