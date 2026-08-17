import { ApplicationRef, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { OverlayContainer } from '@angular/cdk/overlay';
import { By } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';
import { AuthStore } from '../../core/auth/auth-store';
import { Role } from '../../core/auth/roles';
import { DEFAULT_NAV_GROUPS } from '../../core/navigation/nav-group';
import { NavigationService } from '../../core/navigation/navigation.service';
import { Sidenav } from './sidenav';

/**
 * Fakes rather than the real `AuthStore`/`NavigationService`, both driven through their own
 * `httpResource`s. Chaining a component's role-filter through two live resources plus
 * `AccessBadgeService`'s own third one does not settle inside one test's tick/flush cycle — it
 * showed up as five-second timeouts. Plain signals give the component's own `groups` computed
 * exactly the reactivity it needs; `AccessBadgeService` is left real, since wiring its resolved
 * count onto the right nav item is the one thing this file exists to prove.
 */
class FakeAuthStore {
  private readonly isAccessManager = signal(false);

  hasRole = (role: string): boolean => role === Role.ACCESS_MANAGER && this.isAccessManager();

  grantAccessManager(): void {
    this.isAccessManager.set(true);
  }
}

class FakeNavigationService {
  readonly groups = signal(DEFAULT_NAV_GROUPS);
}

describe('Sidenav', () => {
  let fixture: ComponentFixture<Sidenav>;
  let fakeAuthStore: FakeAuthStore;
  let httpTesting: HttpTestingController;
  let appRef: ApplicationRef;

  beforeEach(async () => {
    fakeAuthStore = new FakeAuthStore();
    await TestBed.configureTestingModule({
      imports: [Sidenav],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: AuthStore, useValue: fakeAuthStore },
        { provide: NavigationService, useValue: new FakeNavigationService() },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(Sidenav);
    httpTesting = TestBed.inject(HttpTestingController);
    appRef = TestBed.inject(ApplicationRef);
  });

  afterEach(() => httpTesting.verify());

  // frontend/CLAUDE.md §8: hide what the user cannot reach, never disable it — an empty group
  // header would advertise a feature nobody in it can open.
  it('hides the Access group entirely for a caller without the role, and never fires the badge request', async () => {
    fixture.detectChanges();
    await appRef.whenStable();
    fixture.detectChanges();

    const groupKeys = fixture.componentInstance['groups']().map((g) => g.key);
    expect(groupKeys).not.toContain('access');
    httpTesting.expectNone('/api/v1/access/summary');
  });

  it('shows the Access group with its badge for an access manager', async () => {
    fakeAuthStore.grantAccessManager();
    fixture.detectChanges();
    // The flush has to happen before whenStable() is awaited, not after: whenStable() does not
    // resolve while an HTTP request is in flight, and a request only leaves flight when this
    // test flushes it — awaiting first deadlocks.
    httpTesting
      .expectOne('/api/v1/access/summary')
      .flush({ categoryCount: 3, groupCount: 2, unassignedContainerCount: 5 });
    await appRef.whenStable();
    fixture.detectChanges();

    const access = fixture.componentInstance['groups']().find((g) => g.key === 'access');
    expect(access?.items.map((i) => i.key)).toEqual([
      'access-categories',
      'access-grants',
      'access-containers',
      'access-unassigned',
      'access-defaults',
    ]);
    expect(access?.items.find((i) => i.key === 'access-unassigned')?.badge).toBe(5);

    const text: string = fixture.nativeElement.textContent;
    expect(text).toContain('5');
  });

  // frontend/CLAUDE.md §9: "By default left nav-bar is expanded" — the 64px rail only appears
  // once the user asks for it.
  it('renders expanded by default: group labels and the full item list, no icon rail', async () => {
    fixture.detectChanges();
    await appRef.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Requirements');
    expect(fixture.debugElement.query(By.css('.sec-sidenav__rail'))).toBeNull();
  });

  it('collapses to one icon per group on toggle, and back to the full list on a second click', async () => {
    fixture.detectChanges();
    await appRef.whenStable();
    fixture.detectChanges();

    const toggle = fixture.debugElement.query(By.css('.sec-sidenav__toggle'))
      .nativeElement as HTMLButtonElement;

    toggle.click();
    fixture.detectChanges();

    const railItems = fixture.debugElement.queryAll(By.css('.sec-sidenav__rail-item'));
    expect(railItems.map((el) => el.nativeElement.getAttribute('aria-label'))).toEqual([
      'Requirements',
      'JIRA',
      'Documents',
      'CAMEO',
    ]);
    expect(fixture.debugElement.query(By.css('.sec-sidenav__group-label'))).toBeNull();

    toggle.click();
    fixture.detectChanges();

    expect(fixture.debugElement.query(By.css('.sec-sidenav__rail'))).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Requirements');
  });

  it('opens a group\'s flyout on click and lists its items, so a route can be reached collapsed', async () => {
    fixture.detectChanges();
    await appRef.whenStable();
    fixture.detectChanges();

    fixture.debugElement.query(By.css('.sec-sidenav__toggle')).nativeElement.click();
    fixture.detectChanges();

    const [requirementsIcon] = fixture.debugElement.queryAll(By.css('.sec-sidenav__rail-item'));
    (requirementsIcon.nativeElement as HTMLButtonElement).click();
    fixture.detectChanges();
    await appRef.whenStable();
    fixture.detectChanges();

    const overlayText =
      TestBed.inject(OverlayContainer).getContainerElement().textContent ?? '';
    expect(overlayText).toContain('Statistics');
    expect(overlayText).toContain('Modules');
    expect(overlayText).toContain('Req review');
  });

  // The flyout is click-only (MatMenuTrigger's own default toggle): a first click opens it, a
  // second click on the same icon closes it again without picking anything.
  it('toggles the flyout closed on a second click of the same icon', async () => {
    fixture.detectChanges();
    await appRef.whenStable();
    fixture.detectChanges();

    fixture.debugElement.query(By.css('.sec-sidenav__toggle')).nativeElement.click();
    fixture.detectChanges();

    const [requirementsIcon] = fixture.debugElement.queryAll(By.css('.sec-sidenav__rail-item'));
    const button = requirementsIcon.nativeElement as HTMLButtonElement;
    const overlay = TestBed.inject(OverlayContainer).getContainerElement();

    button.click();
    fixture.detectChanges();
    await appRef.whenStable();
    fixture.detectChanges();
    expect(overlay.textContent).toContain('Statistics');

    button.click();
    fixture.detectChanges();
    await appRef.whenStable();
    fixture.detectChanges();
    // Closing runs a real CSS exit animation (Material's default ~150ms), so the panel is still
    // in the DOM — mid-animation — right after whenStable() resolves; give it time to finish
    // rather than asserting mid-transition.
    await new Promise((resolve) => setTimeout(resolve, 300));
    fixture.detectChanges();
    expect(overlay.querySelector('.mat-mdc-menu-panel')).toBeNull();
  });

  // Hovering used to open the flyout too, which made it flicker open/closed on a plain click (a
  // mouse click fires `mouseenter` immediately before `click`, and MatMenuTrigger's own click
  // handler unconditionally toggles). Hover now only surfaces the group's matTooltip; opening the
  // interactive flyout is click-only.
  it('does not open the flyout on hover alone', async () => {
    fixture.detectChanges();
    await appRef.whenStable();
    fixture.detectChanges();

    fixture.debugElement.query(By.css('.sec-sidenav__toggle')).nativeElement.click();
    fixture.detectChanges();

    const [requirementsIcon] = fixture.debugElement.queryAll(By.css('.sec-sidenav__rail-item'));
    requirementsIcon.nativeElement.dispatchEvent(new MouseEvent('mouseenter', { bubbles: true }));
    fixture.detectChanges();

    const overlay = TestBed.inject(OverlayContainer).getContainerElement();
    expect(overlay.querySelector('.mat-mdc-menu-panel')).toBeNull();
  });
});
