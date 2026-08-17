import { Injectable, computed, inject } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { AuthStore } from '../../core/auth/auth-store';
import { Role } from '../../core/auth/roles';
import type { AccessSummary } from './access.model';

/**
 * The sidenav's "Not assigned" badge — the count of containers with no direct category
 * (`docs/features/access-control.md` §10.2 screen 3).
 *
 * Gated on the caller's own role so the request never fires for anyone but an access manager:
 * `/api/v1/access/*` sits behind `requireRole(Role.ACCESS_MANAGER)` on the backend, so without
 * this guard every other signed-in user would take a wasted `403` on every page load.
 */
@Injectable({ providedIn: 'root' })
export class AccessBadgeService {
  private readonly authStore = inject(AuthStore);

  private readonly summaryResource = httpResource<AccessSummary>(() =>
    this.authStore.hasRole(Role.ACCESS_MANAGER) ? '/api/v1/access/summary' : undefined,
  );

  readonly count = computed<number | undefined>(() =>
    this.summaryResource.hasValue() ? this.summaryResource.value().unassignedContainerCount : undefined,
  );
}
