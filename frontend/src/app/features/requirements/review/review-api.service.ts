import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import type { ModuleSettingsRequest } from '../modules/modules.model';
import type { ThreadNote } from './review.model';

// The writes and the one-off reads of the Req review view. The table's own resources are created
// in the component, not here, so they are torn down with the view rather than living on the root
// injector for the rest of the session.
//
// Settings go to the same endpoint the Modules dialog posts to, and reach the same guarded meta
// writer: one write path, several endpoints (REQ_REVIEW.md §8).
@Injectable({ providedIn: 'root' })
export class ReviewApiService {
  private readonly http = inject(HttpClient);

  static objectsUrl(moduleRef: string): string {
    return `/api/v1/modules/${moduleRef}/objects`;
  }

  static itemUrl(itemRef: string): string {
    return `/api/v1/items/${itemRef}`;
  }

  // The module's embedded tables, already reconstructed (docs/DOORS_TABLES.md §4.3). The visible
  // attribute columns ride along as repeated `attrs` parameters, set by the caller.
  static tablesUrl(moduleRef: string): string {
    return `/api/v1/modules/${moduleRef}/tables`;
  }

  // The thread panel's own load — every note on one item, root first
  // (docs/req-review-comment-threads.md §4).
  static annotationsUrl(itemRef: string): string {
    return `/api/v1/items/${itemRef}/annotations`;
  }

  // Each reply is its own request, its own transaction — R7's ordinary rule, not the batch
  // exception the single-note Comment column used to need. The server decides root vs. reply.
  postNote(itemRef: string, text: string): Promise<ThreadNote> {
    return firstValueFrom(
      this.http.post<ThreadNote>(ReviewApiService.annotationsUrl(itemRef), { text }),
    );
  }

  resolveThread(rootRef: string, resolved: boolean): Promise<ThreadNote> {
    return firstValueFrom(
      this.http.patch<ThreadNote>(`/api/v1/annotations/${rootRef}`, { resolved }),
    );
  }

  deleteThread(ref: string): Promise<void> {
    return firstValueFrom(this.http.delete<void>(`/api/v1/annotations/${ref}`));
  }

  saveSettings(moduleRef: string, body: ModuleSettingsRequest): Promise<unknown> {
    return firstValueFrom(this.http.post(`/api/v1/modules/${moduleRef}/settings`, body));
  }
}
