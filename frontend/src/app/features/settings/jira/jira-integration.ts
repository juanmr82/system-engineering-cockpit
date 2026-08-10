import { Component, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTooltipModule } from '@angular/material/tooltip';
import { firstValueFrom } from 'rxjs';
import type { ProblemDetails } from '../../../core/error/problem-details';
import { ConfirmDialog } from '../../../shared/dialog/confirm-dialog';
import { FieldSelectionDialog } from '../../jira/field-selection-dialog/field-selection-dialog';
import { ImportReportDialog } from '../../jira/import-report-dialog/import-report-dialog';
import { JiraApiService } from '../../jira/jira-api.service';
import type { JiraProjectRow } from '../../jira/jira.model';

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
 * Settings → JIRA integration (design doc §9).
 *
 * Three things live here, and they are the three the design doc lists: which projects are
 * imported, the import trigger itself, and the column selection.
 *
 * ## Every row commits itself (R7)
 *
 * There is no Save button on this tab and no staging layer. Adding a project, switching one off,
 * editing its JQL clause and removing it are each one gesture, one request, one transaction — the
 * project list that comes back is the server's, not a locally patched copy. That is why the JQL
 * field commits on blur rather than on every keystroke: a per-keystroke write would be a request
 * per character, and a Save button would be the cross-view dirty state R7 forbids.
 *
 * ## Removing a project takes its issues
 *
 * Confirmed first, because it is not recoverable without another import, and the confirmation says
 * how many issues go. Leaving them would put rows in the Issues table with no project governing
 * them, which nothing in the UI could then explain.
 */
@Component({
  selector: 'sec-jira-integration',
  imports: [
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    MatSelectModule,
    MatSlideToggleModule,
    MatTooltipModule,
  ],
  templateUrl: './jira-integration.html',
  styleUrl: './jira-integration.scss',
})
export class JiraIntegration {
  protected readonly api = inject(JiraApiService);
  private readonly dialog = inject(MatDialog);

  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly projectToAdd = signal('');

  protected readonly connection = computed(() =>
    this.api.connection.hasValue() ? this.api.connection.value() : null,
  );

  protected readonly configured = computed(() => this.connection()?.configured === true);

  protected readonly projects = computed<JiraProjectRow[]>(() =>
    this.api.projects.hasValue() ? this.api.projects.value().projects : [],
  );

  protected readonly available = computed(() =>
    this.api.projects.hasValue() ? this.api.projects.value().available : [],
  );

  protected readonly inScope = computed(() => this.projects().filter((p) => p.inScope));

  protected readonly canImport = computed(
    () => this.configured() && this.inScope().some((p) => p.enabled) && !this.busy(),
  );

  protected readonly totalIssues = computed(() =>
    this.projects().reduce((sum, project) => sum + project.issueCount, 0),
  );

  private async withBusy(fallback: string, action: () => Promise<unknown>): Promise<void> {
    if (this.busy()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    try {
      await action();
    } catch (caught) {
      this.error.set(extractErrorDetail(caught, fallback));
    } finally {
      this.busy.set(false);
    }
  }

  protected async addProject(): Promise<void> {
    const key = this.projectToAdd().trim();
    if (!key) {
      return;
    }
    await this.withBusy(`'${key}' could not be added.`, async () => {
      await this.api.saveProjectScope({ key, enabled: true, jql: '' });
      this.projectToAdd.set('');
      this.api.projects.reload();
    });
  }

  protected async setEnabled(project: JiraProjectRow, enabled: boolean): Promise<void> {
    await this.withBusy(`'${project.key}' could not be updated.`, async () => {
      await this.api.saveProjectScope({ key: project.key, enabled, jql: project.jql });
      this.api.projects.reload();
    });
  }

  /** Commits on blur: a request per keystroke is not a save policy. */
  protected async setJql(project: JiraProjectRow, jql: string): Promise<void> {
    if (jql.trim() === project.jql.trim()) {
      return;
    }
    await this.withBusy(`The filter for '${project.key}' could not be saved.`, async () => {
      await this.api.saveProjectScope({ key: project.key, enabled: project.enabled, jql });
      this.api.projects.reload();
    });
  }

  protected async removeProject(project: JiraProjectRow): Promise<void> {
    const confirmation = ConfirmDialog.open(this.dialog, {
      title: `Remove ${project.key}?`,
      message:
        project.issueCount === 0
          ? `${project.name} will no longer be imported.`
          : `${project.name} will no longer be imported, and its ${project.issueCount} imported ` +
            `issue${project.issueCount === 1 ? '' : 's'} will be removed from the graph. ` +
            `Importing it again brings them back.`,
      confirmLabel: 'Remove',
    });
    if (!(await firstValueFrom(confirmation.afterClosed()))) {
      return;
    }
    await this.withBusy(`'${project.key}' could not be removed.`, async () => {
      await this.api.removeProject(project.ref);
      this.api.projects.reload();
      this.api.issues.reload();
    });
  }

  /** The same trigger the Issues view carries, so an admin can import without leaving Settings. */
  protected async runImport(): Promise<void> {
    await this.withBusy('The import could not be started. Please try again.', async () => {
      const report = await this.api.runImport();
      this.api.projects.reload();
      this.api.issues.reload();
      ImportReportDialog.open(this.dialog, report);
    });
  }

  protected openColumns(): void {
    FieldSelectionDialog.open(this.dialog)
      .afterClosed()
      .subscribe((saved) => {
        if (saved) {
          // The columns changed, so the table's shape did. The server decides both.
          this.api.issues.reload();
        }
      });
  }
}
