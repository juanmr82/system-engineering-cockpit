import { Component, computed, input, output } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { ReviewApiService } from './review-api.service';
import type { ItemDetail } from './review.model';

interface AttributeEntry {
  readonly label: string;
  readonly value: string;
}

// The detail panel of the Req review view (REQ_REVIEW.md §7). It owns its own request, keyed by
// the ref it is given, so opening it changes nothing about the table — not its selection, not its
// scroll position — and closing it disposes the request with the panel.
@Component({
  selector: 'sec-item-detail-panel',
  imports: [MatButtonModule, MatIconModule, MatProgressBarModule],
  templateUrl: './item-detail-panel.html',
  styleUrl: './item-detail-panel.scss',
})
export class ItemDetailPanel {
  readonly itemRef = input.required<string>();
  readonly closed = output<void>();
  /** Emitted when the user follows the module link, so the view can load that module instead. */
  readonly moduleSelected = output<string>();

  protected readonly detail = httpResource<ItemDetail>(() => ReviewApiService.itemUrl(this.itemRef()));

  // The dynamic attribute bag is Record<string, unknown>: narrowed here, at the point of use, and
  // rendered in the order the server sent it. An empty string is a value ("the attribute exists
  // and is empty") and renders as an empty cell rather than being dropped.
  protected readonly attributes = computed<AttributeEntry[]>(() =>
    Object.entries(this.detail.value()?.attributes ?? {}).map(([label, value]) => ({
      label,
      value: value === null || value === undefined ? '' : String(value),
    })),
  );

  protected readonly properties = computed(() => this.detail.value()?.properties ?? []);
}
