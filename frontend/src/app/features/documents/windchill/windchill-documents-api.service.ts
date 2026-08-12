import { Injectable } from '@angular/core';
import { httpResource } from '@angular/common/http';
import type { WindchillDocuments } from './windchill-documents.model';

/**
 * The one HTTP client for the Documents view (CLAUDE.md §11).
 *
 * ## Why this takes no parameters
 *
 * Unlike the JIRA Issues table, this one asks for **everything, once**. Searching, sorting and
 * grouping all happen in the browser over the set in hand, which is what makes the search instant —
 * no debounce, no round trip, no request per keystroke. The set is ~1 500 documents; the server
 * caps it and says so when the cap is reached.
 *
 * So there is no query signal here and no `params`: the resource's identity is the endpoint, and
 * the only thing that ever changes it is an import, which the view reloads for explicitly.
 */
@Injectable({ providedIn: 'root' })
export class WindchillDocumentsApiService {
  /**
   * Every imported document, in the server's order: by number, then newest version first.
   *
   * A field rather than a factory method, deliberately, and it is the opposite call from
   * `JiraFieldsApiService` — this resource is wanted by exactly one view and is small, so an eager
   * fetch costs one request on a page that was about to make it anyway.
   */
  readonly documents = httpResource<WindchillDocuments>(() => '/api/v1/windchill/documents');
}
