import { ApplicationRef } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { DEFAULT_NAV_GROUPS } from './nav-group';
import { NavigationService } from './navigation.service';

describe('NavigationService', () => {
  let service: NavigationService;
  let httpTesting: HttpTestingController;
  let appRef: ApplicationRef;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(NavigationService);
    httpTesting = TestBed.inject(HttpTestingController);
    appRef = TestBed.inject(ApplicationRef);
  });

  afterEach(() => httpTesting.verify());

  it('unwraps the wire response, which is { groups: [...] } and not a bare array', async () => {
    TestBed.tick();
    const served = [{ key: 'access', label: 'Access', items: [] }];
    httpTesting.expectOne('/api/v1/config/navigation').flush({ groups: served });
    await appRef.whenStable();

    expect(service.groups()).toEqual(served);
  });

  // The bug this guards against: reading resource.value() with no hasValue() check throws in an
  // error state instead of falling back — the same trap AuthStore's own doc comment names.
  it('falls back to DEFAULT_NAV_GROUPS on a fetch failure, without throwing', async () => {
    TestBed.tick();
    httpTesting.expectOne('/api/v1/config/navigation').flush(null, { status: 500, statusText: 'Server Error' });
    await appRef.whenStable();

    expect(() => service.groups()).not.toThrow();
    expect(service.groups()).toEqual(DEFAULT_NAV_GROUPS);
  });
});
