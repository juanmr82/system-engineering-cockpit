import { ApplicationRef } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AuthStore } from './auth-store';
import type { AuthenticatedUser } from './user';

const ME: AuthenticatedUser = {
  userId: 'user-1',
  displayName: 'Ada Lovelace',
  email: 'ada@example.com',
  roles: ['sec-user', 'sec-access-manager'],
  groups: ['/SEC/Thermal'],
  csrfToken: 'csrf-token-1',
  seesAll: false,
  categoryCount: 2,
};

describe('AuthStore', () => {
  let store: AuthStore;
  let httpTesting: HttpTestingController;
  let appRef: ApplicationRef;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    store = TestBed.inject(AuthStore);
    httpTesting = TestBed.inject(HttpTestingController);
    appRef = TestBed.inject(ApplicationRef);
  });

  afterEach(() => httpTesting.verify());

  it('exposes the signed-in user once /auth/me answers', async () => {
    // httpResource's initial fetch is scheduled through an effect, which needs a tick to run at
    // all outside a component fixture — nothing here ever calls detectChanges() to do it for us.
    TestBed.tick();
    httpTesting.expectOne('/api/v1/auth/me').flush(ME);
    await appRef.whenStable();

    expect(store.user()?.displayName).toBe('Ada Lovelace');
    expect(store.csrfToken()).toBe('csrf-token-1');
    expect(store.roles()).toEqual(['sec-user', 'sec-access-manager']);
    expect(store.groups()).toEqual(['/SEC/Thermal']);
    expect(store.seesAll()).toBe(false);
    expect(store.categoryCount()).toBe(2);
  });

  // AccessSet.SEES_ALL carries no categoryIds by design (backend AuthMeDto's own doc comment) —
  // this is the wart made visible in the frontend rather than mistaken for "sees nothing".
  it('categoryCount reads 0 for a seesAll user, which does not mean they see nothing', async () => {
    TestBed.tick();
    httpTesting.expectOne('/api/v1/auth/me').flush({ ...ME, seesAll: true, categoryCount: 0 });
    await appRef.whenStable();

    expect(store.seesAll()).toBe(true);
    expect(store.categoryCount()).toBe(0);
  });

  it('hasRole checks the roles the session actually carries', async () => {
    // httpResource's initial fetch is scheduled through an effect, which needs a tick to run at
    // all outside a component fixture — nothing here ever calls detectChanges() to do it for us.
    TestBed.tick();
    httpTesting.expectOne('/api/v1/auth/me').flush(ME);
    await appRef.whenStable();

    expect(store.hasRole('sec-access-manager')).toBe(true);
    expect(store.hasRole('sec-admin')).toBe(false);
  });

  // A user in no group at all is a real, designed-for state (R8) — csrfToken must still resolve so
  // a Tier-2 write from an empty-application view is not silently impossible.
  it('a user in no group still resolves an identity and a csrf token', async () => {
    TestBed.tick();
    httpTesting.expectOne('/api/v1/auth/me').flush({ ...ME, groups: [] });
    await appRef.whenStable();

    expect(store.groups()).toEqual([]);
    expect(store.csrfToken()).toBe('csrf-token-1');
  });

  it('signOut posts to /auth/logout and follows the returned end-session URL', async () => {
    // httpResource's initial fetch is scheduled through an effect, which needs a tick to run at
    // all outside a component fixture — nothing here ever calls detectChanges() to do it for us.
    TestBed.tick();
    httpTesting.expectOne('/api/v1/auth/me').flush(ME);
    await appRef.whenStable();

    const originalLocation = window.location;
    Object.defineProperty(window, 'location', {
      value: { href: '' },
      writable: true,
      configurable: true,
    });

    const signOutPromise = store.signOut();
    httpTesting.expectOne('/api/v1/auth/logout').flush({ endSessionUrl: 'https://keycloak.example/logout' });
    await signOutPromise;

    expect(window.location.href).toBe('https://keycloak.example/logout');
    Object.defineProperty(window, 'location', { value: originalLocation, writable: true, configurable: true });
  });
});
