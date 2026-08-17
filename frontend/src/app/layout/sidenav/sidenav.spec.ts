import { ApplicationRef, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
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
      'access-unassigned',
      'access-defaults',
    ]);
    expect(access?.items.find((i) => i.key === 'access-unassigned')?.badge).toBe(5);

    const text: string = fixture.nativeElement.textContent;
    expect(text).toContain('5');
  });
});
