import { Component, computed, input, output } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { parseMentions } from './mentions.model';
import type { TextSegment } from './mentions.model';

/**
 * Renders a note's `text` read-only, turning `@[display](kind:ref)` tokens into chips
 * (docs/req-review-comment-threads.md §3.3) — the "small parser/pipe" the spec calls for, not a
 * rich-text renderer: plain text runs pass through unchanged, and only a recognised token becomes
 * a chip.
 *
 * A `user` mention is a static chip — a person icon and a name, no link — because there is no
 * user-profile view to send it to (§3.4: notification and profile views are both later work). An
 * `item` mention is a button that emits {@link itemClick}; the caller decides what "open" means,
 * which today is the same detail-panel mechanism the References column already uses
 * (`ReviewCellContext.openDetail`) — no new route needed, since `GET /api/v1/items/{ref}` is
 * already ref-only and not scoped to the module currently on screen.
 */
@Component({
  selector: 'sec-mention-text',
  imports: [MatIconModule],
  templateUrl: './mention-text.html',
  styleUrl: './mention-text.scss',
})
export class MentionText {
  readonly text = input.required<string>();
  readonly itemClick = output<string>();

  protected readonly segments = computed<TextSegment[]>(() => parseMentions(this.text()));

  protected onItemClick(ref: string): void {
    this.itemClick.emit(ref);
  }
}
