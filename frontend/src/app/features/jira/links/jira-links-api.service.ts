import { Injectable, Injector, inject } from '@angular/core';
import { httpResource } from '@angular/common/http';
import type { Signal } from '@angular/core';
import type { JiraLinkGraph } from './jira-links.model';

/**
 * The one HTTP client for the related-issues diagram (CLAUDE.md §11).
 *
 * A factory rather than a field: one dialog opens on one issue, and the depth control makes the
 * scope part of the request's identity — so the resource is created per dialog and re-runs when the
 * depth changes, exactly as the DOORS graph's does.
 */
@Injectable({ providedIn: 'root' })
export class JiraLinksApiService {
  private readonly injector = inject(Injector);

  graph(scope: Signal<{ ref: string; depth: number }>) {
    return httpResource<JiraLinkGraph>(
      () => {
        const current = scope();
        return {
          url: `/api/v1/jira/issues/${current.ref}/graph`,
          params: { depth: current.depth },
        };
      },
      { injector: this.injector },
    );
  }
}
