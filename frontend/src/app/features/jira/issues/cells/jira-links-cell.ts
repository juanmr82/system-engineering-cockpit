import { Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import type { ICellRendererAngularComp } from 'ag-grid-angular';
import type { ICellRendererParams } from 'ag-grid-community';
import { JiraLinksDialog } from '../../links/jira-links-dialog';
import type { JiraIssueRow } from '../jira-issues.model';

/**
 * The related-issues column: a control when there is something to see, and nothing when there
 * is not.
 *
 * **Empty is the point.** A disabled button in every row of a table is a control a reader has to
 * check one row at a time; an empty cell is read at a glance down the column. So an issue with no
 * links renders nothing at all, and the icon itself is the answer to "does this issue relate to
 * anything".
 */
@Component({
  selector: 'sec-jira-links-cell',
  imports: [MatButtonModule, MatIconModule, MatTooltipModule],
  templateUrl: './jira-links-cell.html',
  styleUrl: './jira-links-cell.scss',
})
export class JiraLinksCell implements ICellRendererAngularComp {
  private readonly dialog = inject(MatDialog);

  protected readonly row = signal<JiraIssueRow | undefined>(undefined);

  protected readonly linkCount = computed(() => this.row()?.linkCount ?? 0);

  protected readonly label = computed(() => {
    const count = this.linkCount();
    const key = this.row()?.key ?? '';
    return count === 1 ? `Show the issue linked to ${key}` : `Show the ${count} issues linked to ${key}`;
  });

  agInit(params: ICellRendererParams<JiraIssueRow>): void {
    this.row.set(params.data);
  }

  // ag-grid reuses a renderer for a different row when it scrolls.
  refresh(params: ICellRendererParams<JiraIssueRow>): boolean {
    this.row.set(params.data);
    return true;
  }

  protected open(): void {
    const row = this.row();
    if (!row) return;

    JiraLinksDialog.open(this.dialog, { seedRef: row.ref, seedKey: row.key });
  }
}
