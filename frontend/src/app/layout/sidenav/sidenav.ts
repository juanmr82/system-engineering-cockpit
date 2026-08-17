import { Component, computed, inject } from '@angular/core';
import { MatListModule } from '@angular/material/list';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { AuthStore } from '../../core/auth/auth-store';
import { Role } from '../../core/auth/roles';
import type { NavGroup } from '../../core/navigation/nav-group';
import { NavigationService } from '../../core/navigation/navigation.service';
import { AccessBadgeService } from '../../features/access/access-badge.service';
import { Logo } from './logo';

/**
 * Which nav-item key requires which role. An item absent here is open to any signed-in user —
 * the config itself carries no role field on purpose (root CLAUDE.md §9, backend step 9: a
 * per-item role in `application.yaml` would be a second, redundant encoding of
 * `Role.ACCESS_MANAGER` that could drift from the one actually enforced at the route).
 */
const ITEM_ROLE: Readonly<Record<string, string>> = {
  'access-categories': Role.ACCESS_MANAGER,
  'access-grants': Role.ACCESS_MANAGER,
  'access-containers': Role.ACCESS_MANAGER,
  'access-unassigned': Role.ACCESS_MANAGER,
  'access-defaults': Role.ACCESS_MANAGER,
};

// Rendered from NavGroup[] (config, never hand-written markup, never the graph — CLAUDE.md §9).
// Collapse-to-64px and per-user width are a later pass; this is the expanded, always-open shape.
@Component({
  selector: 'sec-sidenav',
  imports: [RouterLink, RouterLinkActive, MatListModule, Logo],
  templateUrl: './sidenav.html',
  styleUrl: './sidenav.scss',
})
export class Sidenav {
  private readonly navigation = inject(NavigationService);
  private readonly authStore = inject(AuthStore);
  private readonly accessBadge = inject(AccessBadgeService);

  /**
   * Filtered to what this caller may reach, then any group left with no items is dropped — an
   * empty group header would advertise a feature nobody in it can open (frontend/CLAUDE.md §8:
   * "hide what the user cannot reach; never disable it"). The unassigned-count badge is overlaid
   * here rather than carried by `NavigationService`, since it is per-caller state, not config.
   */
  protected readonly groups = computed<NavGroup[]>(() =>
    this.navigation
      .groups()
      .map((group) => ({
        ...group,
        items: group.items
          .filter((item) => this.canSee(item.key))
          .map((item) =>
            item.key === 'access-unassigned' ? { ...item, badge: this.accessBadge.count() } : item,
          ),
      }))
      .filter((group) => group.items.length > 0),
  );

  private canSee(itemKey: string): boolean {
    const role = ITEM_ROLE[itemKey];
    return role === undefined || this.authStore.hasRole(role);
  }
}
