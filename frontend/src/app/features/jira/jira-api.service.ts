import { Injectable, Injector, computed, inject, signal } from '@angular/core';
import { HttpClient, httpResource } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import type {
  JiraConnection,
  JiraFieldTree,
  JiraImportReport,
  JiraIssues,
  JiraProjectList,
  SaveJiraColumnsRequest,
  SaveJiraProjectScopeRequest,
} from './jira.model';

/**
 * The one HTTP surface for the JIRA views (CLAUDE.md §11): `httpResource` for every GET,
 * `HttpClient` for the writes.
 *
 * After a successful write the caller reloads rather than patching a row by hand — the server is
 * truth, which matters more here than anywhere else in the application because an import changes
 * far more than the request that started it can describe.
 */
@Injectable({ providedIn: 'root' })
export class JiraApiService {
  private readonly http = inject(HttpClient);
  private readonly injector = inject(Injector);

  readonly connection = httpResource<JiraConnection>(() => '/api/v1/jira/connection');
  readonly projects = httpResource<JiraProjectList>(() => '/api/v1/jira/projects');

  /** Which projects the Issues table is filtered to. Empty means every project. */
  readonly projectFilter = signal<readonly string[]>([]);
  readonly pageOffset = signal(0);

  private readonly issuesUrl = computed(() => {
    const params = new URLSearchParams();
    params.set('offset', String(this.pageOffset()));
    params.set('limit', String(PAGE_SIZE));
    for (const key of this.projectFilter()) {
      params.append('project', key);
    }
    return `/api/v1/jira/issues?${params.toString()}`;
  });

  readonly issues = httpResource<JiraIssues>(() => this.issuesUrl());

  /**
   * The field tree, created per dialog open rather than held here.
   *
   * The same shape the module settings dialog uses: one dialog open, one resource scoped to it,
   * created against this service's injector so the call site need not be synchronous.
   */
  fieldTree() {
    return httpResource<JiraFieldTree>(() => '/api/v1/jira/fields', { injector: this.injector });
  }

  saveColumns(body: SaveJiraColumnsRequest): Promise<JiraFieldTree> {
    return firstValueFrom(this.http.post<JiraFieldTree>('/api/v1/jira/fields', body));
  }

  saveProjectScope(body: SaveJiraProjectScopeRequest): Promise<JiraProjectList> {
    return firstValueFrom(this.http.post<JiraProjectList>('/api/v1/jira/projects', body));
  }

  removeProject(ref: string): Promise<JiraProjectList> {
    return firstValueFrom(this.http.delete<JiraProjectList>(`/api/v1/jira/projects/${ref}`));
  }

  /**
   * Start an import and wait for its report.
   *
   * Synchronous by design: the user pressed a button and gets the run's report back as the
   * response. A run over a few thousand issues is seconds; the point at which that stops being
   * true is recorded in ADR 0013, and the answer there is a job id, not a longer timeout.
   */
  runImport(): Promise<JiraImportReport> {
    return firstValueFrom(this.http.post<JiraImportReport>('/api/v1/jira/import', {}));
  }
}

/** One page of issues. Bounded because Community has no query governor (CLAUDE.md §7). */
export const PAGE_SIZE = 100;
