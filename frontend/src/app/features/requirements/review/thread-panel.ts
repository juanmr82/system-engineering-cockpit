import { Component, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { httpResource } from '@angular/common/http';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { detailOf } from '../../../core/error/problem-details';
import { AuthorAvatar } from '../../../shared/avatar/author-avatar';
import { ConfirmDialog } from '../../../shared/dialog/confirm-dialog';
import { SEC_MODAL_DIALOG } from '../../../shared/dialog/modal-dialog.config';
import { MentionText } from '../../../shared/text/mention-text';
import { ReviewApiService } from './review-api.service';
import type { AnnotationsResponse, ThreadNote } from './review.model';

export interface ThreadPanelData {
  readonly itemRef: string;
  /** The row's own id (`SRD-147`), shown as the panel's heading. */
  readonly itemLabel: string;
  /**
   * What an `item` mention's chip does when clicked — the same detail-panel mechanism the
   * References column already uses (`RequirementReview.openDetail`, wired in as
   * `ReviewCellContext.openDetail`). Passed in rather than owned here: this dialog has no route
   * of its own to open one, and the caller's detail panel already exists beside the table.
   */
  readonly onItemMentionClick: (ref: string) => void;
}

/**
 * The comment thread panel (`docs/req-review-comment-threads.md`).
 *
 * Every reply is its own request the moment it posts — there is no draft to save and no staging
 * layer, so this dialog carries none of R7's usual save/cancel machinery. `SEC_MODAL_DIALOG` still
 * applies (no accidental ESC/backdrop dismiss while a reply is half-typed); there is simply one
 * exit, Close, and it always succeeds.
 *
 * `changed` tracks whether *anything* was written during this open — a post, a resolve, a delete —
 * so the caller can refresh the row's thread summary without polling or assuming.
 */
@Component({
  selector: 'sec-thread-panel',
  imports: [
    AuthorAvatar,
    DatePipe,
    MatButtonModule,
    MatDialogModule,
    MatIconModule,
    MatProgressBarModule,
    MentionText,
  ],
  templateUrl: './thread-panel.html',
  styleUrl: './thread-panel.scss',
})
export class ThreadPanel {
  static open(dialog: MatDialog, data: ThreadPanelData) {
    return dialog.open<ThreadPanel, ThreadPanelData, boolean>(ThreadPanel, {
      ...SEC_MODAL_DIALOG,
      // A small dialog, not a big one — a card-sized panel
      // over a thread, sized to its content up to a cap rather than claiming most of the screen
      // the way the settings dialogs do.
      width: '420px',
      maxHeight: '70vh',
      data,
    });
  }

  protected readonly data = inject<ThreadPanelData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(MatDialogRef<ThreadPanel, boolean>);
  private readonly reviewApi = inject(ReviewApiService);
  private readonly dialog = inject(MatDialog);

  protected readonly annotations = httpResource<AnnotationsResponse>(() =>
    ReviewApiService.annotationsUrl(this.data.itemRef),
  );

  protected readonly root = computed<ThreadNote | null>(
    () => (this.annotations.hasValue() ? this.annotations.value().notes : []).find((n) => n.replyTo === null) ?? null,
  );
  protected readonly replies = computed<ThreadNote[]>(() =>
    (this.annotations.hasValue() ? this.annotations.value().notes : []).filter((n) => n.replyTo !== null),
  );

  protected readonly replyText = signal('');
  protected readonly posting = signal(false);
  protected readonly postError = signal<string | null>(null);
  protected readonly resolving = signal(false);
  protected readonly deletingRef = signal<string | null>(null);

  private readonly changed = signal(false);

  protected async post(): Promise<void> {
    const text = this.replyText().trim();
    if (!text || this.posting()) {
      return;
    }
    this.posting.set(true);
    this.postError.set(null);
    try {
      await this.reviewApi.postNote(this.data.itemRef, text);
      this.replyText.set('');
      this.changed.set(true);
      this.annotations.reload();
    } catch (error) {
      // Never lose the typed text on a failed write — there is no staging layer to recover it
      // from (R7).
      this.postError.set(detailOf(error, 'Something went wrong posting this comment. Please try again.'));
    } finally {
      this.posting.set(false);
    }
  }

  protected async toggleResolved(): Promise<void> {
    const root = this.root();
    if (!root || this.resolving()) {
      return;
    }
    this.resolving.set(true);
    try {
      await this.reviewApi.resolveThread(root.ref, !root.resolved);
      this.changed.set(true);
      this.annotations.reload();
    } finally {
      this.resolving.set(false);
    }
  }

  /** Deleting the root deletes the whole thread — the confirm message says so explicitly. */
  protected async delete(note: ThreadNote): Promise<void> {
    const isRoot = note.replyTo === null;
    const replyCount = this.replies().length;
    const confirmed = await new Promise<boolean | undefined>((resolve) => {
      ConfirmDialog.open(this.dialog, {
        title: isRoot ? 'Delete this comment?' : 'Delete this reply?',
        message:
          isRoot && replyCount > 0
            ? `This deletes the comment and its ${replyCount} ${replyCount === 1 ? 'reply' : 'replies'}. This cannot be undone.`
            : 'This cannot be undone.',
        confirmLabel: 'Delete',
        cancelLabel: 'Cancel',
      })
        .afterClosed()
        .subscribe(resolve);
    });
    if (confirmed !== true) {
      return;
    }

    this.deletingRef.set(note.ref);
    try {
      await this.reviewApi.deleteThread(note.ref);
      this.changed.set(true);
      // The whole thread is gone with the root — nothing left for this panel to show.
      if (isRoot) {
        this.dialogRef.close(true);
        return;
      }
      this.annotations.reload();
    } finally {
      this.deletingRef.set(null);
    }
  }

  /**
   * Closes this dialog and opens the mentioned item's detail panel underneath — in that order,
   * because `SEC_MODAL_DIALOG`'s backdrop would otherwise hide a panel opened while this dialog
   * is still on screen.
   */
  protected openMentionedItem(ref: string): void {
    this.dialogRef.close(this.changed());
    this.data.onItemMentionClick(ref);
  }

  protected close(): void {
    this.dialogRef.close(this.changed());
  }
}
