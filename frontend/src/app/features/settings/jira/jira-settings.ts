import { Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, RouterLink } from '@angular/router';
import { ImportRunStore } from '../../../core/import/import-run-store';
import { COUNTER_LABELS, type ImportRun } from '../../../core/import/import.model';
import { JiraColumnsApiService } from '../../jira/columns/jira-columns-api.service';
import { JiraColumnsDialog } from '../../jira/columns/jira-columns-dialog';
import { JiraSettingsApiService } from './jira-settings-api.service';

/** The importer this page starts. The framework is generic; this page is not (spec §11). */
const JIRA_IMPORTER = 'jira';

/**
 * JIRA settings (`/settings/jira`, spec §13.5).
 *
 * Four sections, and they are in the order a person sets a deployment up: does JIRA answer, which
 * projects are in the query, which columns the table shows, and then — only then — run an import.
 *
 * ## Saving is per gesture, and there is no buffer (R7)
 *
 * Adding or removing a project key is one gesture, one request, one transaction. There is no Save
 * button for the chip list and no dirty state, which is what keeps this page free of an exit guard:
 * a user who navigates away has written exactly what they saw happen. The column picker is a
 * dialog and owns its own buffer, which is the other half of the same rule.
 *
 * ## The token is not here, in any form
 *
 * It lives in `application.yaml` and never reaches the browser (spec §3, R5). *Test connection* is
 * how this page reports that the credential works, and the resolved JIRA user is the whole of what
 * it says about it.
 */
@Component({
  selector: 'sec-jira-settings',
  imports: [
    MatButtonModule,
    MatChipsModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    MatSelectModule,
    MatTooltipModule,
    RouterLink,
  ],
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
  protected readonly settings = this.api.settings;
  protected readonly projects = this.api.projects;
  protected readonly columns = this.columnsApi.columns;

  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  /**
   * The key just removed, named in the warning under the chips.
   *
   * Kept until the next change rather than timed out: the consequence it describes — the issues
   * leaving on the next import — has not happened yet, so the sentence is still true.
   */
  protected readonly removed = signal<string | null>(null);

  protected readonly starting = signal(false);
  protected readonly lastRun = signal<ImportRun | null>(null);

  /** The run this page started, or the one already going when it loaded. */
  protected readonly activeRun = computed(() => {
    const run = this.runs.run();
    return run?.status === 'RUNNING' ? run : null;
  });

  protected readonly projectKeys = computed<readonly string[]>(() =>
    this.settings.hasValue() ? this.settings.value().projectKeys : [],
  );

  protected readonly jql = computed(() => (this.settings.hasValue() ? this.settings.value().jql : null));

  /** Projects this JIRA has that are not already configured — the only ones worth offering. */
  protected readonly addable = computed(() => {
    const configured = new Set(this.projectKeys());
    return (this.projects.hasValue() ? this.projects.value() : []).filter(
      (project) => !configured.has(project.key),
    );
  });

  protected readonly columnSummary = computed(() => {
    const columns = this.columns.hasValue() ? this.columns.value() : [];
    return columns.map((column) => column.name);
  });

  /** An import with no projects would be an import across a whole JIRA instance, which never runs. */
  protected readonly canImport = computed(
    () => this.projectKeys().length > 0 && this.activeRun() === null && !this.starting(),
  );

  constructor() {
    void this.loadRuns();
  }

  protected async addProject(key: string): Promise<void> {
    if (!key || this.projectKeys().includes(key)) return;

    this.removed.set(null);
    await this.save([...this.projectKeys(), key]);
  }

  protected async removeProject(key: string): Promise<void> {
    await this.save(this.projectKeys().filter((configured) => configured !== key));
    this.removed.set(key);
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
   * The last run, and the live one if there is one.
   *
   * Read once on load rather than polled: a run started from this page is watched over SSE, and a
   * run started elsewhere is somebody else's business until this page is opened again.
   */
  private async loadRuns(): Promise<void> {
    try {
      const [history, importers] = await Promise.all([
        this.runs.history(JIRA_IMPORTER, 1),
        this.runs.importers(),
      ]);

      this.lastRun.set(history[0] ?? null);

      const active = importers.find((importer) => importer.importerId === JIRA_IMPORTER)?.activeRunId;
      if (active) await this.runs.watch(active);
    } catch {
      // The page is still usable without a history: every other section reads its own resource.
      this.lastRun.set(null);
    }
  }

  private async save(keys: readonly string[]): Promise<void> {
    this.saving.set(true);
    this.error.set(null);

    try {
      await this.api.saveProjectKeys(keys);
      // The server owns the JQL preview, so the new state is read back rather than assembled here.
      this.settings.reload();
    } catch {
      this.error.set('Could not save the projects. Nothing has been changed.');
    } finally {
      this.saving.set(false);
    }
  }
}
