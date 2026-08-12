import { Injectable, inject } from '@angular/core';
import { HttpClient, httpResource } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';

/** Whether this deployment knows where Windchill is. There is no credential to report. */
export interface WindchillHealth {
  readonly configured: boolean;
  readonly host: string;
}

/** What the upload answers with: the run to watch, and what the file turned out to be. */
export interface WindchillImportStarted {
  readonly runId: string;
  readonly documents: number;
  /** The file carried an `@odata.nextLink` — it is one page of several, and it imported anyway. */
  readonly paged: boolean;
  /** The parser's findings. Also raised as warnings on the run itself. */
  readonly warnings: readonly string[];
}

/** A refusal the settings page has a sentence for, as opposed to a failure it can only report. */
export class WindchillImportRejected extends Error {}

/**
 * The Windchill settings page's one HTTP client.
 *
 * ## The upload is the import
 *
 * `POST /api/v1/windchill/import` takes the file's text as its body and answers `202` with a run
 * id. One gesture, one request, one transaction (R7) — there is no staging step, no upload handle
 * and no second button, which is what makes "a file that will not import never becomes a run" a
 * property of the endpoint rather than of the page.
 *
 * The body is the JSON itself rather than a multipart part: there is one file and nothing beside
 * it, so an envelope would add a parser on both sides in exchange for nothing.
 */
@Injectable({ providedIn: 'root' })
export class WindchillSettingsApiService {
  private readonly http = inject(HttpClient);

  readonly health = httpResource<WindchillHealth>(() => '/api/v1/windchill/health');

  /**
   * Uploads an export and starts the import.
   *
   * A rejected file comes back as a `400` carrying an RFC 9457 problem detail, and its `detail` is
   * written for a person — so it is surfaced verbatim rather than replaced with a sentence of our
   * own. That is the one case where echoing the server's words is right: it knows which line of the
   * file was wrong and this does not.
   */
  async importExport(text: string): Promise<WindchillImportStarted> {
    try {
      return await firstValueFrom(
        this.http.post<WindchillImportStarted>('/api/v1/windchill/import', text, {
          headers: { 'Content-Type': 'application/json' },
        }),
      );
    } catch (cause) {
      throw new WindchillImportRejected(detailOf(cause));
    }
  }
}

/** The problem detail's own sentence, or a fallback when the failure never reached the API. */
function detailOf(cause: unknown): string {
  const body = (cause as { error?: unknown } | null)?.error;
  const detail = (body as { detail?: unknown } | null)?.detail;
  return typeof detail === 'string' && detail.length > 0
    ? detail
    : 'The export could not be imported. Check that the server is running and try again.';
}
