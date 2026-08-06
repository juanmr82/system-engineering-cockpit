import { Component, signal } from '@angular/core';
import type { ICellRendererAngularComp } from 'ag-grid-angular';
import type { ICellRendererParams } from 'ag-grid-community';
import type { ReviewCellContext, TableRow } from '../review-table.model';
import type { ReviewRow } from '../review.model';

/**
 * The Comment cell (§5.2): one reviewer comment per object, always visible and always editable.
 *
 * **Deliberately not an ag-grid editable cell.** Grid cell editing would introduce a second
 * staging concept — a cell value the grid holds and commits — beside the view's own edit buffer,
 * and R7 allows exactly one. So this is a plain `<input>` writing straight into the component's
 * `ref`-keyed buffer, which is what the Save button reads (ADR 0006).
 *
 * The value shown is asked for on every init and refresh rather than cached across rows: ag-grid
 * recycles a renderer as rows scroll, and a stale value here would put one object's comment on
 * another object's row.
 */
@Component({
  selector: 'sec-comment-cell',
  templateUrl: './comment-cell.html',
  styleUrl: './comment-cell.scss',
})
export class CommentCell implements ICellRendererAngularComp {
  protected readonly text = signal('');
  protected readonly dirty = signal(false);
  protected readonly label = signal('');

  private row: ReviewRow | null = null;
  private context?: ReviewCellContext;

  agInit(params: ICellRendererParams<TableRow>): void {
    this.update(params);
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
  }

  protected onInput(value: string): void {
    const row = this.row;
    const context = this.context;
    if (!row || !context) {
      return;
    }
    context.editComment(row, value);
    this.text.set(value);
    // Asked for rather than assumed: typing a comment back to what was stored un-dirties the row,
    // and only the buffer knows that.
    this.dirty.set(context.isDirty(row));
  }
}
