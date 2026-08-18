import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpEventType } from '@angular/common/http';
import { Observable } from 'rxjs';
import { detailOf } from '../../../core/error/problem-details';

/** What `POST /api/v1/doors/import` answered with (ADR 0019). */
export interface DoorsImportResult {
  readonly status: 'started' | 'skipped';
  /** Present only when `status` is `'started'` — the run to watch over SSE. */
  readonly runId?: string;
  /** Base64url over the module's `__id` (R5) — never the raw id. */
  readonly moduleRef: string;
  readonly moduleName: string;
  readonly objects: number;
  readonly checksum: string;
  readonly warnings: readonly string[];
}

/** One tick of an upload in flight: bytes sent so far, or the server's final answer. */
export type DoorsUploadEvent =
  | { readonly kind: 'progress'; readonly percent: number }
  | { readonly kind: 'done'; readonly result: DoorsImportResult };

/** A refusal the settings page has a sentence for, as opposed to a failure it can only report. */
export class DoorsImportRejected extends Error {}

/**
 * The DOORS settings page's one HTTP client.
 *
 * ## The upload is the import, same as Windchill's
 *
 * `POST /api/v1/doors/import` takes the file's text as its body and answers `200` (this exact file
 * was already imported — nothing to do) or `202` (a run to watch). One gesture, one request, one
 * transaction (R7): there is no staging step and no second button.
 *
 * ## Real upload progress, unlike Windchill's page
 *
 * DOORS exports run larger than a Windchill document — a module can carry up to 12 000 objects at
 * 78+ attributes each — so this reports genuine byte-level progress while the file is *in transit*,
 * via `HttpClient`'s own `reportProgress`/`observe: 'events'`, rather than only the "reading…"
 * spinner Windchill's page shows while `File.text()` resolves. Once the upload completes, the run's
 * own progress (phases, percent) takes over exactly the way it does for Windchill — this is a second,
 * earlier phase, not a replacement for it.
 */
@Injectable({ providedIn: 'root' })
export class DoorsSettingsApiService {
  private readonly http = inject(HttpClient);

  /**
   * Uploads an export and starts the import, reporting upload progress as it goes.
   *
   * Completes after the server's response arrives — there is nothing to subscribe to beyond that;
   * import-run progress is a separate concern, watched via `ImportRunStore` once a run id comes back.
   */
  importExport(text: string): Observable<DoorsUploadEvent> {
    return new Observable<DoorsUploadEvent>((subscriber) => {
      const subscription = this.http
        .request('POST', '/api/v1/doors/import', {
          body: text,
          headers: { 'Content-Type': 'application/json' },
          reportProgress: true,
          observe: 'events',
        })
        .subscribe({
          next: (event) => {
            if (event.type === HttpEventType.UploadProgress) {
              const percent = event.total
                ? Math.min(100, Math.round((100 * event.loaded) / event.total))
                : 0;
              subscriber.next({ kind: 'progress', percent });
            } else if (event.type === HttpEventType.Response) {
              subscriber.next({ kind: 'done', result: event.body as DoorsImportResult });
              subscriber.complete();
            }
          },
          error: (cause: unknown) => {
            subscriber.error(
              new DoorsImportRejected(
                detailOf(
                  cause,
                  'The export could not be imported. Check that the server is running and try again.',
                ),
              ),
            );
          },
        });

      return () => subscription.unsubscribe();
    });
  }
}
