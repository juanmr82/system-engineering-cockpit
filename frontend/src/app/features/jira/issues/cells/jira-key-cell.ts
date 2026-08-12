import { Component } from '@angular/core';
import type { ICellRendererAngularComp } from 'ag-grid-angular';
import type { ICellRendererParams } from 'ag-grid-community';
import type { JiraIssueRow } from '../jira-issues.model';

/**
 * The Key column: the issue key, and — for a stub — what a stub is.
 *
 * A renderer rather than a value formatter, because two things share the cell and only one of them
 * is the value. `unresolved` is a state channel on the row (R5); the words are this component's,
 * and saying them beside the key is what stops a reader treating a placeholder as an imported
 * issue. It is the same wording `:__UNDEFINED` carries everywhere else in the application — *Not
 * yet imported* — because it is the same fact about a different source.
 */
@Component({
  selector: 'sec-jira-key-cell',
  templateUrl: './jira-key-cell.html',
  styleUrl: './jira-key-cell.scss',
})
export class JiraKeyCell implements ICellRendererAngularComp {
  protected row?: JiraIssueRow;

  agInit(params: ICellRendererParams<JiraIssueRow>): void {
    this.row = params.data;
  }

  // ag-grid reuses a renderer instance for a different row when it scrolls. Returning true here
  // and re-reading the row is what keeps the cell showing its own row's key.
  refresh(params: ICellRendererParams<JiraIssueRow>): boolean {
    this.row = params.data;
    return true;
  }
}
