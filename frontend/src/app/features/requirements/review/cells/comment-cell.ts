import { Component, ElementRef, computed, inject, signal } from '@angular/core';
import type { OnDestroy } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { MatIconModule } from '@angular/material/icon';
import type { ICellRendererAngularComp } from 'ag-grid-angular';
import type { ICellRendererParams } from 'ag-grid-community';
import { AuthorAvatar } from '../../../../shared/avatar/author-avatar';
import { ReviewApiService } from '../review-api.service';
import type { ReviewCellContext, TableRow } from '../review-table.model';
import type { AnnotationsResponse, ReviewRow, ThreadNote, ThreadSummary } from '../review.model';

// One preview line's pixel budget, tuned against the table's own default row height
// (`--ag-row-height: 46px`, styles/_grid.scss) so an ordinary row yields exactly one slot — the
// compact chip — and only a row a wrapped Description made taller crosses into the per-note
// preview (docs/comments_design.jpg, the redesign's own baseline).
const LINE_HEIGHT = 24;
const MIN_EXPANDED_SLOTS = 2;

type Mode = 'empty' | 'compact' | 'expanded';

/**
 * The Comment cell: the whole cell is the affordance now, not a small icon a reviewer has to hunt
 * for on a row a wrapped Description made tall (docs/comments_design.jpg baseline). Three states:
 *
 * - **empty** — no thread yet. The full cell reads "Add a comment…"; clicking anywhere opens the
 *   panel, the same as every other state.
 * - **compact** — a thread exists but the row has no more than one line's worth of height to give
 *   it (the common case, at the table's own 46px row height): participant avatars, the count, and
 *   a resolved mark.
 * - **expanded** — the row is tall enough for more. Fetches the full thread lazily (only once a
 *   row actually needs it — most never do) and lists as many individual "avatar + one line of
 *   text" previews as fit, folding whatever is left into a trailing summary line.
 *
 * A thread's *identity* changing — a post, a resolve, a delete — hands the cell back to ag-grid
 * for a fresh instance rather than patching this one in place ({@link refresh}), the same pattern
 * `TableCell.refresh` already uses: a stale `httpResource` fetched under the old thread state must
 * not survive into the new one.
 */
@Component({
  selector: 'sec-comment-cell',
  imports: [AuthorAvatar, MatIconModule],
  templateUrl: './comment-cell.html',
  styleUrl: './comment-cell.scss',
})
export class CommentCell implements ICellRendererAngularComp, OnDestroy {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  private readonly rowSignal = signal<ReviewRow | null>(null);
  protected readonly thread = signal<ThreadSummary | null>(null);
  private context?: ReviewCellContext;

  private readonly slots = signal(1);
  private observer: ResizeObserver | null = null;
  /** What this instance was built for, so {@link refresh} can tell a re-read from a new thread. */
  private builtForThread: ThreadSummary | null = null;

  protected readonly wantsExpanded = computed(
    () => !!this.thread() && this.slots() >= MIN_EXPANDED_SLOTS,
  );

  // Fetched only once a row is actually tall enough to show it — every row at the table's default
  // height stops at the compact chip, which is drawn from the summary already on hand.
  protected readonly annotations = httpResource<AnnotationsResponse>(() => {
    const row = this.rowSignal();
    return this.wantsExpanded() && row ? ReviewApiService.annotationsUrl(row.ref) : undefined;
  });

  protected readonly mode = computed<Mode>(() => {
    if (!this.thread()) {
      return 'empty';
    }
    if (!this.wantsExpanded()) {
      return 'compact';
    }
    // Falls back to the compact chip while the fetch is in flight (or failed) rather than an empty
    // cell — the summary is already known, so there is always something to show.
    return this.annotations.hasValue() ? 'expanded' : 'compact';
  });

  protected readonly previewNotes = computed<ThreadNote[]>(() =>
    this.annotations.hasValue() ? this.annotations.value().notes : [],
  );

  /** How many notes get their own preview line; the rest fold into the trailing summary. */
  protected readonly individualCount = computed(() => {
    const total = this.previewNotes().length;
    const slots = this.slots();
    return total <= slots ? total : Math.max(0, slots - 1);
  });

  protected readonly individualNotes = computed(() => this.previewNotes().slice(0, this.individualCount()));
  protected readonly overflowCount = computed(() => this.previewNotes().length - this.individualCount());

  agInit(params: ICellRendererParams<TableRow>): void {
    this.update(params);
    this.builtForThread = this.thread();
    this.watchHeight();
  }

  refresh(params: ICellRendererParams<TableRow>): boolean {
    const incoming = params.data?.row.thread ?? null;
    if (incoming !== this.builtForThread) {
      return false;
    }
    this.update(params);
    return true;
  }

  ngOnDestroy(): void {
    this.observer?.disconnect();
  }

  private update(params: ICellRendererParams<TableRow>): void {
    const row = params.data?.row ?? null;
    this.context = params.context as ReviewCellContext | undefined;
    this.rowSignal.set(row);
    this.thread.set(row?.thread ?? null);
  }

  private watchHeight(): void {
    if (this.observer || typeof ResizeObserver === 'undefined') {
      return;
    }
    this.observer = new ResizeObserver(() => {
      const height = this.host.nativeElement.getBoundingClientRect().height;
      this.slots.set(Math.max(1, Math.floor(height / LINE_HEIGHT)));
    });
    this.observer.observe(this.host.nativeElement);
  }

  protected open(): void {
    const row = this.rowSignal();
    if (row) {
      this.context?.openThread(row);
    }
  }
}
