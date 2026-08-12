import { Injectable } from '@angular/core';
import { httpResource } from '@angular/common/http';
import type { Signal } from '@angular/core';
import type { JiraIssuesPage, JiraIssuesQuery } from './jira-issues.model';

/**
 * The one HTTP client for the Issues table (CLAUDE.md §11).
 *
 * ## Why this takes a signal instead of a page number
 *
 * The table is paged, sorted and filtered **server-side** — 784 issues on the reference instance
 * and tens of thousands on a real one, so there is no "load it all and filter in the browser"
 * option here the way there is for the modules list. That makes the query part of the resource's
 * *identity*: every change to it is a new request, and `httpResource` re-runs when the signal it
 * reads changes. A method taking a page number would put that wiring in the component, once per
 * caller.
 */
@Injectable({ providedIn: 'root' })
export class JiraIssuesApiService {
  /**
   * One page of issues for [query].
   *
   * The parameters are given to `httpResource` as an object rather than assembled into a string:
   * a search term is user input on its way into a URL, and `HttpParams` is what encodes it.
   */
  issues(query: Signal<JiraIssuesQuery>) {
    return httpResource<JiraIssuesPage>(() => {
      const current = query();
      return {
        url: '/api/v1/jira/issues',
        params: {
          page: current.page,
          size: current.size,
          sort: current.sort,
          dir: current.dir,
          // Omitted rather than sent empty: the server reads a blank `q` as no filter either way,
          // and a URL carrying `q=` on every request is one that looks like it is searching.
          ...(current.q ? { q: current.q } : {}),
        },
      };
    });
  }
}
