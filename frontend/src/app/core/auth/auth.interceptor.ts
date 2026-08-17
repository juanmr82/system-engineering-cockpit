import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { AuthStore } from './auth-store';

const CSRF_HEADER = 'X-SEC-CSRF';
const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS']);

/**
 * The three mechanics ADR 0017 hands the frontend, in the one interceptor `frontend/CLAUDE.md`
 * calls for — "Credentials, not headers" plus the CSRF token plus the `401`/`403` split:
 *
 * - Every request carries the session cookie (`withCredentials`). There is no token to attach —
 *   the backend is the OIDC client and the browser never sees one.
 * - Every non-`GET` carries the double-submit CSRF header, read from {@link AuthStore}.
 * - A `401` is a **full browser navigation** to Keycloak, never a router navigation — the browser
 *   has to follow a redirect chain an Angular route cannot. A `403` is left alone: it renders as
 *   an in-app refusal wherever the failing call is handled, never a redirect (conflating the two
 *   produces a redirect loop that is nearly unreadable from a screenshot).
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const authStore = inject(AuthStore);

  let request = req.clone({ withCredentials: true });
  if (!SAFE_METHODS.has(req.method)) {
    const csrfToken = authStore.csrfToken();
    if (csrfToken) {
      request = request.clone({ setHeaders: { [CSRF_HEADER]: csrfToken } });
    }
  }

  return next(request).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status === 401) {
        const redirect = encodeURIComponent(window.location.pathname + window.location.search);
        window.location.href = `/api/v1/auth/login?redirect=${redirect}`;
      }
      return throwError(() => error);
    }),
  );
};
