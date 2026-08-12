import { Injectable, inject } from '@angular/core';
import { HttpClient, httpResource } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import type { JiraHealth, JiraProject, JiraProjectSettings } from './jira-settings.model';

/**
 * The one HTTP client for the JIRA settings page (CLAUDE.md §11).
 *
 * `httpResource` for the three reads, `HttpClient` for the one write — the same split every other
 * feature uses. The write returns the new state, so a successful save is applied by *reloading*
 * rather than by patching a local copy: the server owns the JQL preview, and a client that
 * assembled its own would eventually disagree with the query that actually runs.
 */
@Injectable({ providedIn: 'root' })
export class JiraSettingsApiService {
  private readonly http = inject(HttpClient);

  /** Whether JIRA answers, and as whom. Reloaded by the *Test connection* button. */
  readonly health = httpResource<JiraHealth>(() => '/api/v1/jira/health');

  readonly settings = httpResource<JiraProjectSettings>(() => '/api/v1/jira/settings');

  /**
   * The live project list.
   *
   * 503s on a deployment with no JIRA host, which is a legitimate state rather than a failure: the
   * page can still show and edit the configured keys, because those are ours.
   */
  readonly projects = httpResource<JiraProject[]>(() => '/api/v1/jira/projects');

  saveProjectKeys(projectKeys: readonly string[]): Promise<JiraProjectSettings> {
    return firstValueFrom(
      this.http.put<JiraProjectSettings>('/api/v1/jira/settings', { projectKeys }),
    );
  }
}
