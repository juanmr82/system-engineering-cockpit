import { Injectable, Injector, inject } from '@angular/core';
import { HttpClient, httpResource } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import type {
  ModuleAttributesResponse,
  ModuleDetail,
  ModuleListResponse,
  ModuleSettingsRequest,
  SystemLevelsResponse,
} from './modules.model';

// The one HTTP client for this feature (CLAUDE.md §11): httpResource for every GET, HttpClient
// for the single POST. After a successful save the caller reloads `modules` rather than
// hand-patching a row (requirements-modules.md §7) — the server, not the client, is truth.
@Injectable({ providedIn: 'root' })
export class ModulesApiService {
  private readonly http = inject(HttpClient);
  private readonly injector = inject(Injector);

  readonly modules = httpResource<ModuleListResponse>(() => '/api/v1/modules');
  readonly systemLevels = httpResource<SystemLevelsResponse>(() => '/api/v1/config/system-levels');

  // Factory methods: one dialog open = one pair of resources scoped to that ref, created
  // explicitly against this service's injector so a synchronous call site isn't required.
  moduleDetail(ref: string) {
    return httpResource<ModuleDetail>(() => `/api/v1/modules/${ref}`, { injector: this.injector });
  }

  moduleAttributes(ref: string) {
    return httpResource<ModuleAttributesResponse>(() => `/api/v1/modules/${ref}/attributes`, {
      injector: this.injector,
    });
  }

  saveSettings(ref: string, body: ModuleSettingsRequest): Promise<ModuleDetail> {
    return firstValueFrom(this.http.post<ModuleDetail>(`/api/v1/modules/${ref}/settings`, body));
  }
}