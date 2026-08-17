import { Injectable, computed } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { DEFAULT_NAV_GROUPS, NavGroup } from './nav-group';

/** The wire shape of `GET /api/v1/config/navigation` — `NavigationResponseDto`, wrapped, not a
 *  bare array. */
interface NavigationResponse {
  readonly groups: NavGroup[];
}

// Fetches GET /api/v1/config/navigation and falls back to DEFAULT_NAV_GROUPS on failure, so a
// broken config file never produces an app with no navigation (CLAUDE.md §9).
@Injectable({ providedIn: 'root' })
export class NavigationService {
  private readonly navResource = httpResource<NavigationResponse>(() => '/api/v1/config/navigation');

  // resource.value() throws in an error state (the trap AuthStore's own doc comment names), so
  // the fallback has to go through hasValue() first — reading it unguarded meant a genuine fetch
  // failure threw instead of quietly falling back to the hardcoded default.
  readonly groups = computed<NavGroup[]>(() =>
    this.navResource.hasValue() ? this.navResource.value().groups : DEFAULT_NAV_GROUPS,
  );
}
