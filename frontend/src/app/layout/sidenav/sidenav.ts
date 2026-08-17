import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { MatMenuModule } from '@angular/material/menu';
import { MatTooltipModule } from '@angular/material/tooltip';
import { NavigationEnd, Router, RouterLink, RouterLinkActive } from '@angular/router';
import { filter, map } from 'rxjs';
import { AuthStore } from '../../core/auth/auth-store';
import { Role } from '../../core/auth/roles';
import type { SecIconName } from '../../core/icons/sec-icons';
import type { NavGroup } from '../../core/navigation/nav-group';
import { NavigationService } from '../../core/navigation/navigation.service';
import { AccessBadgeService } from '../../features/access/access-badge.service';
import { Logo } from './logo';
import { SidenavCollapseService } from './sidenav-collapse.service';

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

/**
 * One glyph per source family, for the collapsed 64px rail (frontend/CLAUDE.md §9). Keyed by
 * group, not by item — a source family is one icon, not one per view. This lives here rather than
 * in the backend config because it is presentation, not navigation structure: the backend owns
 * order and labels, the frontend owns how a key is drawn (same split `ITEM_ROLE` already makes for
 * roles). A group key shipped by config with no entry here falls back to `DEFAULT_GROUP_ICON`
 * rather than rendering nothing.
 */
const GROUP_ICON: Readonly<Record<string, SecIconName>> = {
  requirements: 'doors',
  jira: 'jira',
  documents: 'windchill',
  cameo: 'cameo',
  access: 'shield',
};
const DEFAULT_GROUP_ICON: SecIconName = 'graph';

// Rendered from NavGroup[] (config, never hand-written markup, never the graph — CLAUDE.md §9).
// Collapsed state and its 64px rail are owned by SidenavCollapseService, shared with Shell so the
// drawer width and this template move together without an input/output chain between them.
@Component({
  selector: 'sec-sidenav',
  imports: [
    RouterLink,
    RouterLinkActive,
    MatListModule,
    MatButtonModule,
    MatIconModule,
    MatMenuModule,
    MatTooltipModule,
    Logo,
  ],
  templateUrl: './sidenav.html',
  styleUrl: './sidenav.scss',
})
export class Sidenav {
  private readonly navigation = inject(NavigationService);
  private readonly authStore = inject(AuthStore);
  private readonly accessBadge = inject(AccessBadgeService);
  private readonly router = inject(Router);
  protected readonly collapse = inject(SidenavCollapseService);

  private readonly currentUrl = toSignal(
    this.router.events.pipe(
      filter((event): event is NavigationEnd => event instanceof NavigationEnd),
      map((event) => event.urlAfterRedirects),
    ),
    { initialValue: this.router.url },
  );

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

  protected groupIcon(group: NavGroup): SecIconName {
    return GROUP_ICON[group.key] ?? DEFAULT_GROUP_ICON;
  }

  // Mirrors the "subset" match RouterLinkActive already applies to each expanded item: a route one
  // level under an item's own route (an item's detail view, say) still marks the collapsed rail's
  // group icon current, without claiming an unrelated route that merely starts with the same text.
  protected isGroupActive(group: NavGroup): boolean {
    const url = this.currentUrl();
    return group.items.some((item) => url === item.route || url.startsWith(`${item.route}/`));
  }
}
