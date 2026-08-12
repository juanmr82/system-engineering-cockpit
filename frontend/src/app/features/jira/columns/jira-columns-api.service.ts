import { Injectable, Injector, inject } from '@angular/core';
import { HttpClient, httpResource } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import type { JiraColumn, JiraField } from './jira-columns.model';

/**
 * The one HTTP client for the column choice (CLAUDE.md §11).
 *
 * Shared by the picker dialog, the settings page's summary and the Issues table, because all three
 * ask the same question and a second copy of the answer is a second thing to keep in step.
 *
 * ## Why only one of the three is a field
 *
 * [columns] is small, wanted by every consumer, and reloaded after a save — so it is created once
 * and shared. The catalogue and the defaults are wanted by the **dialog only**, and the catalogue
 * is over a thousand rows: as eager fields on a root-provided service they would be fetched by
 * every page that so much as reads the column summary. They are factories instead, created against
 * this service's injector so a call site does not have to be inside an injection context.
 */
@Injectable({ providedIn: 'root' })
export class JiraColumnsApiService {
  private readonly http = inject(HttpClient);
  private readonly injector = inject(Injector);

  /** The chosen columns, in order, with the stale ones marked. Shared and reloaded after a save. */
  readonly columns = httpResource<JiraColumn[]>(() => '/api/v1/jira/columns');

  /** The whole offerable catalogue. Empty until an import has run, which is a state, not a failure. */
  fieldCatalogue() {
    return httpResource<JiraField[]>(() => '/api/v1/jira/fields', { injector: this.injector });
  }

  /** What *Reset to defaults* resets to — the server's list, never a copy of it held here. */
  defaults() {
    return httpResource<JiraColumn[]>(() => '/api/v1/jira/columns/defaults', {
      injector: this.injector,
    });
  }

  /**
   * Replace the chosen columns.
   *
   * One request per Save (R7) — never one per checkbox. The response is the resolved set, and
   * [columns] is reloaded so every consumer sees it: the server decides which columns are stale,
   * and no client can work that out for itself.
   */
  async save(fieldIds: readonly string[]): Promise<JiraColumn[]> {
    const saved = await firstValueFrom(
      this.http.put<JiraColumn[]>('/api/v1/jira/columns', { fieldIds }),
    );

    this.columns.reload();
    return saved;
  }
}
