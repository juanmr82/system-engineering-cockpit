import { Injectable, computed, inject } from '@angular/core';
import { HttpClient, httpResource } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import type { AuthenticatedUser } from './user';

interface LogoutResponse {
  readonly endSessionUrl: string;
}

/**
 * The frontend's only source of identity (ADR 0017). Holds `GET /api/v1/auth/me` as a signal —
 * never cached beyond that: a fresh page load means a fresh `AuthStore`, which means a fresh
 * request, which is what "never browser-cached, re-fetched on every full page load" means in
 * practice. There is nothing here to invalidate on sign-out either; signing out navigates the
 * browser away and the whole application, this store included, is torn down with it.
 *
 * `resource.value()` throws in an error state (trap in `frontend/CLAUDE.md` §6), so every read
 * here goes through `hasValue()` first — including the anonymous case: a `401` on this very
 * request is exactly how a visitor with no session is discovered, and the interceptor
 * (`auth.interceptor.ts`) is what turns that into the redirect to Keycloak.
 */
@Injectable({ providedIn: 'root' })
export class AuthStore {
  private readonly http = inject(HttpClient);

  private readonly meResource = httpResource<AuthenticatedUser>(() => '/api/v1/auth/me');

  readonly isLoading = computed(() => this.meResource.isLoading());
  readonly user = computed<AuthenticatedUser | null>(() =>
    this.meResource.hasValue() ? this.meResource.value() : null,
  );

  /** The double-submit token `auth.interceptor.ts` attaches to every non-`GET` request. */
  readonly csrfToken = computed(() => this.user()?.csrfToken ?? null);
  readonly roles = computed<readonly string[]>(() => this.user()?.roles ?? []);
  readonly groups = computed<readonly string[]>(() => this.user()?.groups ?? []);
  readonly seesAll = computed(() => this.user()?.seesAll ?? false);
  readonly categoryCount = computed(() => this.user()?.categoryCount ?? 0);

  hasRole(role: string): boolean {
    return this.roles().includes(role);
  }

  /**
   * Drops the local session, then follows the server to Keycloak's own end-session endpoint
   * (RP-initiated logout, ADR 0017 §11) — a full navigation, the same as sign-in, because this
   * has to leave the single-page application entirely.
   */
  async signOut(): Promise<void> {
    const response = await firstValueFrom(this.http.post<LogoutResponse>('/api/v1/auth/logout', null));
    window.location.href = response.endSessionUrl;
  }
}
