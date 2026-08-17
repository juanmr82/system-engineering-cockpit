import { ApplicationRef, signal } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AuthStore } from '../../core/auth/auth-store';
import { Role } from '../../core/auth/roles';
import { AccessBadgeService } from './access-badge.service';

/**
 * A fake rather than the real `AuthStore`, driven through its own `/auth/me` `httpResource`.
 * `AccessBadgeService`'s request is itself a `httpResource` reading `AuthStore.hasRole()` — two
 * chained resources' cross-resource reactivity does not settle inside one test's tick/flush cycle
 * the way a single resource's does, which showed up as five-second timeouts here. A plain signal
 * gives `AccessBadgeService`'s own request-recompute exactly the direct reactivity it needs
 * without also re-exercising `AuthStore`'s own httpResource lifecycle, which `auth-store.spec.ts`
 * already covers.
 */
class FakeAuthStore {
  private readonly isAccessManager = signal(false);

  hasRole = (role: string): boolean => role === Role.ACCESS_MANAGER && this.isAccessManager();

  grantAccessManager(): void {
    this.isAccessManager.set(true);
  }
}

describe('AccessBadgeService', () => {
  let fakeAuthStore: FakeAuthStore;
  let httpTesting: HttpTestingController;
  let appRef: ApplicationRef;

  beforeEach(() => {
    fakeAuthStore = new FakeAuthStore();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthStore, useValue: fakeAuthStore },
      ],
    });
    httpTesting = TestBed.inject(HttpTestingController);
    appRef = TestBed.inject(ApplicationRef);
  });

  afterEach(() => httpTesting.verify());

  it('never requests the summary for a caller without the Access manager role', async () => {
    const service = TestBed.inject(AccessBadgeService);
    TestBed.tick();
    await appRef.whenStable();

    expect(service.count()).toBeUndefined();
    httpTesting.expectNone('/api/v1/access/summary');
  });

  it('resolves the unassigned-container count for an access manager', async () => {
    const service = TestBed.inject(AccessBadgeService);
    fakeAuthStore.grantAccessManager();
    TestBed.tick();
    // The flush has to happen before whenStable() is awaited, not after: whenStable() does not
    // resolve while an HTTP request is in flight, and a request only leaves flight when this
    // test flushes it — awaiting first, as the earlier draft of this test did, deadlocks.
    httpTesting
      .expectOne('/api/v1/access/summary')
      .flush({ categoryCount: 3, groupCount: 2, unassignedContainerCount: 5 });
    await appRef.whenStable();

    expect(service.count()).toBe(5);
  });
});
