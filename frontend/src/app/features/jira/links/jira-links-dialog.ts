import { Component, computed, inject, signal } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef, type MatDialog } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { SEC_MODAL_DIALOG } from '../../../shared/dialog/modal-dialog.config';
import { JiraLinksApiService } from './jira-links-api.service';
import { JiraLinksCanvas } from './jira-links-canvas';
import { DEFAULT_DEPTH, MAX_DEPTH, MIN_DEPTH } from './jira-links.model';

export interface JiraLinksDialogData {
  readonly seedRef: string;
  /** What the table knew the issue was called, so the title is right before the response arrives. */
  readonly seedKey: string;
}

/**
 * The related issues of one issue, as a diagram.
 *
 * **Read-only**, so R7's save contract has nothing to hold: there is no dirty state and closing can
 * never discard anything. That is also why this one dialog does not need `disableClose` to mean
 * what it means elsewhere — it is spread in for consistency, and nothing is lost either way.
 *
 * Opens at a fixed near-fullscreen size and fits the *diagram* inside it rather than sizing itself
 * to the diagram: the extent changes with every depth change, and a frame that resized under the
 * cursor would be unusable (`REQ_BREAKDOWN_GRAPH_VIEW` §2.1).
 */
@Component({
  selector: 'sec-jira-links-dialog',
  imports: [
    JiraLinksCanvas,
    MatButtonModule,
    MatDialogModule,
    MatProgressBarModule,
    MatTooltipModule,
  ],
  templateUrl: './jira-links-dialog.html',
  styleUrl: './jira-links-dialog.scss',
})
export class JiraLinksDialog {
  private readonly data = inject<JiraLinksDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject<MatDialogRef<JiraLinksDialog>>(MatDialogRef);
  private readonly api = inject(JiraLinksApiService);

  protected readonly seedKey = this.data.seedKey;
  protected readonly depth = signal(DEFAULT_DEPTH);
  protected readonly depths = Array.from(
    { length: MAX_DEPTH - MIN_DEPTH + 1 },
    (_, index) => MIN_DEPTH + index,
  );

  private readonly scope = computed(() => ({ ref: this.data.seedRef, depth: this.depth() }));

  protected readonly graph = this.api.graph(this.scope);

  /** Held across a depth change, so the diagram does not blink out while the next one loads. */
  protected readonly current = computed(() => (this.graph.hasValue() ? this.graph.value() : null));

  protected readonly issueCount = computed(() => this.current()?.nodes.length ?? 0);
  protected readonly linkCount = computed(() => this.current()?.edges.length ?? 0);

  protected setDepth(depth: number): void {
    this.depth.set(depth);
  }

  protected close(): void {
    this.dialogRef.close();
  }

  /** A static `open` so no call site can size it wrongly or forget the modal contract (§6). */
  static open(dialog: MatDialog, data: JiraLinksDialogData) {
    return dialog.open<JiraLinksDialog, JiraLinksDialogData, void>(JiraLinksDialog, {
      ...SEC_MODAL_DIALOG,
      width: '1200px',
      height: '88vh',
      data,
    });
  }
}
