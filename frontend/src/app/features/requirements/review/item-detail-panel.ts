import { Component, computed, input, output } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTabsModule } from '@angular/material/tabs';
import { Breakdown } from './breakdown/breakdown';
import { ReviewApiService } from './review-api.service';
import type { ItemDetail } from './review.model';

interface AttributeEntry {
  readonly label: string;
  readonly value: string;
  /** True when this object has no value for the attribute — the panel says so in words. */
  readonly empty: boolean;
}

// The detail panel of the Req review view (REQ_REVIEW.md §7). It owns its own request, keyed by
// the ref it is given, so opening it changes nothing about the table — not its selection, not its
// scroll position — and closing it disposes the request with the panel.
@Component({
  selector: 'sec-item-detail-panel',
  imports: [Breakdown, MatButtonModule, MatIconModule, MatProgressBarModule, MatTabsModule],
  templateUrl: './item-detail-panel.html',
  styleUrl: './item-detail-panel.scss',
})
export class ItemDetailPanel {
  readonly itemRef = input.required<string>();
  readonly closed = output<void>();
  /** Emitted when the user follows the module link, so the view can load that module instead. */
  readonly moduleSelected = output<string>();

  protected readonly detail = httpResource<ItemDetail>(() => ReviewApiService.itemUrl(this.itemRef()));

  /**
   * The attributes this object carries, in the order the server sent them.
   *
   * The dynamic attribute bag is `Record<string, unknown>`, narrowed here at the point of use.
   *
   * **An empty value is kept and named.** `""` from DOORS means "the attribute exists and is empty",
   * which is not the same as absent (CLAUDE.md §11) — so it has always been listed, but it rendered
   * as an empty `<dd>`: a label with nothing beside it, which reads as the panel having failed to
   * show something. `empty` is what lets the template write *Empty* there instead.
   *
   * Deliberately **not** the module's whole attribute set. That was tried: it meant a module-wide
   * scan on every panel open — 8ms to 26ms, measured against the running service — to list
   * attributes the object does not have.
   */
  protected readonly attributes = computed<AttributeEntry[]>(() =>
    Object.entries(this.detail.value()?.attributes ?? {}).map(([label, value]) => {
      const rendered = value === null || value === undefined ? '' : String(value);
      return { label, value: rendered, empty: rendered === '' };
    }),
  );

  protected readonly properties = computed(() => this.detail.value()?.properties ?? []);

  /**
   * The line under the heading: the object's name, but only when the heading is not already it.
   *
   * With an id to lead on, the name is the useful second line; without one the name *is* the
   * heading, and repeating it under itself would say nothing.
   */
  protected readonly subtitle = computed(() => {
    const item = this.detail.value();
    return item?.id ? item.name : '';
  });
}
