import { Injectable, Injector, inject, type Signal } from '@angular/core';
import { httpResource } from '@angular/common/http';
import type { CyclesResponse, RequirementStatistics } from './statistics.model';

const BASE = '/api/v1/statistics/requirements';

/**
 * The one HTTP client for the Statistics view (CLAUDE.md §11).
 *
 * Two resources, never one. Band 4 scans the whole `refersTo` edge set, and the other three bands
 * must paint without waiting on it (§7.4) — a single resource would couple them and a slow loop
 * scan would blank the page.
 *
 * Both take the scope as a **signal**, so changing the module in the dropdown re-requests both
 * without any imperative reload call.
 */
@Injectable({ providedIn: 'root' })
export class StatisticsApiService {
  private readonly injector = inject(Injector);

  statistics(moduleRef: Signal<string | null>) {
    return httpResource<RequirementStatistics>(() => urlFor(BASE, moduleRef()), {
      injector: this.injector,
    });
  }

  cycles(moduleRef: Signal<string | null>) {
    return httpResource<CyclesResponse>(() => urlFor(`${BASE}/cycles`, moduleRef()), {
      injector: this.injector,
    });
  }
}

// The ref is already base64url and therefore URL-safe; it is encoded anyway so that a malformed
// handle arriving from the address bar reaches the server as a value it can reject with a 400,
// rather than as characters that change the shape of the request.
function urlFor(base: string, moduleRef: string | null): string {
  return moduleRef ? `${base}?module=${encodeURIComponent(moduleRef)}` : base;
}
