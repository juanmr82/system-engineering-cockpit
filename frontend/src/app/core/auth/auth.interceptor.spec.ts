import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { AuthStore } from './auth-store';
import { authInterceptor } from './auth.interceptor';

/**
 * `AuthStore` is stubbed rather than real here: this spec is about what the interceptor does with
 * a csrf token, not about how `AuthStore` gets one — `auth-store.spec.ts` covers that, and letting
 * the real `httpResource`-backed store construct itself would fire its own `/auth/me` request that
 * has nothing to do with what any test below asserts.
 */
describe('authInterceptor', () => {
  let http: HttpClient;
  let httpTesting: HttpTestingController;
  let csrfToken: string | null;

  beforeEach(() => {
    csrfToken = 'csrf-abc';
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: AuthStore, useValue: { csrfToken: () => csrfToken } },
      ],
    });
    http = TestBed.inject(HttpClient);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpTesting.verify());

  it('sends every request with credentials — there is no token to attach instead', () => {
    http.get('/api/v1/health').subscribe();

    const request = httpTesting.expectOne('/api/v1/health');
    expect(request.request.withCredentials).toBe(true);
    request.flush('ok');
  });

  it('attaches the CSRF header to a non-GET request', () => {
    http.post('/api/v1/modules/system-levels', {}).subscribe();

    const request = httpTesting.expectOne('/api/v1/modules/system-levels');
    expect(request.request.headers.get('X-SEC-CSRF')).toBe('csrf-abc');
    request.flush({});
  });

  it('does not attach the CSRF header to a GET request', () => {
    http.get('/api/v1/modules').subscribe();

    const request = httpTesting.expectOne('/api/v1/modules');
    expect(request.request.headers.has('X-SEC-CSRF')).toBe(false);
    request.flush([]);
  });

  it('omits the CSRF header when there is no session yet, rather than sending an empty one', () => {
    csrfToken = null;
    http.post('/api/v1/modules/system-levels', {}).subscribe();

    const request = httpTesting.expectOne('/api/v1/modules/system-levels');
    expect(request.request.headers.has('X-SEC-CSRF')).toBe(false);
    request.flush({});
  });

  it('a 401 is a full navigation to Keycloak login with the current route as the redirect target', async () => {
    const originalLocation = window.location;
    Object.defineProperty(window, 'location', {
      value: { href: '', pathname: '/requirements/modules', search: '?module=srd' },
      writable: true,
      configurable: true,
    });

    const settled = firstValueFrom(http.get('/api/v1/modules')).catch(() => 'caught');
    httpTesting.expectOne('/api/v1/modules').flush('nope', { status: 401, statusText: 'Unauthorized' });
    await settled;

    expect(window.location.href).toBe(
      '/api/v1/auth/login?redirect=' + encodeURIComponent('/requirements/modules?module=srd'),
    );
    Object.defineProperty(window, 'location', { value: originalLocation, writable: true, configurable: true });
  });

  it('a 403 never navigates — it is left for the caller to render as a refusal', async () => {
    const originalLocation = window.location;
    Object.defineProperty(window, 'location', {
      value: { href: 'https://app.example/requirements/modules' },
      writable: true,
      configurable: true,
    });

    const settled = firstValueFrom(http.get('/api/v1/settings/jira')).catch(() => 'caught');
    httpTesting.expectOne('/api/v1/settings/jira').flush('nope', { status: 403, statusText: 'Forbidden' });
    await settled;

    expect(window.location.href).toBe('https://app.example/requirements/modules');
    Object.defineProperty(window, 'location', { value: originalLocation, writable: true, configurable: true });
  });
});
