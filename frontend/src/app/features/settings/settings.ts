import { Component, computed, inject } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatTabsModule } from '@angular/material/tabs';
import { JiraIntegration } from './jira/jira-integration';

/** The tabs, in order. The key is what the `?tab=` query parameter carries. */
const TABS = ['jira'] as const;
type TabKey = (typeof TABS)[number];

/**
 * Settings (design doc §9).
 *
 * A routed feature with tabs rather than a toolbar menu, because of what is going to live here:
 * the JIRA integration today, and — the design doc is explicit that RBAC is coming — role
 * configuration later. A flat `mat-menu` does not have anywhere to put the second one, and a
 * routed tab is a natural place to gate behind a guard when RBAC arrives.
 *
 * ## Two departures from §9, both deliberate
 *
 * **No `isAdmin` guard.** §9 asks for a simple flag that can be swapped for real RBAC later. There
 * is no authentication in this application at all yet — `CurrentUser.PLACEHOLDER` is what the
 * backend stamps on every write — so a guard here would be a component reading a constant `true`.
 * That is not a seam for RBAC, it is a thing to delete when RBAC lands, and it would read to the
 * next person as though access control existed. The route is the seam; the guard goes on it the
 * day there is an identity to ask about.
 *
 * **The host/token stay backend configuration** and are not editable here, which §9 also says. The
 * tab shows which JIRA is connected so an admin can confirm it, and nothing more.
 *
 * The tab lives in the query string so a link can open one — `/settings?tab=jira` is what the
 * Issues view's Settings button points at — without the route table growing a child per tab.
 */
@Component({
  selector: 'sec-settings',
  imports: [JiraIntegration, MatTabsModule],
  templateUrl: './settings.html',
  styleUrl: './settings.scss',
})
export class Settings {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  private readonly queryParams = toSignal(this.route.queryParamMap, {
    initialValue: this.route.snapshot.queryParamMap,
  });

  protected readonly selectedIndex = computed(() => {
    const tab = this.queryParams().get('tab') as TabKey | null;
    const index = tab ? TABS.indexOf(tab) : -1;
    return index >= 0 ? index : 0;
  });

  protected onTabChange(index: number): void {
    // replaceUrl, so flipping between tabs does not fill the back button with the same page.
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { tab: TABS[index] },
      replaceUrl: true,
    });
  }
}
