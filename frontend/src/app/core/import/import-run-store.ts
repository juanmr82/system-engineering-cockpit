import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import {
  IMPORT_EVENT,
  isFinished,
  type CountersEvent,
  type ImportLogLine,
  type ImportRun,
  type ImportStarted,
  type Importer,
  type LogEvent,
  type PhaseEvent,
  type ProgressEvent,
  type StatusEvent,
} from './import.model';

/** How long to wait before the first reconnect, and the ceiling it doubles towards (spec §13.6). */
const RETRY_MIN_MS = 1_000;
const RETRY_MAX_MS = 30_000;

/** The live log is a window, not a transcript: the server keeps its own and never sends it twice. */
const MAX_LOG_LINES = 500;

/**
 * One import run, live (spec §13.6).
 *
 * ## Why this is a store and not a resource
 *
 * `httpResource` models a value that is fetched. A run is a value that is *pushed at you* — the
 * server opens a stream and sends five kinds of event until the run ends. Every handler writes to a
 * signal and nothing else triggers change detection, which is what makes this work under zoneless
 * Angular without a single `NgZone` reference.
 *
 * ## The order of the two steps in [watch] is load-bearing
 *
 * Read the run resource **first**, then subscribe. The stream is not a history: a client that
 * subscribed first would show an empty console for a run already at phase four, and one that asked
 * the stream to replay would be keeping a second, weaker copy of the resource that disagrees with
 * it the moment either drops an event.
 *
 * Provided in root and holding exactly one run: two consoles open on two runs is not a thing this
 * application can do — there is one import at a time per importer, and the toolbar chip and the
 * console are two views of the same one.
 */
@Injectable({ providedIn: 'root' })
export class ImportRunStore {
  private readonly http = inject(HttpClient);

  private readonly run$ = signal<ImportRun | null>(null);
  private readonly logs$ = signal<readonly ImportLogLine[]>([]);
  private readonly connected$ = signal(false);

  /** The run being watched, or null when none is. */
  readonly run = this.run$.asReadonly();
  readonly logs = this.logs$.asReadonly();

  /**
   * Whether the stream is open.
   *
   * Not the same as "running": a finished run has no stream and is not a problem, while a running
   * one with no stream is exactly the state a reader needs to be told about, because the numbers
   * on screen have stopped moving for a reason that is not the import.
   */
  readonly connected = this.connected$.asReadonly();

  readonly isRunning = computed(() => this.run$()?.status === 'RUNNING');

  private source: EventSource | null = null;
  private retryAt = RETRY_MIN_MS;
  private retryTimer: ReturnType<typeof setTimeout> | null = null;
  private watching: string | null = null;

  constructor() {
    // A store provided in root outlives every view, but not the application — and an EventSource
    // that survives it holds a connection open against a server that will never speak again.
    inject(DestroyRef).onDestroy(() => this.stop());
  }

  /** Every registered importer and, for each, the run happening right now if there is one. */
  importers(): Promise<Importer[]> {
    return firstValueFrom(this.http.get<Importer[]>('/api/v1/import/importers'));
  }

  /** Previous runs, newest first. `importerId` narrows it to one source. */
  history(importerId?: string, limit = 20): Promise<ImportRun[]> {
    const query = new URLSearchParams({ limit: String(limit) });
    if (importerId) query.set('importerId', importerId);

    return firstValueFrom(this.http.get<ImportRun[]>(`/api/v1/import/runs?${query}`));
  }

  /** Start an import and begin watching it. The caller gets the run id it can link to. */
  async start(importerId: string): Promise<string> {
    const started = await firstValueFrom(
      this.http.post<ImportStarted>(`/api/v1/import/${importerId}/runs`, {}),
    );

    await this.watch(started.runId);
    return started.runId;
  }

  /**
   * Cancellation is a request, not an act (the server answers 202).
   *
   * Nothing is written here: the phases stop at their next checkpoint and the `status` event is
   * what tells this store it happened. Setting `CANCELLED` locally would show a stopped run that
   * is still writing to the graph.
   */
  cancel(runId: string): Promise<unknown> {
    return firstValueFrom(this.http.delete(`/api/v1/import/runs/${runId}`));
  }

  /**
   * Watch one run: read its current state, then subscribe for the rest.
   *
   * Idempotent for the run already being watched, because the console calls it on every navigation
   * and re-subscribing would drop events in the gap between the two connections.
   */
  async watch(runId: string): Promise<void> {
    if (this.watching === runId && this.source) return;

    this.stop();
    this.watching = runId;

    const run = await this.load(runId);
    // Nothing to subscribe to, and the run resource has already said everything there is to say.
    if (!run || isFinished(run.status)) return;

    this.subscribe(runId);
  }

  /** Stop watching. Safe to call when nothing is being watched. */
  stop(): void {
    this.source?.close();
    this.source = null;
    this.watching = null;
    this.connected$.set(false);

    if (this.retryTimer !== null) {
      clearTimeout(this.retryTimer);
      this.retryTimer = null;
    }
    this.retryAt = RETRY_MIN_MS;
  }

  /** Forget the run entirely — used when a console leaves and nothing is running. */
  clear(): void {
    this.stop();
    this.run$.set(null);
    this.logs$.set([]);
  }

  private async load(runId: string): Promise<ImportRun | null> {
    try {
      const run = await firstValueFrom(this.http.get<ImportRun>(`/api/v1/import/runs/${runId}`));
      this.run$.set(run);
      this.logs$.set(run.log);
      return run;
    } catch {
      // A run this server has never heard of, or a request that failed. Either way there is no
      // state to show and no stream worth opening; the console renders its own empty state.
      return null;
    }
  }

  private subscribe(runId: string): void {
    const source = new EventSource(`/api/v1/import/runs/${runId}/events`);
    this.source = source;

    source.onopen = () => {
      this.connected$.set(true);
      // The backoff resets on a connection that *opened*, not on one that was attempted — a server
      // refusing every attempt must not be retried every second forever.
      this.retryAt = RETRY_MIN_MS;
    };

    source.addEventListener(IMPORT_EVENT.phase, (event) => {
      const data = parse<PhaseEvent>(event);
      if (data) this.patch({ phase: data.phase });
    });

    source.addEventListener(IMPORT_EVENT.progress, (event) => {
      const data = parse<ProgressEvent>(event);
      if (data) {
        this.patch({
          phase: data.phase,
          current: data.current,
          total: data.total,
          percent: data.percent,
        });
      }
    });

    source.addEventListener(IMPORT_EVENT.log, (event) => {
      const data = parse<LogEvent>(event);
      if (!data) return;

      this.logs$.update((lines) =>
        [...lines, { level: data.level, message: data.message, at: data.at }].slice(-MAX_LOG_LINES),
      );
    });

    source.addEventListener(IMPORT_EVENT.counters, (event) => {
      const data = parse<CountersEvent>(event);
      if (data) this.patch({ counters: data.counters });
    });

    source.addEventListener(IMPORT_EVENT.status, (event) => {
      const data = parse<StatusEvent>(event);
      if (data) {
        this.patch({ status: data.status, finishedAt: data.finishedAt, error: data.error });
      }
      // Always the last event, and the server closes its side. Closing ours here is what stops the
      // browser reconnecting to a stream that has nothing left to say.
      this.stop();
    });

    source.onerror = () => {
      this.connected$.set(false);
      source.close();
      this.source = null;
      this.retry(runId);
    };
  }

  /**
   * Re-read the run, and resubscribe only if it is still going.
   *
   * The re-read is the point: a stream that dropped while the run finished must not become an
   * endless reconnect against a run that ended thirty seconds ago.
   */
  private retry(runId: string): void {
    if (this.watching !== runId) return;

    const delay = this.retryAt;
    this.retryAt = Math.min(this.retryAt * 2, RETRY_MAX_MS);

    this.retryTimer = setTimeout(async () => {
      this.retryTimer = null;
      if (this.watching !== runId) return;

      const run = await this.load(runId);
      if (!run || isFinished(run.status)) {
        this.stop();
        return;
      }
      this.subscribe(runId);
    }, delay);
  }

  /** Merge one event's fields into the run. A no-op before the run resource has been read. */
  private patch(fields: Partial<ImportRun>): void {
    this.run$.update((run) => (run ? { ...run, ...fields } : run));
  }
}

/**
 * An event's `data:` as its payload type, or null.
 *
 * A stream is a network input like any other: one malformed frame must not tear down a console
 * that is otherwise showing a correct run.
 */
function parse<T>(event: Event): T | null {
  try {
    return JSON.parse((event as MessageEvent<string>).data) as T;
  } catch {
    return null;
  }
}
