import { Injectable } from '@angular/core';
import { httpResource } from '@angular/common/http';
import type { JiraHealth, JiraProject } from './jira-settings.model';

/**
 * The one HTTP client for the JIRA settings page (CLAUDE.md §11).
 *
 * Both reads are `httpResource` — there is no write here any more (ADR 0018): the importer brings
 * in everything the configured token can see, so there is nothing left on this page for a user to
 * configure.
 */
@Injectable({ providedIn: 'root' })
export class JiraSettingsApiService {
  /** Whether JIRA answers, and as whom. Reloaded by the *Test connection* button. */
  readonly health = httpResource<JiraHealth>(() => '/api/v1/jira/health');

  /**
   * The live project list — a read-only diagnostic (ADR 0018).
   *
   * 503s on a deployment with no JIRA host, which is a legitimate state rather than a failure.
   */
  readonly projects = httpResource<JiraProject[]>(() => '/api/v1/jira/projects');
}
