import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ImportRunStore } from './import-run-store';
import type { ImportRun } from './import.model';

/**
 * A stand-in for the browser's EventSource, which jsdom does not implement.
 *
 * Not a mock of convenience: without it `new EventSource(...)` is a ReferenceError, and the half of
 * this store that matters — what happens when events arrive — could not be tested at all. It records
 * the URL it was opened with and lets a spec deliver one frame at a time.
 */
class FakeEventSource {
  static last: FakeEventSource | null = null;

  readonly listeners = new Map<string, (event: MessageEvent<string>) => void>();
  onopen: (() => void) | null = null;
  onerror: (() => void) | null = null;
  closed = false;

  constructor(readonly url: string) {
    FakeEventSource.last = this;
  }

  addEventListener(name: string, handler: (event: MessageEvent<string>) => void): void {
    this.listeners.set(name, handler);
  }

  close(): void {
    this.closed = true;
  }

  /** Deliver one event, the way the server would. */
  emit(name: string, data: unknown): void {
    this.listeners.get(name)?.({ data: JSON.stringify(data) } as MessageEvent<string>);
  }
}

const RUNNING: ImportRun = {
  runId: 'run-1',
  importerId: 'jira',
  status: 'RUNNING',
  startedAt: '2026-08-12T07:00:00Z',
  finishedAt: null,
  phase: 'issues',
  phases: [
    { id: 'issues', label: 'Importing issues', weight: 70 },
    { id: 'sweep', label: 'Removing deleted issues', weight: 30 },
  ],
  percent: 40,
  current: 4,
  total: 9,
  params: {},
  counters: {},
  warnings: [],
  error: null,
  log: [],
};

describe('ImportRunStore', () => {
  let store: ImportRunStore;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    FakeEventSource.last = null;
    (globalThis as { EventSource?: unknown }).EventSource = FakeEventSource;

    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });

    store = TestBed.inject(ImportRunStore);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    store.clear();
    httpTesting.verify();
    delete (globalThis as { EventSource?: unknown }).EventSource;
  });

  /**
   * The order these two happen in is the whole design (spec §11.4).
   *
   * The run resource is the late-join source of truth and the stream is not a history: a client
   * that subscribed first would show an empty console for a run already at phase four.
   */
  it('reads the run resource before it subscribes', async () => {
    const watching = store.watch('run-1');

    httpTesting.expectOne('/api/v1/import/runs/run-1').flush(RUNNING);
    await watching;

    expect(store.run()?.phase).toBe('issues');
    expect(FakeEventSource.last?.url).toBe('/api/v1/import/runs/run-1/events');
  });

  it('applies progress and log events as they arrive', async () => {
    const watching = store.watch('run-1');
    httpTesting.expectOne('/api/v1/import/runs/run-1').flush(RUNNING);
    await watching;

    const source = FakeEventSource.last;
    source?.emit('progress', {
      runId: 'run-1',
      phase: 'issues',
      current: 9,
      total: 9,
      percent: 80,
    });
    source?.emit('log', {
      runId: 'run-1',
      level: 'INFO',
      message: '9 issues over 1 page(s)',
      at: '2026-08-12T07:00:01Z',
    });

    expect(store.run()?.current).toBe(9);
    expect(store.run()?.percent).toBe(80);
    expect(store.logs().map((line) => line.message)).toEqual(['9 issues over 1 page(s)']);
  });

  /**
   * `status` is always the last event and the server closes its side.
   *
   * Closing ours is what stops the browser reconnecting to a stream that has nothing left to say —
   * an EventSource left open reconnects on its own, forever.
   */
  it('closes the stream on the status event, and keeps the outcome', async () => {
    const watching = store.watch('run-1');
    httpTesting.expectOne('/api/v1/import/runs/run-1').flush(RUNNING);
    await watching;

    const source = FakeEventSource.last;
    source?.emit('status', {
      runId: 'run-1',
      status: 'SUCCEEDED_WITH_WARNINGS',
      finishedAt: '2026-08-12T07:00:04Z',
      warnings: 2,
      error: null,
    });

    expect(store.run()?.status).toBe('SUCCEEDED_WITH_WARNINGS');
    expect(store.isRunning()).toBe(false);
    expect(source?.closed).toBe(true);
  });

  /** A run that has already ended has nothing to stream. Opening one must not hold a connection. */
  it('does not subscribe to a run that is already finished', async () => {
    const watching = store.watch('run-9');

    httpTesting
      .expectOne('/api/v1/import/runs/run-9')
      .flush({ ...RUNNING, runId: 'run-9', status: 'SUCCEEDED', finishedAt: '…' });
    await watching;

    expect(FakeEventSource.last).toBeNull();
  });

  /** One malformed frame must not tear down a console that is otherwise showing a correct run. */
  it('ignores an event whose payload is not JSON', async () => {
    const watching = store.watch('run-1');
    httpTesting.expectOne('/api/v1/import/runs/run-1').flush(RUNNING);
    await watching;

    FakeEventSource.last?.listeners.get('progress')?.({
      data: 'not json',
    } as MessageEvent<string>);

    expect(store.run()?.current).toBe(4);
  });

  it('starts a run and watches it', async () => {
    const starting = store.start('jira');

    httpTesting
      .expectOne((request) => request.url === '/api/v1/import/jira/runs' && request.method === 'POST')
      .flush({ runId: 'run-1' });
    // The start is followed by a read of the run resource, not by a subscription on its own.
    await Promise.resolve();
    httpTesting.expectOne('/api/v1/import/runs/run-1').flush(RUNNING);

    expect(await starting).toBe('run-1');
    expect(store.isRunning()).toBe(true);
  });
});
