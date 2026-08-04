import { Injectable, computed } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { DEFAULT_NAV_GROUPS, NavGroup } from './nav-group';

// Fetches GET /api/v1/config/navigation and falls back to DEFAULT_NAV_GROUPS on failure, so a
// broken config file never produces an app with no navigation (CLAUDE.md §9).
@Injectable({ providedIn: 'root' })
export class NavigationService {
  private readonly navResource = httpResource<NavGroup[]>(() => '/api/v1/config/navigation');

  readonly groups = computed(() => this.navResource.value() ?? DEFAULT_NAV_GROUPS);
}
