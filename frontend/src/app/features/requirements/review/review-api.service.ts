import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import type { ModuleSettingsRequest } from '../modules/modules.model';
import type { SaveCommentsRequest, SaveCommentsResponse } from './review.model';

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

  // Every dirty comment for one module, one request, one server-side transaction. Partial success
  // is impossible: on failure nothing is written and the edits stay on screen (§5.2).
  saveComments(moduleRef: string, body: SaveCommentsRequest): Promise<SaveCommentsResponse> {
    return firstValueFrom(
      this.http.post<SaveCommentsResponse>(`/api/v1/modules/${moduleRef}/comments`, body),
    );
  }

  saveSettings(moduleRef: string, body: ModuleSettingsRequest): Promise<unknown> {
    return firstValueFrom(this.http.post(`/api/v1/modules/${moduleRef}/settings`, body));
  }
}
