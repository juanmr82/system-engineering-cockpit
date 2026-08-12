import { Component } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import type { ICellRendererAngularComp } from 'ag-grid-angular';
import type { ICellRendererParams } from 'ag-grid-community';
import type { JiraIssueRow } from '../jira-issues.model';

/**
 * The header-less last column: open this issue in JIRA (spec §13.2, point 14.6).
 *
 * **The href is `browseUrl`, never the issue's stored identity.** JIRA's `self` is an API URL that
 * answers with raw JSON, so a link built from it takes a reviewer to a wall of braces. The browse
 * URL is derived by the server on every read and is the one thing here that must not be assembled
 * in the browser.
 *
 * `rel="noopener noreferrer"` is not boilerplate: without `noopener` the opened page can reach back
 * through `window.opener`, and this application is the thing it would reach.
 */
@Component({
  selector: 'sec-jira-link-cell',
  imports: [MatIconModule, MatTooltipModule],
  templateUrl: './jira-link-cell.html',
  styleUrl: './jira-link-cell.scss',
})
export class JiraLinkCell implements ICellRendererAngularComp {
  protected row?: JiraIssueRow;

  agInit(params: ICellRendererParams<JiraIssueRow>): void {
    this.row = params.data;
  }

  refresh(params: ICellRendererParams<JiraIssueRow>): boolean {
    this.row = params.data;
    return true;
  }
}
