import { Component, computed, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { SEC_MODAL_DIALOG } from '../../../shared/dialog/modal-dialog.config';
import type { JiraImportReport } from '../jira.model';

export interface ImportReportDialogData {
  readonly report: JiraImportReport;
}

/** One line of the report. `emphasis` marks the numbers a reader should not miss. */
interface ReportLine {
  readonly label: string;
  readonly value: string;
  readonly emphasis: boolean;
}

/**
 * What the import did, shown when it finishes (design doc §5, step 7).
 *
 * A dialog rather than a snackbar, which is what the design doc offered as the alternative. An
 * import is not a notification: it reports a dozen numbers, some of which mean somebody has to go
 * and do something — a deleted issue, a field that disappeared from the catalogue, a project that
 * did not answer — and a snackbar takes those away after four seconds whether or not they were
 * read. The dialog is dismissible and says nothing more once closed, which is the right lifetime
 * for a result nothing else in the application stores.
 *
 * Nothing here is persisted. The report is derived from a run that has already finished, and a
 * stored derivation goes stale silently (R2).
 */
@Component({
  selector: 'sec-import-report-dialog',
  imports: [MatButtonModule, MatDialogModule, MatIconModule],
  templateUrl: './import-report-dialog.html',
  styleUrl: './import-report-dialog.scss',
})
export class ImportReportDialog {
  private readonly dialogRef = inject(MatDialogRef<ImportReportDialog>);
  protected readonly data = inject<ImportReportDialogData>(MAT_DIALOG_DATA);

  protected readonly report = this.data.report;

  protected readonly projectsLabel = computed(() =>
    this.report.projects.length === 0 ? 'None' : this.report.projects.join(', '),
  );

  /**
   * How long it took, in words rather than milliseconds.
   *
   * `1 m 12 s`, not `72014 ms`: the number is there to tell somebody whether to change the scope,
   * and no one reads a five-digit millisecond count as a duration.
   */
  protected readonly duration = computed(() => {
    const seconds = Math.round(this.report.durationMs / 1000);
    if (seconds < 1) {
      return 'Under a second';
    }
    if (seconds < 60) {
      return `${seconds} s`;
    }
    return `${Math.floor(seconds / 60)} m ${seconds % 60} s`;
  });

  protected readonly issueLines = computed<ReportLine[]>(() => [
    { label: 'Issues read from JIRA', value: `${this.report.issuesSeen}`, emphasis: true },
    { label: 'New', value: `${this.report.issuesCreated}`, emphasis: false },
    { label: 'Updated', value: `${this.report.issuesUpdated}`, emphasis: false },
    // Deleted is emphasised whatever its value: zero is the reassurance, and a number is the
    // thing a reader most needs to have seen.
    { label: 'Removed', value: `${this.report.issuesDeleted}`, emphasis: true },
  ]);

  protected readonly linkLines = computed<ReportLine[]>(() => [
    { label: 'Links created', value: `${this.report.linksCreated}`, emphasis: false },
    { label: 'Links removed', value: `${this.report.linksPruned}`, emphasis: false },
    { label: 'Hierarchy changes', value: `${this.report.hierarchyPruned}`, emphasis: false },
    {
      label: 'Linked issues outside the imported projects',
      value: `${this.report.placeholdersCreated}`,
      emphasis: false,
    },
  ]);

  protected readonly catalogueLines = computed<ReportLine[]>(() => [
    { label: 'Issue types', value: `${this.report.issueTypes}`, emphasis: false },
    { label: 'Fields available', value: `${this.report.fieldsInCatalog}`, emphasis: false },
  ]);

  protected readonly hasFieldChanges = computed(
    () => this.report.fieldsAdded.length > 0 || this.report.fieldsRemoved.length > 0,
  );

  protected close(): void {
    this.dialogRef.close();
  }

  /**
   * The dialog owns its own presentation, so a call site passes data and nothing else
   * (CLAUDE.md §6). `SEC_MODAL_DIALOG` carries the R7 contract.
   */
  static open(dialog: MatDialog, report: JiraImportReport) {
    return dialog.open<ImportReportDialog, ImportReportDialogData, void>(ImportReportDialog, {
      ...SEC_MODAL_DIALOG,
      width: '620px',
      maxHeight: '80vh',
      data: { report },
    });
  }
}
