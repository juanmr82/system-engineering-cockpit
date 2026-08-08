import { Component, ElementRef, inject, signal, viewChild } from '@angular/core';
import type { AfterViewInit, OnDestroy } from '@angular/core';
import type { ICellRendererAngularComp } from 'ag-grid-angular';
import type { ICellRendererParams } from 'ag-grid-community';
import type { ReviewCellContext, TableRow } from '../review-table.model';
import type { ReviewRow } from '../review.model';

/**
 * The Comment cell (§5.2): one reviewer comment per object, always visible and always editable.
 *
 * **Deliberately not an ag-grid editable cell.** Grid cell editing would introduce a second
 * staging concept — a cell value the grid holds and commits — beside the view's own edit buffer,
 * and R7 allows exactly one. So this is a plain `<textarea>` writing straight into the component's
 * `ref`-keyed buffer, which is what the Save button reads (ADR 0006).
 *
 * The value shown is asked for on every init and refresh rather than cached across rows: ag-grid
 * recycles a renderer as rows scroll, and a stale value here would put one object's comment on
 * another object's row.
 *
 * **The box grows to its text, and the row grows with it.** A textarea has a fixed height whatever
 * its content, so a long comment used to scroll inside a cell the reviewer could not see the bottom
 * of. This measures the content and states the height; the column carries `autoHeight`, so ag-grid
 * takes that into the row height alongside the wrapped Description beside it.
 */
@Component({
  selector: 'sec-comment-cell',
  templateUrl: './comment-cell.html',
  styleUrl: './comment-cell.scss',
})
export class CommentCell implements ICellRendererAngularComp, AfterViewInit, OnDestroy {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly box = viewChild<ElementRef<HTMLTextAreaElement>>('box');

  protected readonly text = signal('');
  protected readonly dirty = signal(false);
  protected readonly label = signal('');

  private row: ReviewRow | null = null;
  private context?: ReviewCellContext;

  private observer: ResizeObserver | null = null;
  private observedWidth = 0;
  private frame = 0;

  agInit(params: ICellRendererParams<TableRow>): void {
    this.update(params);
  }

  ngAfterViewInit(): void {
    this.autosize();
    this.watchWidth();
  }

  ngOnDestroy(): void {
    this.observer?.disconnect();
    cancelAnimationFrame(this.frame);
  }

  // True keeps this instance alive and just re-reads it. That is what lets the parent clear the
  // dirty marks after a save without the input losing focus or the table reloading (§5.2).
  refresh(params: ICellRendererParams<TableRow>): boolean {
    this.update(params);
    return true;
  }

  private update(params: ICellRendererParams<TableRow>): void {
    const row = params.data?.row ?? null;
    const context = params.context as ReviewCellContext | undefined;
    this.row = row;
    this.context = context;
    this.label.set(row ? `Comment on ${row.id}` : 'Comment');
    this.text.set(row && context ? context.commentText(row) : '');
    this.dirty.set(!!row && !!context && context.isDirty(row));
    // Scrolling recycles this renderer onto another object, whose comment is a different length.
    // Deferred, because the signal above only schedules the DOM write — measuring now would measure
    // the previous object's text.
    this.scheduleAutosize();
  }

  protected onInput(value: string): void {
    const row = this.row;
    const context = this.context;
    // The user's own keystroke is already in the DOM, so this one measurement is not deferred: a
    // frame's delay is visible as the box lagging a line behind the text being typed into it.
    this.autosize();
    if (!row || !context) {
      return;
    }
    context.editComment(row, value);
    this.text.set(value);
    // Asked for rather than assumed: typing a comment back to what was stored un-dirties the row,
    // and only the buffer knows that.
    this.dirty.set(context.isDirty(row));
  }

  /**
   * Set the box's height to its content's height.
   *
   * `height: auto` first, and it is load-bearing: `scrollHeight` is the greater of the content and
   * the current height, so measuring without releasing the height means the box can only ever grow.
   * Deleting a paragraph would leave the row as tall as the paragraph was.
   */
  private autosize(): void {
    const element = this.box()?.nativeElement;
    if (!element) {
      return;
    }
    element.style.height = 'auto';
    element.style.height = `${element.scrollHeight}px`;
  }

  private scheduleAutosize(): void {
    cancelAnimationFrame(this.frame);
    this.frame = requestAnimationFrame(() => this.autosize());
  }

  /**
   * Re-measure when the column is dragged narrower or wider, because that re-wraps the text.
   *
   * **Width only.** Writing the height inside a `ResizeObserver` that also watches height is how a
   * `ResizeObserver loop` error is produced, and ag-grid has its own observer on this cell to keep
   * the row height in step — so reacting to our own height change would put the two in a cycle.
   * A width that has not changed does nothing at all.
   */
  private watchWidth(): void {
    if (this.observer || typeof ResizeObserver === 'undefined') {
      return;
    }
    this.observer = new ResizeObserver((entries) => {
      const width = Math.round(entries[0]?.contentRect.width ?? 0);
      if (width === 0 || width === this.observedWidth) {
        return;
      }
      this.observedWidth = width;
      this.scheduleAutosize();
    });
    this.observer.observe(this.host.nativeElement);
  }
}
