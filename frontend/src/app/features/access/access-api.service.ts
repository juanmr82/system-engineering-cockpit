import { Injectable, inject } from '@angular/core';
import { HttpClient, httpResource } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { AuthStore } from '../../core/auth/auth-store';
import { Role } from '../../core/auth/roles';
import type {
  AccessCategory,
  AccessCategoryListResponse,
  CreateAccessCategoryRequest,
  GroupListResponse,
  GroupWithGrants,
  SaveGrantsRequest,
  SetSeesAllRequest,
  UpdateAccessCategoryRequest,
} from './access.model';

/**
 * The one HTTP client for the Access views (CLAUDE.md §11) — built out screen by screen alongside
 * `access.model.ts`, today only categories. `httpResource` for every GET, `HttpClient` for
 * writes; a caller reloads the resource after a successful write rather than hand-patching a row
 * (same discipline `ModulesApiService` follows), because the server, not the client, is truth.
 *
 * Every `/api/v1/access/*` route sits behind `requireRole(Role.ACCESS_MANAGER)` on the backend
 * (unlike `ModulesApiService`'s routes), so every read here is gated on the caller's own role the
 * same way `AccessBadgeService`'s summary request already is — otherwise a screen's own role
 * self-check (rendering `RefusalPanel` instead of its content) would still leave the resource
 * firing a wasted `403` underneath it on every load.
 */
@Injectable({ providedIn: 'root' })
export class AccessApiService {
  private readonly http = inject(HttpClient);
  private readonly authStore = inject(AuthStore);

  readonly categories = httpResource<AccessCategoryListResponse>(() =>
    this.authStore.hasRole(Role.ACCESS_MANAGER) ? '/api/v1/access/categories' : undefined,
  );

  createCategory(body: CreateAccessCategoryRequest): Promise<AccessCategory> {
    return firstValueFrom(this.http.post<AccessCategory>('/api/v1/access/categories', body));
  }

  renameCategory(ref: string, body: UpdateAccessCategoryRequest): Promise<AccessCategory> {
    return firstValueFrom(this.http.patch<AccessCategory>(`/api/v1/access/categories/${ref}`, body));
  }

  deleteCategory(ref: string): Promise<void> {
    return firstValueFrom(this.http.delete<void>(`/api/v1/access/categories/${ref}`));
  }

  // -- Groups & Grants (spec §10.2 screen 2) --------------------------------------------------

  readonly groups = httpResource<GroupListResponse>(() =>
    this.authStore.hasRole(Role.ACCESS_MANAGER) ? '/api/v1/access/groups' : undefined,
  );

  saveGrants(ref: string, body: SaveGrantsRequest): Promise<GroupWithGrants> {
    return firstValueFrom(this.http.put<GroupWithGrants>(`/api/v1/access/groups/${ref}/grants`, body));
  }

  /** A deliberately separate write from `saveGrants` (spec §9: "audited loudly"). */
  setSeesAll(ref: string, body: SetSeesAllRequest): Promise<GroupWithGrants> {
    return firstValueFrom(this.http.patch<GroupWithGrants>(`/api/v1/access/groups/${ref}`, body));
  }
}
